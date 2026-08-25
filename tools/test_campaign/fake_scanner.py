"""Protocol conformance check: acts as a scanner against the live simulator.

Runs the full contract v3.1.0 flow over the real broker — SCRAM login, tag_scan,
offload scan/confirm/complete, logout — and asserts every response. Validates the
simulator's MQTT wiring end-to-end before the Android app is tested against it.

    python tools/test_campaign/fake_scanner.py
"""
from __future__ import annotations

import json
import queue
import secrets
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import paho.mqtt.client as mqtt

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tests"))
from scram_client import client_proof  # noqa: E402

DEVICE = "scanner_test00000000"
BASE = "PPNAM/station_1"


def iso6() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f") + "Z"


def iso3() -> str:
    now = datetime.now(timezone.utc)
    return now.strftime("%Y-%m-%dT%H:%M:%S.") + f"{now.microsecond // 1000:03d}Z"


class FakeScanner:
    def __init__(self):
        self.responses: queue.Queue = queue.Queue()
        self.connected = threading.Event()
        self.client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"FakeScanner_{uuid.uuid4().hex[:8]}",
            transport="websockets",
            protocol=mqtt.MQTTv311,
        )
        self.client.username_pw_set("admin", "admin")
        self.client.tls_set()
        self.client.will_set(f"{BASE}/{DEVICE}", "offline", qos=2, retain=True)
        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message

    def _on_connect(self, client, userdata, flags, reason_code, properties=None):
        client.subscribe(f"{BASE}/{DEVICE}/res/+", qos=1)
        client.publish(f"{BASE}/{DEVICE}", "online", qos=2, retain=True)
        self.connected.set()

    def _on_message(self, client, userdata, msg):
        suffix = msg.topic.split("/")[-1]
        self.responses.put((suffix, json.loads(msg.payload.decode("utf-8"))))

    def start(self):
        self.client.connect("mqtt.sysone.co.za", 443, keepalive=15)
        self.client.loop_start()
        assert self.connected.wait(10), "broker connect failed"

    def request(self, req_type: str, payload: dict, expect_suffix: str, timeout=10.0) -> dict:
        while not self.responses.empty():
            self.responses.get_nowait()
        self.client.publish(f"{BASE}/{DEVICE}/req/{req_type}", json.dumps(payload), qos=1)
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                suffix, body = self.responses.get(timeout=max(0.1, deadline - time.time()))
            except queue.Empty:
                break
            if suffix == expect_suffix:
                return body
            print(f"    (ignoring {suffix}: {body.get('errorCode')})")
        raise AssertionError(f"no {expect_suffix} for {req_type}")

    def stop(self):
        self.client.publish(f"{BASE}/{DEVICE}", "offline", qos=2, retain=True)
        time.sleep(0.3)
        self.client.loop_stop()
        self.client.disconnect()


def auth_payload(**fields) -> dict:
    return {"messageId": f"fs-{uuid.uuid4().hex[:10]}", "schemaVersion": "4.1",
            "deviceId": DEVICE, "timestampUtc": iso6(), **fields}


def wf_payload(session: str, **fields) -> dict:
    return {"ts": iso3(), "deviceId": DEVICE, "operatorSessionId": session, **fields}


CHECKS = []


def check(name: str, condition: bool, detail=""):
    CHECKS.append((name, condition))
    print(f"  {'PASS' if condition else 'FAIL'}  {name}" + (f" — {detail}" if detail and not condition else ""))


def main():
    scanner = FakeScanner()
    scanner.start()
    print("[fake-scanner] connected")

    # ---- SCRAM login -------------------------------------------------
    nonce = secrets.token_urlsafe(12)
    ch = scanner.request("scram_start_requested",
                         auth_payload(username="op.both", clientNonce=nonce, purpose="login"),
                         "scram_challenge")
    check("scram_challenge accepted", ch.get("accepted") is True, ch)
    check("nextAction submit_scram_proof", ch.get("nextAction") == "submit_scram_proof")

    cfwp, proof, expected_sig = client_proof("both123!", "op.both", nonce, ch["serverFirstMessage"])
    pr = scanner.request("scram_proof_requested",
                         auth_payload(challengeId=ch["challengeId"], clientFinalWithoutProof=cfwp,
                                      clientProof=proof, purpose="login"),
                         "scram_proof_result")
    check("proof accepted", pr.get("accepted") is True, pr)
    check("serverSignature valid", pr.get("serverSignature") == expected_sig)
    check("allowedTabs both", pr.get("allowedTabs") == ["tag_assignment", "offload"])
    check("nextAction workflow_selection", pr.get("nextAction") == "workflow_selection")
    session = pr["operatorSessionId"]
    check("session issued", bool(session))

    # ---- wrong password rejected ------------------------------------
    nonce2 = secrets.token_urlsafe(12)
    ch2 = scanner.request("scram_start_requested",
                          auth_payload(username="op.both", clientNonce=nonce2, purpose="login"),
                          "scram_challenge")
    cfwp2, proof2, _ = client_proof("wrong!", "op.both", nonce2, ch2["serverFirstMessage"])
    bad = scanner.request("scram_proof_requested",
                          auth_payload(challengeId=ch2["challengeId"], clientFinalWithoutProof=cfwp2,
                                       clientProof=proof2, purpose="login"),
                          "scram_proof_result")
    check("wrong password rejected", bad.get("accepted") is False
          and bad.get("errorCode") == "scram_proof_invalid", bad)

    # ---- envelope rejection ------------------------------------------
    stale = auth_payload(username="op.both", clientNonce="n", purpose="login")
    stale["timestampUtc"] = "2020-01-01T00:00:00.000000Z"
    rej = scanner.request("scram_start_requested", stale, "request_rejected")
    check("stale timestamp -> request_rejected timestamp_stale",
          rej.get("errorCode") == "timestamp_stale", rej)

    # ---- tag assignment ----------------------------------------------
    tag = scanner.request("tag_scan", wf_payload(session, tagId="TAG-OK-e2e"), "tag_scan_result")
    check("tag_scan accepted, echoes tagId",
          tag.get("accepted") is True and tag.get("tagId") == "TAG-OK-e2e", tag)
    used = scanner.request("tag_scan", wf_payload(session, tagId="TAG-USED-001"), "tag_scan_result")
    check("held tag -> TAG_ALREADY_IN_USE", used.get("errorCode") == "TAG_ALREADY_IN_USE", used)

    # ---- offload ------------------------------------------------------
    scan = scanner.request("offload_scan",
                           wf_payload(session, tagId="TAG-PAL-001", barcode="BC-001"),
                           "offload_scan_result")
    check("offload_scan matched with prefill+document",
          scan.get("matched") is True and scan.get("bagWeight") == 25.0
          and scan.get("documentNumber") == "PO-000123"
          and scan.get("palletsScanned") == 5 and scan.get("palletsExpected") == 12, scan)

    confirm = scanner.request("offload_confirm",
                              wf_payload(session, documentType=scan["documentType"],
                                         documentNumber=scan["documentNumber"],
                                         tagId="TAG-PAL-001", barcode="BC-001",
                                         bagWeight=24.5, bagCount=40, batchReference="BATCH-A"),
                              "offload_confirm_result")
    check("offload_confirm accepted with post-commit progress",
          confirm.get("accepted") is True and confirm.get("palletsScanned") == 6, confirm)

    done = scanner.request("offload_complete",
                           wf_payload(session, documentType="purchase_order",
                                      documentNumber="PO-000123", status="complete"),
                           "offload_complete_result")
    check("offload_complete accepted, echoes status",
          done.get("accepted") is True and done.get("status") == "complete", done)

    closed = scanner.request("offload_scan",
                             wf_payload(session, tagId="TAG-PAL-002", barcode="BC-002"),
                             "offload_scan_result")
    check("scan after completion -> DOCUMENT_UNKNOWN",
          closed.get("errorCode") == "DOCUMENT_UNKNOWN", closed)

    # ---- logout -------------------------------------------------------
    out = scanner.request("reader_logout_requested",
                          auth_payload(operatorSessionId=session), "operator_context")
    check("logout closes session",
          out.get("accepted") is True and out.get("sessionState") == "Closed", out)
    dead = scanner.request("tag_scan", wf_payload(session, tagId="TAG-OK-x"), "tag_scan_result")
    check("workflow after logout -> OPERATOR_SESSION_INVALID",
          dead.get("errorCode") == "OPERATOR_SESSION_INVALID", dead)

    scanner.stop()
    failed = [n for n, ok in CHECKS if not ok]
    print(f"\n[fake-scanner] {len(CHECKS) - len(failed)}/{len(CHECKS)} checks passed")
    if failed:
        print("FAILED:", ", ".join(failed))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
