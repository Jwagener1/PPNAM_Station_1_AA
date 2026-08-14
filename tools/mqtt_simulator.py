#!/usr/bin/env python3
"""
Station 1 backend simulator.

Stands in for the Windows PPNAM-Station-1-App over MQTT so the Android
Station-1 scanner app can be driven through full end-to-end workflows
without the real Windows app or a live SAP backend running.

Connects to the same broker/credentials/topic convention as the real
Windows app (station_1), subscribes to every scanner-inbound topic, and
publishes plausible "station" responses matching the real app's JSON
contract, reverse-engineered from Device_Initializing/RfidDeviceInitializer.cs
and Models/Rfid*.cs in PPNAM-Station-1-App (read-only reference, no code
shared or copied from that repo).

Usage:
    python tools/mqtt_simulator.py [--station-id 1] [--scanner-id 1]

While running, type commands at the prompt:
    fail <kind>       - make the NEXT response of that kind an error (one-shot)
                        kinds: sap, sap_products_request, sap_products_selected,
                        assignment, all_assigned, print_all, offload,
                        all_offloaded, unassign, reassign
    offline            - simulate the station going offline (retained status=offline)
    online             - simulate the station coming back online
    state              - print current session state
    quit               - exit
"""
import argparse
import json
import sys
import threading
import time
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

BROKER_HOST = "mqtt.sysone.co.za"
BROKER_PORT = 443
MQTT_USERNAME = "admin"
MQTT_PASSWORD = "admin"

FAKE_PRODUCTS = [
    {
        "productCode": "ITEM001",
        "productDescription": "50kg Maize Meal (Simulated)",
        "openQuantity": 1000.0,
        "uom": "BAG",
        "expectedPalletQty": 10,
        "bagSize": "50",
        "bagCount": 40,
        "bagsPerPallet": 40,
        "batchReference": "BATCH-SIM-001",
    },
    {
        "productCode": "ITEM002",
        "productDescription": "25kg Feed Pellet (Simulated)",
        "openQuantity": 500.0,
        "uom": "BAG",
        "expectedPalletQty": 5,
        "bagSize": "25",
        "bagCount": 20,
        "bagsPerPallet": 20,
        "batchReference": "BATCH-SIM-002",
    },
]


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "0000Z"


class Session:
    def __init__(self, session_id: int, doc_type: str, doc_number: str):
        self.session_id = session_id
        self.doc_type = doc_type
        self.doc_number = doc_number
        self.assigned_count = 0
        self.offloaded_count = 0
        self.pallet_row_seq = 0


class StationSimulator:
    def __init__(self, station_id: int, scanner_id: int):
        self.station_id = station_id
        self.scanner_id = scanner_id
        self.station_topic = f"PPNAM/station_{station_id}"
        self.app_device_id = f"station_{station_id}"  # Windows always echoes its OWN id, not the scanner's
        self.force_fail = set()  # topic "kinds" whose next response should be an error (one-shot)
        self.lock = threading.Lock()

        self.sessions_by_id: dict[int, Session] = {}
        self.sessions_by_doc: dict[str, Session] = {}
        self.next_session_id = 1001
        self.next_pallet_row_id = 5001
        self.history_count = 0

        self.client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION1,
            client_id=f"StationSimulator_{station_id}",
            transport="websockets",
            protocol=mqtt.MQTTv311,
        )
        self.client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)
        self.client.tls_set()
        self.client.will_set(f"{self.station_topic}/status", "offline", qos=1, retain=True)
        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message

        # topic-suffix -> handler(payload: dict)
        self.handlers = {
            "sap": self.handle_sap,
            "sap_products_request": self.handle_sap_products_request,
            "sap_products_selected": self.handle_sap_products_selected,
            "assignment": self.handle_assignment_or_all_assigned,
            "assignment_v2": self.handle_assignment_or_all_assigned,
            "print_all": self.handle_print_all,
            "offload": self.handle_offload,
            "all_offloaded": self.handle_all_offloaded,
            "unassign": self.handle_unassign,
            "reassign": self.handle_reassign,
        }

    # -- connection -------------------------------------------------------
    def connect(self):
        self.client.connect(BROKER_HOST, BROKER_PORT, keepalive=15)
        self.client.loop_start()

    def _on_connect(self, client, userdata, flags, rc):
        print(f"[sim] connected rc={rc}")
        client.publish(f"{self.station_topic}/status", "online", qos=1, retain=True)
        client.subscribe("PPNAM/#", qos=1)

    def _on_message(self, client, userdata, msg):
        topic = msg.topic
        try:
            payload = json.loads(msg.payload.decode("utf-8"))
        except Exception:
            return  # not JSON (e.g. retained status messages) - ignore

        parts = topic.split("/")
        if len(parts) < 3:
            return
        device_id, suffix = parts[1], "/".join(parts[2:])

        if not device_id.startswith("scanner_"):
            return  # only react to scanner-originated inbound topics

        handler = self.handlers.get(suffix)
        if handler is None:
            return

        print(f"[sim] <- {topic}: {json.dumps(payload)}")
        try:
            handler(payload)
        except Exception as e:
            print(f"[sim] ERROR handling {topic}: {e}")

    def publish_station(self, suffix: str, payload: dict):
        topic = f"{self.station_topic}/{suffix}"
        body = json.dumps(payload)
        print(f"[sim] -> {topic}: {body}")
        self.client.publish(topic, body, qos=1)

    def should_fail(self, kind: str) -> bool:
        with self.lock:
            if kind in self.force_fail:
                self.force_fail.discard(kind)
                return True
        return False

    # -- session resolution -------------------------------------------------
    def resolve_session(self, payload: dict) -> Session | None:
        raw_sid = payload.get("sessionId")
        if raw_sid not in (None, ""):
            try:
                sid = int(raw_sid)
                if sid in self.sessions_by_id:
                    return self.sessions_by_id[sid]
            except (TypeError, ValueError):
                pass
        doc_number = payload.get("sourceDocumentNumber")
        if doc_number and doc_number in self.sessions_by_doc:
            return self.sessions_by_doc[doc_number]
        return None

    # -- handlers -----------------------------------------------------------
    def handle_sap(self, payload):
        doc_type = payload.get("sourceDocumentType", "")
        doc_number = payload.get("sourceDocumentNumber", "")

        if self.should_fail("sap") or not doc_type or not doc_number:
            self.publish_station("sap_result", {
                "ts": now_iso(),
                "deviceId": self.app_device_id,
                "status": "Failed",
                "sourceDocumentType": doc_type,
                "sourceDocumentNumber": doc_number,
                "message": "Document type or document number was missing",
            })
            return

        session = Session(self.next_session_id, doc_type, doc_number)
        self.next_session_id += 1
        self.sessions_by_id[session.session_id] = session
        self.sessions_by_doc[doc_number] = session

        self.publish_station("sap_result", {
            "ts": now_iso(),
            "deviceId": self.app_device_id,
            "status": "Success",
            "sessionId": session.session_id,
            "sourceDocumentType": doc_type,
            "sourceDocumentNumber": doc_number,
            "message": "Session loaded successfully",
        })

    def handle_sap_products_request(self, payload):
        doc_type = payload.get("sourceDocumentType", "")
        doc_number = payload.get("sourceDocumentNumber", "")
        session = self.resolve_session(payload)

        if self.should_fail("sap_products_request") or session is None:
            self.publish_station("sap_products_response", {
                "ts": now_iso(),
                "deviceId": self.app_device_id,
                "status": "Failed",
                "sourceDocumentType": doc_type,
                "sourceDocumentNumber": doc_number,
                "products": [],
                "message": "No loaded receiving session was found for that source document.",
            })
            return

        self.publish_station("sap_products_response", {
            "ts": now_iso(),
            "deviceId": self.app_device_id,
            "status": "Success",
            "sessionId": session.session_id,
            "sourceDocumentType": session.doc_type,
            "sourceDocumentNumber": session.doc_number,
            "products": FAKE_PRODUCTS,
            "message": "Products returned successfully",
        })

    def handle_sap_products_selected(self, payload):
        doc_type = payload.get("sourceDocumentType", "")
        doc_number = payload.get("sourceDocumentNumber", "")
        codes = payload.get("selectedProductCodes") or []
        session = self.resolve_session(payload)

        if self.should_fail("sap_products_selected") or session is None or not codes:
            self.publish_station("sap_products_selected_result", {
                "ts": now_iso(),
                "deviceId": self.app_device_id,
                "status": "Failed",
                "sourceDocumentType": doc_type,
                "sourceDocumentNumber": doc_number,
                "selectedCount": 0,
                "message": "No products were selected" if not codes else
                           "No loaded receiving session was found for that source document.",
            })
            return

        self.publish_station("sap_products_selected_result", {
            "ts": now_iso(),
            "deviceId": self.app_device_id,
            "status": "Success",
            "sessionId": session.session_id,
            "sourceDocumentType": session.doc_type,
            "sourceDocumentNumber": session.doc_number,
            "selectedCount": len(codes),
            "totalSelectedForDocument": len(codes),
            "message": "Product selection saved",
        })

    def handle_assignment_or_all_assigned(self, payload):
        if payload.get("allAssigned"):
            self._handle_all_assigned(payload)
        else:
            self._handle_single_assignment(payload)

    def _handle_single_assignment(self, payload):
        session = self.resolve_session(payload)
        tag_id = payload.get("tagId", "")
        doc_number = payload.get("sourceDocumentNumber", "")
        actual_sequence = payload.get("actualPalletSequence")

        if self.should_fail("assignment") or session is None:
            self.publish_station("assignment_result", {
                "ts": now_iso(),
                "deviceId": self.app_device_id,
                "status": "Failed",
                "sourceDocumentNumber": doc_number,
                "tagId": tag_id,
                "message": "No loaded receiving session was found for that source document.",
            })
            return

        session.assigned_count += 1
        session.pallet_row_seq += 1
        self.next_pallet_row_id += 1
        pallet_code = f"PALLET-{session.pallet_row_seq:03d}"
        barcode = f"BC{abs(hash((session.session_id, session.pallet_row_seq))) % 10**10:010d}"

        self.publish_station("assignment_result", {
            "ts": now_iso(),
            "deviceId": self.app_device_id,
            "status": "Success",
            "sessionId": session.session_id,
            "sourceDocumentNumber": session.doc_number,
            "palletRowId": self.next_pallet_row_id,
            "palletCode": pallet_code,
            "tagId": tag_id,
            "barcode": barcode,
            "actualPalletSequence": actual_sequence,
            "message": f"{pallet_code} assigned",
        })

    def _handle_all_assigned(self, payload):
        session = self.resolve_session(payload)

        if self.should_fail("all_assigned") or session is None:
            self.publish_station("all_assigned_result", {
                "ts": now_iso(),
                "deviceId": self.app_device_id,
                "status": "Failed",
                "message": "No loaded receiving session was found for that source document.",
            })
            return

        self.publish_station("all_assigned_result", {
            "ts": now_iso(),
            "deviceId": self.app_device_id,
            "status": "Success",
            "sessionId": session.session_id,
            "palletCount": session.assigned_count,
            "message": f"All {session.assigned_count} pallets assigned",
        })

    def handle_print_all(self, payload):
        session = self.resolve_session(payload)
        doc_number = payload.get("sourceDocumentNumber", "")

        if self.should_fail("print_all") or session is None or session.assigned_count == 0:
            self.publish_station("print_all_result", {
                "ts": now_iso(),
                "deviceId": self.app_device_id,
                "status": "Failed",
                "sourceDocumentNumber": doc_number,
                "totalFound": 0,
                "printedCount": 0,
                "message": "No pending RFID-assigned labels are available to print.",
            })
            return

        self.publish_station("print_all_result", {
            "ts": now_iso(),
            "deviceId": self.app_device_id,
            "status": "Success",
            "sessionId": session.session_id,
            "sourceDocumentNumber": session.doc_number,
            "totalFound": session.assigned_count,
            "printedCount": session.assigned_count,
            "message": f"Printed {session.assigned_count} labels",
        })

    def handle_offload(self, payload):
        session = self.resolve_session(payload)
        tag_id = payload.get("tagId", "")
        barcode = payload.get("barcode", "")
        doc_number = payload.get("sourceDocumentNumber", "")
        product_code = payload.get("productCode", FAKE_PRODUCTS[0]["productCode"])
        batch_ref = payload.get("batchRef", "")
        bag_weight = payload.get("bagWeightKg")
        bag_count = payload.get("bagCount")

        if self.should_fail("offload") or session is None:
            self.publish_station("offload_result", {
                "ts": now_iso(),
                "deviceId": self.app_device_id,
                "status": "Failed",
                "sourceDocumentNumber": doc_number,
                "tagId": tag_id,
                "barcode": barcode,
                "message": "No loaded receiving session was found for that source document.",
            })
            return

        session.offloaded_count += 1
        pallet_weight = (bag_weight or 0) * (bag_count or 1) if bag_weight else None

        self.publish_station("offload_result", {
            "ts": now_iso(),
            "deviceId": self.app_device_id,
            "status": "Success",
            "sessionId": session.session_id,
            "sourceDocumentNumber": session.doc_number,
            "palletRowId": self.next_pallet_row_id,
            "palletCode": f"PALLET-{session.offloaded_count:03d}",
            "tagId": tag_id,
            "barcode": barcode,
            "productCode": product_code,
            "batchRef": batch_ref,
            "bagWeightKg": bag_weight,
            "bagCount": bag_count,
            "palletWeight": pallet_weight,
            "pairValidated": True,
            "sapPostStatus": "NotStarted",
            "message": "Offload recorded",
        })

    def handle_all_offloaded(self, payload):
        session = self.resolve_session(payload)
        doc_number = payload.get("sourceDocumentNumber", "")

        if self.should_fail("all_offloaded") or session is None:
            self.publish_station("all_offloaded_result", {
                "ts": now_iso(),
                "deviceId": self.app_device_id,
                "status": "Failed",
                "sourceDocumentNumber": doc_number,
                "message": "No loaded receiving session was found for that source document.",
            })
            return

        total = max(session.assigned_count, session.offloaded_count)
        finalized = session.offloaded_count
        incomplete = max(total - finalized, 0)

        self.publish_station("all_offloaded_result", {
            "ts": now_iso(),
            "deviceId": self.app_device_id,
            "status": "Success",
            "sessionId": session.session_id,
            "sourceDocumentNumber": session.doc_number,
            "totalPalletCount": total,
            "finalizedPalletCount": finalized,
            "incompletePalletCount": incomplete,
            "sapPostStatus": "Complete",
            "sapPostedCount": finalized,
            "sapFailedCount": 0,
            "documentCompleted": True,
            "message": f"Session complete. {finalized} pallets finalized.",
        })

    def handle_unassign(self, payload):
        tag_id = payload.get("tagId", "")

        if self.should_fail("unassign"):
            self.publish_station("unassign_result", {
                "ts": now_iso(),
                "deviceId": self.app_device_id,
                "status": "Failed",
                "tagId": tag_id,
                "message": f"No pallet found for tag {tag_id}",
            })
            return

        self.history_count += 1
        self.publish_station("unassign_result", {
            "ts": now_iso(),
            "deviceId": self.app_device_id,
            "status": "Success",
            "tagId": tag_id,
            "palletCount": 1,
            "historyCount": self.history_count,
            "message": f"Tag {tag_id} unassigned",
        })

    def handle_reassign(self, payload):
        tag_id = payload.get("tagId", "")
        barcode = payload.get("barcode", "")

        # Real app uses "Error" (not "Failed") for this one topic - mirrored faithfully.
        if self.should_fail("reassign"):
            self.publish_station("reassign_result", {
                "ts": now_iso(),
                "deviceId": self.app_device_id,
                "status": "Error",
                "tagId": tag_id,
                "barcode": barcode,
                "message": f"Invalid Barcode: {barcode} does not exist in the system.",
            })
            return

        self.publish_station("reassign_result", {
            "ts": now_iso(),
            "deviceId": self.app_device_id,
            "status": "Success",
            "tagId": tag_id,
            "barcode": barcode,
            "palletCode": f"PALLET-{barcode[-3:] if len(barcode) >= 3 else '001'}",
            "message": f"Tag reassigned successfully to Barcode {barcode}",
        })

    # -- manual triggers ------------------------------------------------------
    def go_offline(self):
        self.client.publish(f"{self.station_topic}/status", "offline", qos=1, retain=True)

    def go_online(self):
        self.client.publish(f"{self.station_topic}/status", "online", qos=1, retain=True)

    def print_state(self):
        with self.lock:
            print(f"[sim] next_session_id={self.next_session_id} pending_fail={self.force_fail}")
        for sid, s in self.sessions_by_id.items():
            print(f"  session {sid}: doc={s.doc_type}/{s.doc_number} assigned={s.assigned_count} offloaded={s.offloaded_count}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--station-id", type=int, default=1)
    parser.add_argument("--scanner-id", type=int, default=1)
    args = parser.parse_args()

    sim = StationSimulator(args.station_id, args.scanner_id)
    sim.connect()
    print(f"[sim] Station {args.station_id} simulator running. Scanner id {args.scanner_id}. Ctrl+C to quit.")

    try:
        while True:
            line = input("> ").strip()
            if not line:
                continue
            if line == "quit":
                break
            elif line == "offline":
                sim.go_offline()
            elif line == "online":
                sim.go_online()
            elif line == "state":
                sim.print_state()
            elif line.startswith("fail "):
                kind = line.split(" ", 1)[1].strip()
                sim.force_fail.add(kind)
                print(f"[sim] next '{kind}' response will fail")
            else:
                print(f"[sim] unknown command: {line}")
    except (KeyboardInterrupt, EOFError):
        pass
    finally:
        sim.go_offline()
        time.sleep(0.3)
        sim.client.loop_stop()
        sim.client.disconnect()


if __name__ == "__main__":
    sys.exit(main())
