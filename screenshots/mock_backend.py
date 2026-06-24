"""
PPNAM Mock Station Backend
==========================
Connects to the MQTT broker and automatically responds to scanner requests
with realistic simulated payloads, so the scanner app can be driven through
its full workflow without the real Station 1 WPF desktop app running.

Usage
-----
  # Normal operation (all steps succeed):
  python mock_backend.py

  # Make a specific step fail (useful for screenshot error states):
  python mock_backend.py --fail-step sap
  python mock_backend.py --fail-step assignment
  python mock_backend.py --fail-step print
  python mock_backend.py --fail-step offload
  python mock_backend.py --fail-step unassign
  python mock_backend.py --fail-step reassign

  # Scanner 2 as the target (for offload-only scenario):
  python mock_backend.py --scanner 2

Dynamic scenario control
------------------------
Write one of the following to scenario.txt (next to this script) to override
the --fail-step at runtime without restarting:

  success           -- all steps succeed
  fail_sap          -- SAP lookup fails
  fail_assignment   -- tag assignment fails
  fail_print        -- print all fails
  fail_offload      -- offload fails
  fail_unassign     -- unassign fails
  fail_reassign     -- reassign fails
  slow              -- 3.5 s delay on product/assignment/print/offload handlers
                       (used by generate_sop.py to keep Working popups visible for screenshots)

MQTT log
--------
Assignment results are appended to mqtt_log.txt (in the screenshots folder)
in the same pipe-delimited format that the existing mqtt_subscribe.py uses,
so walk.py barcode lookups continue to work when paired with this backend.
"""

from __future__ import annotations

import argparse
import json
import random
import re
import ssl
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import paho.mqtt.client as mqtt

# ─────────────────────────────────── broker config ────────────────────────────
HOST = "mqtt.sysone.co.za"
PORT = 443
USER = "admin"
PASSWORD = "admin"
WS_PATH = "/mqtt"

# ─────────────────────────────────── paths ────────────────────────────────────
HERE = Path(__file__).resolve().parent
SCENARIO_FILE = HERE / "scenario.txt"
MQTT_LOG_FILE = HERE / "mqtt_log.txt"

# ─────────────────────────────────── product data ─────────────────────────────
PRODUCTS_PO = [
    {
        "productCode": "1500000331",
        "productDescription": "MASTERBATCH ME 005-014A DESICCANT",
        "openQuantity": 1750.0,
        "uom": "KG",
        "expectedPalletQty": 0,
        "bagSize": "25.000",
        "bagCount": 55,
        "bagsPerPallet": 55,
        "batchReference": "",
    }
]

PRODUCTS_ST = [
    {
        "productCode": "1600000003",
        "productDescription": "HD 5295HL (HTA 001 HP5) SAFRIPOL",
        "openQuantity": 11000.0,
        "uom": "KG",
        "expectedPalletQty": 0,
        "bagSize": "25.000",
        "bagCount": 55,
        "bagsPerPallet": 55,
        "batchReference": "",
    },
    {
        "productCode": "1600000062",
        "productDescription": "EXEED Though+ m 0512.RA (EXEED XP7052RA)",
        "openQuantity": 11000.0,
        "uom": "KG",
        "expectedPalletQty": 0,
        "bagSize": "25.000",
        "bagCount": 55,
        "bagsPerPallet": 55,
        "batchReference": "",
    },
]


# ─────────────────────────────────── session model ────────────────────────────
class MockSession:
    def __init__(self, session_id: int, doc_number: str, doc_type: str) -> None:
        self.session_id = session_id
        self.doc_number = doc_number
        self.doc_type = doc_type  # "PurchaseOrder" | "StockTransfer"
        self.pallet_counter = 0
        # tagId -> assignment dict
        self.assignments: dict[str, dict] = {}
        # pallet codes that have been offloaded
        self.offloaded: set[str] = set()


# ─────────────────────────────────── backend ──────────────────────────────────
class MockBackend:
    def __init__(self, scanner_id: int = 1, station_id: int = 1, fail_step: str | None = None) -> None:
        self.scanner_id = scanner_id
        self.station_id = station_id
        self._cli_fail_step = fail_step  # from command line
        self.session_counter = 0
        self.current_session: MockSession | None = None
        self.client: mqtt.Client | None = None

    # ── helpers ───────────────────────────────────────────────────────────────

    def _ts(self) -> str:
        return datetime.now(timezone.utc).isoformat()

    def _gen_barcode(self) -> str:
        return str(random.randint(1_000_000_000, 9_999_999_999))

    def _fail_step(self) -> str | None:
        """Return the current fail scenario.  Reads scenario.txt at runtime so
        generate_sop.py can switch scenarios without restarting this process."""
        try:
            text = SCENARIO_FILE.read_text(encoding="utf-8").strip().lower()
            if text == "success":
                return None
            if text.startswith("fail_"):
                return text[5:]  # e.g. "fail_sap" → "sap"
        except FileNotFoundError:
            pass
        return self._cli_fail_step

    def _slow_response(self) -> None:
        """Sleep 3.5 s when scenario.txt contains 'slow'.
        Gives generate_sop.py time to screenshot Working popups before the response arrives."""
        try:
            text = SCENARIO_FILE.read_text(encoding="utf-8").strip().lower()
            if text == "slow":
                time.sleep(3.5)
        except FileNotFoundError:
            pass

    def _pub(self, subtopic: str, payload: dict, delay: float = 0.3) -> None:
        """Publish a station response after a short realistic delay."""
        time.sleep(delay)
        topic = f"PPNAM/station_{self.station_id}/{subtopic}"
        body = json.dumps(payload)
        self.client.publish(topic, body, qos=1)  # type: ignore[union-attr]
        print(f"  → {topic}")

    def _append_log(self, topic: str, payload: dict) -> None:
        """Write to mqtt_log.txt in the same format as mqtt_subscribe.py."""
        line = f"{int(time.time() * 1000)}|{topic}|{json.dumps(payload)}\n"
        with MQTT_LOG_FILE.open("a", encoding="utf-8") as fh:
            fh.write(line)
            if "/assignment_result" in topic:
                bc = payload.get("barcode", "")
                pc = payload.get("palletCode", "")
                st = payload.get("status", "")
                fh.write(f"# BARCODE status={st} pallet={pc} barcode={bc}\n")

    # ── MQTT callbacks ────────────────────────────────────────────────────────

    def _on_connect(self, client: mqtt.Client, _u, _f, rc, _props=None) -> None:
        print(f"[mock] connected rc={rc}")
        # Subscribe to all scanners so this backend covers scanner_1 AND scanner_2
        for n in [1, 2]:
            client.subscribe(f"PPNAM/scanner_{n}/#", qos=1)
            print(f"[mock] subscribed PPNAM/scanner_{n}/#")
        # Publish station online status (retained) so the scanner app knows the station is up
        for n in [1, 2]:
            client.publish(f"PPNAM/station_{n}/status", "online", qos=1, retain=True)
            print(f"[mock] published PPNAM/station_{n}/status = online")

    def _on_message(self, _client, _u, msg: mqtt.MQTTMessage) -> None:
        topic: str = msg.topic
        try:
            payload: dict = json.loads(msg.payload.decode("utf-8"))
        except Exception:
            return

        device_id: str = payload.get("deviceId", "")
        if not device_id.startswith("scanner_"):
            return

        print(f"  ← {topic}")

        if topic.endswith("/sap"):
            self._handle_sap(payload)
        elif topic.endswith("/sap_products_request"):
            self._handle_products_request(payload)
        elif topic.endswith("/sap_products_selected"):
            self._handle_products_selected(payload)
        elif re.search(r"/assignment_v2$", topic):
            self._handle_assignment(payload)
        elif topic.endswith("/assignment"):
            # allAssigned notification
            if payload.get("allAssigned"):
                self._handle_all_assigned(payload)
        elif topic.endswith("/print_all"):
            self._handle_print_all(payload)
        elif re.search(r"/offload_v2$", topic) or (
            topic.endswith("/offload") and isinstance(payload, dict) and "tagId" in payload
        ):
            self._handle_offload(payload)
        elif topic.endswith("/all_offloaded"):
            self._handle_all_offloaded(payload)
        elif topic.endswith("/unassign"):
            self._handle_unassign(payload)
        elif topic.endswith("/reassign"):
            self._handle_reassign(payload)

    # ── request handlers ──────────────────────────────────────────────────────

    def _handle_sap(self, payload: dict) -> None:
        device_id = payload["deviceId"]
        doc_number = payload.get("sourceDocumentNumber", "000000000")
        doc_type = payload.get("sourceDocumentType", "Purchase Order")

        if self._fail_step() == "sap":
            self._pub("sap_result", {
                "ts": self._ts(),
                "deviceId": device_id,
                "status": "Failed",
                "sessionId": None,
                "sourceDocumentType": doc_type,
                "sourceDocumentNumber": doc_number,
                "message": f"Document {doc_number} not found or is already closed in SAP.",
            })
            return

        self.session_counter += 1
        session_id = self.session_counter
        doc_type_norm = "PurchaseOrder" if "purchase" in doc_type.lower() else "StockTransfer"
        self.current_session = MockSession(session_id, doc_number, doc_type_norm)

        self._pub("sap_result", {
            "ts": self._ts(),
            "deviceId": device_id,
            "status": "Success",
            "sessionId": session_id,
            "sourceDocumentType": doc_type,
            "sourceDocumentNumber": doc_number,
            "message": "Session loaded successfully",
        })

    def _handle_products_request(self, payload: dict) -> None:
        device_id = payload["deviceId"]
        session = self.current_session
        if session is None:
            return

        products = PRODUCTS_ST if session.doc_type == "StockTransfer" else PRODUCTS_PO

        self._pub("sap_products_response", {
            "ts": self._ts(),
            "deviceId": device_id,
            "status": "Success",
            "sessionId": session.session_id,
            "sourceDocumentType": session.doc_type,
            "sourceDocumentNumber": session.doc_number,
            "products": products,
            "message": "Products returned successfully",
        })

    def _handle_products_selected(self, payload: dict) -> None:
        device_id = payload["deviceId"]
        session = self.current_session
        if session is None:
            return
        self._slow_response()

        selected_codes: list[str] = payload.get("selectedProductCodes", [])
        all_products = PRODUCTS_ST if session.doc_type == "StockTransfer" else PRODUCTS_PO
        selected_products = [p for p in all_products if p["productCode"] in selected_codes]

        self._pub("sap_products_selected_result", {
            "ts": self._ts(),
            "deviceId": device_id,
            "status": "Success",
            "sessionId": session.session_id,
            "sourceDocumentType": session.doc_type,
            "sourceDocumentNumber": session.doc_number,
            "selectedCount": len(selected_codes),
            "totalSelectedForDocument": len(selected_codes),
            "message": (
                f"Added {len(selected_codes)} new product selection(s). "
                f"{len(selected_codes)}/{len(all_products)} product line(s) selected for this document."
            ),
        }, delay=0.2)

        # tag_assignment_request — required for TagAssignmentActivity to load products
        product_metas = [
            {
                "productCode": p["productCode"],
                "productDescription": p["productDescription"],
                "bagSize": p["bagSize"],
                "bagsPerPallet": p["bagsPerPallet"],
            }
            for p in selected_products
        ]
        self._pub("tag_assignment_request", {
            "ts": self._ts(),
            "deviceId": device_id,
            "sessionId": session.session_id,
            "sourceDocumentType": session.doc_type,
            "sourceDocumentNumber": session.doc_number,
            "selectedProductCodes": selected_codes,
            "products": product_metas,
            "syncReason": "ProductSelectionChanged",
            "totalPalletCount": 0,
            "assignedPalletCount": 0,
            "pendingTagCount": 0,
            "readyToOffloadCount": 0,
            "offloadedCount": 0,
            "palletStates": [],
            "message": None,
        }, delay=0.1)

        # sap_products_response refresh
        self._pub("sap_products_response", {
            "ts": self._ts(),
            "deviceId": device_id,
            "status": "Success",
            "sessionId": session.session_id,
            "sourceDocumentType": session.doc_type,
            "sourceDocumentNumber": session.doc_number,
            "products": all_products,
            "message": f"Product selection changed: Added {len(selected_codes)} new product selection(s).",
        }, delay=0.1)

    def _handle_assignment(self, payload: dict) -> None:
        device_id = payload["deviceId"]
        session = self.current_session
        if session is None:
            return
        self._slow_response()

        tag_id: str = payload.get("tagId", "")
        product_code: str = payload.get("productCode", "")
        seq: int = payload.get("actualPalletSequence", 1)

        if self._fail_step() == "assignment":
            self._pub("assignment_result", {
                "ts": self._ts(),
                "deviceId": device_id,
                "status": "Failed",
                "sessionId": session.session_id,
                "sourceDocumentNumber": session.doc_number,
                "palletRowId": None,
                "palletCode": None,
                "tagId": tag_id,
                "barcode": None,
                "actualPalletSequence": seq,
                "message": f"Tag {tag_id} is already assigned to another pallet.",
            })
            return

        session.pallet_counter += 1
        pallet_code = f"{session.doc_number}-RS{session.session_id}-D{session.pallet_counter:03d}"
        pallet_row_id = 100 + session.pallet_counter
        barcode = self._gen_barcode()

        session.assignments[tag_id] = {
            "palletRowId": pallet_row_id,
            "palletCode": pallet_code,
            "barcode": barcode,
            "productCode": product_code,
        }

        resp = {
            "ts": self._ts(),
            "deviceId": device_id,
            "status": "Success",
            "sessionId": session.session_id,
            "sourceDocumentNumber": session.doc_number,
            "palletRowId": pallet_row_id,
            "palletCode": pallet_code,
            "tagId": tag_id,
            "barcode": barcode,
            "actualPalletSequence": session.pallet_counter,
            "message": (
                f"Dynamic pallet {pallet_code} created, tag {tag_id} assigned, "
                f"and barcode {barcode} generated successfully."
            ),
        }
        self._pub("assignment_result", resp)
        result_topic = f"PPNAM/station_{self.station_id}/assignment_result"
        self._append_log(result_topic, resp)

    def _handle_all_assigned(self, payload: dict) -> None:
        device_id = payload["deviceId"]
        session = self.current_session
        if session is None:
            return

        self._pub("all_assigned_result", {
            "ts": self._ts(),
            "deviceId": device_id,
            "status": "Success",
            "sessionId": session.session_id,
            "palletCount": session.pallet_counter,
            "message": f"Loaded session {session.doc_type}: {session.doc_number}",
        })

    def _handle_print_all(self, payload: dict) -> None:
        device_id = payload["deviceId"]
        session = self.current_session
        if session is None:
            return
        self._slow_response()

        if self._fail_step() == "print":
            self._pub("print_all_result", {
                "ts": self._ts(),
                "deviceId": device_id,
                "status": "Failed",
                "sessionId": session.session_id,
                "sourceDocumentNumber": session.doc_number,
                "totalFound": session.pallet_counter,
                "printedCount": 0,
                "message": (
                    "Printer failed. Batch print stopped after 0 label(s). "
                    "Could not send label job to 192.168.1.183:9100 or 192.168.1.183:6101."
                ),
            }, delay=1.5)
            return

        self._pub("print_all_result", {
            "ts": self._ts(),
            "deviceId": device_id,
            "status": "Success",
            "sessionId": session.session_id,
            "sourceDocumentNumber": session.doc_number,
            "totalFound": session.pallet_counter,
            "printedCount": session.pallet_counter,
            "message": f"All {session.pallet_counter} label(s) printed successfully.",
        }, delay=1.5)

    def _handle_offload(self, payload: dict) -> None:
        device_id = payload["deviceId"]
        session = self.current_session
        if session is None:
            return
        self._slow_response()

        tag_id: str = payload.get("tagId", "")
        barcode: str = payload.get("barcode", "")
        bag_count: int = payload.get("bagCount", 55)
        bag_weight: float = float(payload.get("bagWeightKg", 25))

        # Find assignment by tagId first, then barcode
        assignment = session.assignments.get(tag_id)
        if assignment is None:
            assignment = next(
                (a for a in session.assignments.values() if a["barcode"] == barcode), None
            )

        if self._fail_step() == "offload":
            self._pub("offload_result", {
                "ts": self._ts(),
                "deviceId": device_id,
                "status": "Failed",
                "sessionId": session.session_id,
                "sourceDocumentNumber": session.doc_number,
                "tagId": tag_id,
                "barcode": barcode,
                "message": "Barcode and RFID tag do not match. Ensure you are scanning the correct pallet.",
            })
            return

        pallet_code = assignment["palletCode"] if assignment else f"UNKNOWN-{barcode[-4:]}"
        pallet_row_id = assignment["palletRowId"] if assignment else None
        product_code = assignment["productCode"] if assignment else ""
        pallet_weight = bag_count * bag_weight
        batch_ref = f"{session.doc_number}-{datetime.now().strftime('%Y%m%d%H%M%S')}"

        session.offloaded.add(pallet_code)

        self._pub("offload_result", {
            "ts": self._ts(),
            "deviceId": device_id,
            "status": "Success",
            "sessionId": session.session_id,
            "sourceDocumentNumber": session.doc_number,
            "palletRowId": pallet_row_id,
            "palletCode": pallet_code,
            "tagId": tag_id,
            "barcode": barcode,
            "productCode": product_code,
            "batchRef": batch_ref,
            "bagWeightKg": bag_weight,
            "bagCount": bag_count,
            "palletWeight": float(pallet_weight),
            "pairValidated": True,
            "sapPostStatus": "NotStarted",
            "message": f"Offload updated for pallet {pallet_code}. Waiting for All Offloaded confirmation.",
        })

    def _handle_all_offloaded(self, payload: dict) -> None:
        device_id = payload["deviceId"]
        session = self.current_session
        if session is None:
            return

        total = session.pallet_counter
        finalized = len(session.offloaded)

        self._pub("all_offloaded_result", {
            "ts": self._ts(),
            "deviceId": device_id,
            "status": "Success",
            "sessionId": session.session_id,
            "sourceDocumentNumber": session.doc_number,
            "totalPalletCount": total,
            "finalizedPalletCount": finalized,
            "incompletePalletCount": max(0, total - finalized),
            "sapPostStatus": "Complete",
            "sapPostedCount": finalized,
            "sapFailedCount": 0,
            "documentCompleted": None,
            "message": (
                f"All Offloaded accepted. {finalized} pallet(s) finalized and queued for SAP upload. "
                f"All product line(s) for document {session.doc_number} are received, assigned, and offloaded. "
                f"SAP posting complete. {finalized} pallet(s) posted."
            ),
        }, delay=1.5)

    def _handle_unassign(self, payload: dict) -> None:
        device_id = payload["deviceId"]
        tag_id: str = payload.get("tagId", "")

        if self._fail_step() == "unassign":
            self._pub("unassign_result", {
                "ts": self._ts(),
                "deviceId": device_id,
                "status": "Failed",
                "tagId": tag_id,
                "message": "Tag not found or not currently assigned to any pallet.",
            })
            return

        self._pub("unassign_result", {
            "ts": self._ts(),
            "deviceId": device_id,
            "status": "Success",
            "tagId": tag_id,
            "message": f"Tag {tag_id} unassigned successfully.",
        })

    def _handle_reassign(self, payload: dict) -> None:
        device_id = payload["deviceId"]
        tag_id: str = payload.get("tagId", "")
        barcode: str = payload.get("barcode", "")

        if self._fail_step() == "reassign":
            self._pub("reassign_result", {
                "ts": self._ts(),
                "deviceId": device_id,
                "status": "Failed",
                "tagId": tag_id,
                "barcode": barcode,
                "message": "Barcode not found in any active receiving session.",
            })
            return

        self._pub("reassign_result", {
            "ts": self._ts(),
            "deviceId": device_id,
            "status": "Success",
            "tagId": tag_id,
            "barcode": barcode,
            "message": f"Tag {tag_id} reassigned to pallet {barcode} successfully.",
        })

    # ── run ───────────────────────────────────────────────────────────────────

    def run(self) -> None:
        client = mqtt.Client(
            client_id=f"PPNAM_MockStation_{uuid.uuid4().hex[:8]}",
            transport="websockets",
            protocol=mqtt.MQTTv311,
        )
        client.ws_set_options(path=WS_PATH)
        client.username_pw_set(USER, PASSWORD)

        ctx = ssl.create_default_context()
        client.tls_set_context(ctx)

        self.client = client
        client.on_connect = self._on_connect
        client.on_message = self._on_message

        scenario = self._cli_fail_step or "success"
        print(f"[mock] starting — fail_step={scenario!r}")
        print(f"[mock] connecting wss://{HOST}:{PORT}{WS_PATH} ...")

        # Initialise the mqtt_log file so walk.py barcode lookups start fresh
        with MQTT_LOG_FILE.open("w", encoding="utf-8") as fh:
            fh.write(f"# START {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")

        client.connect(HOST, PORT, keepalive=30)
        client.loop_forever()


# ─────────────────────────────────── entrypoint ───────────────────────────────
def main() -> None:
    parser = argparse.ArgumentParser(description="PPNAM Mock Station Backend")
    parser.add_argument("--scanner", type=int, default=1, help="Scanner device number (default: 1)")
    parser.add_argument("--station", type=int, default=1, help="Station number (default: 1)")
    parser.add_argument(
        "--fail-step",
        choices=["sap", "assignment", "print", "offload", "unassign", "reassign"],
        default=None,
        help="Make this step fail every time (overridden by scenario.txt at runtime)",
    )
    args = parser.parse_args()

    backend = MockBackend(args.scanner, args.station, args.fail_step)
    try:
        backend.run()
    except KeyboardInterrupt:
        print("\n[mock] stopped.")


if __name__ == "__main__":
    main()
