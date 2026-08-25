"""Campaign section: Offload (contract §6 — scan, prefill, confirm, completion).

    python tools/test_campaign/offload_section.py
"""
from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from campaign import Campaign, expect
from device import Device
from simctl import SimControl

d = Device()
c = Campaign("offload")


def fresh_offload(sim):
    """Reset world, relaunch, login as op.both, open Offload on the scan step."""
    sim.cmd("reset")
    d.relaunch(wait=4)
    expect(d.find(id="etUsername") is not None, "login screen did not appear")
    d.type_into("etUsername", "op.both")
    d.type_into("etPassword", "both123!")
    d.key("KEYCODE_BACK")
    d.tap(id="btnLogin")
    expect(d.find(id="tileOffload", retries=8) is not None, "login failed")
    d.tap(id="tileOffload")
    expect(d.find(id="etTag", retries=6) is not None, "Offload did not open")


def scan_pair(tag: str, barcode: str):
    d.scan_rfid(tag)
    d.scan_barcode(barcode)
    d.tap(id="btnMatchPallet")


def wait_text(node_id: str, want: str, timeout=12.0) -> str:
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        node = d.find(id=node_id, retries=1)
        last = node.text if node else ""
        if want.lower() in last.lower():
            return last
        time.sleep(0.5)
    return last


def on_edit_step() -> bool:
    return d.find(id="etBagWeight", retries=6) is not None


def tap_btn(node_id: str):
    """Taps a button, scrolling it into view if the card pushed it off-screen."""
    node = d.scroll_to(node_id)
    expect(node is not None, f"{node_id} not found even after scrolling")
    d.tap(xy=node.center)


def field_text(node_id: str) -> str:
    node = d.find(id=node_id)
    return node.text if node else ""


def main():
    with SimControl() as sim:
        # ------------------------------------------------------------ O1
        with c.case("O1", "Matched scan prefills values + document progress on the edit step") as case:
            fresh_offload(sim)
            base = len(sim.events())
            scan_pair("TAG-PAL-001", "BC-001")
            expect(on_edit_step(), "edit step did not appear")
            weight = d.wait_field("etBagWeight", lambda t: t in ("25", "25.0"))
            expect(weight in ("25", "25.0"), f"weight {weight!r}")
            count = d.wait_field("etBagCount", lambda t: t == "40")
            expect(count == "40", f"count {count!r}")
            batch = d.wait_field("etBatchRef", lambda t: t == "BATCH-A")
            expect(batch == "BATCH-A", f"batch {batch!r}")
            doc = field_text("tvDocumentInfo")
            expect("PO-000123" in doc and "5 of 12" in doc, f"document info {doc!r}")
            seen = sim.wait_for(lambda e: e["dir"] == "in" and e["topic"].endswith("req/offload_scan"),
                                since=base)
            expect(seen and "documentNumber" not in seen["payload"],
                   "offload_scan must not carry document fields")
            case.shot(d.screenshot("O1_edit_step"))

        # ------------------------------------------------------------ O2-O5
        for case_id, tag, barcode, want in (
            ("O2", "TAG-PAL-001", "BC-002", "mismatch"),            # PAIR_MISMATCH reason
            ("O3", "TAG-PAL-001", "BC-NOPE", "Barcode not found"),
            ("O4", "TAG-PAL-OFF", "BC-OFF", "already offloaded"),
            ("O5", "TAG-PAL-NODOC", "BC-NODOC", "document"),        # DOCUMENT_UNKNOWN reason
        ):
            with c.case(case_id, f"Scan rejection {tag}+{barcode} returns to scanning") as case:
                fresh_offload(sim)
                scan_pair(tag, barcode)
                shown = wait_text("tvScanStatus", want)
                expect(want.lower() in shown.lower(), f"status {shown!r}")
                expect(d.find(id="etBagWeight", retries=1) is None, "edit step appeared on a rejection")
                tag_field = d.find(id="etTag")
                expect(tag_field is not None and tag_field.enabled, "scan fields not re-enabled")
                case.note(f"status: {shown!r}")

        # ------------------------------------------------------------ O6
        with c.case("O6", "Confirm with an edited weight; done prompt shows 6 of 12; Next Pallet resumes") as case:
            fresh_offload(sim)
            base = len(sim.events())
            scan_pair("TAG-PAL-001", "BC-001")
            expect(on_edit_step(), "edit step did not appear")
            d.type_into("etBagWeight", "24.5")
            d.key("KEYCODE_BACK")
            tap_btn("btnConfirmOffload")
            prompt = d.find(text="Pallet recorded", retries=10)
            expect(prompt is not None, "done prompt did not appear")
            body = next((n for n in d.ui() if "6 of 12" in n.text), None)
            expect(body is not None, "done prompt lacks 6-of-12 progress")
            case.shot(d.screenshot("O6_done_prompt"))
            d.tap(text="Next Pallet")
            confirm_seen = sim.wait_for(
                lambda e: e["dir"] == "in" and e["topic"].endswith("req/offload_confirm"), since=base)
            expect(confirm_seen is not None, "sim never saw offload_confirm")
            payload = confirm_seen["payload"]
            expect(payload.get("bagWeight") == 24.5, f"bagWeight sent {payload.get('bagWeight')}")
            expect(payload.get("documentType") == "purchase_order"
                   and payload.get("documentNumber") == "PO-000123",
                   "confirm did not repeat the document reference verbatim")
            tag_field = d.find(id="etTag")
            # Cleared field shows its hint text, never the previous tag.
            expect(tag_field is not None and tag_field.text != "TAG-PAL-001",
                   f"scan fields not cleared: {tag_field}")

        # ------------------------------------------------------------ O7
        with c.case("O7", "Client-side validation blocks a zero weight locally") as case:
            fresh_offload(sim)
            scan_pair("TAG-PAL-001", "BC-001")
            expect(on_edit_step(), "edit step did not appear")
            base = len(sim.events())
            d.type_into("etBagWeight", "0")
            d.key("KEYCODE_BACK")
            tap_btn("btnConfirmOffload")
            shown = wait_text("tvConfirmStatus", "greater than 0", timeout=6)
            expect("greater than 0" in shown, f"status {shown!r}")
            time.sleep(1)
            sent = [e for e in sim.events(since=base)
                    if e["dir"] == "in" and e["topic"].endswith("req/offload_confirm")]
            expect(not sent, "an invalid confirm was still sent to the station")

        # ------------------------------------------------------------ O8
        with c.case("O8", "Server-side INVALID_BAG_WEIGHT keeps the operator on the edit step") as case:
            sim.cmd("fail-next", kind="offload_confirm", code="INVALID_BAG_WEIGHT")
            d.type_into("etBagWeight", "24.5")
            d.key("KEYCODE_BACK")
            tap_btn("btnConfirmOffload")
            shown = wait_text("tvConfirmStatus", "weight")
            expect(shown.strip(), "no rejection shown")
            expect(on_edit_step(), "left the edit step on a value rejection")
            confirm_btn = d.find(id="btnConfirmOffload")
            expect(confirm_btn is not None and confirm_btn.enabled, "confirm not re-enabled")
            case.note(f"status: {shown!r}")

        # ------------------------------------------------------------ O9
        with c.case("O9", "Confirm timeout stays on edit; retry succeeds") as case:
            sim.cmd("swallow-next", kind="offload_confirm")
            tap_btn("btnConfirmOffload")
            shown = wait_text("tvConfirmStatus", "no response", timeout=14)
            expect("no response" in shown.lower(), f"status {shown!r}")
            expect(on_edit_step(), "left the edit step on timeout")
            tap_btn("btnConfirmOffload")
            prompt = d.find(text="Pallet recorded", retries=10)
            expect(prompt is not None, "retry after timeout did not succeed")
            d.tap(text="Next Pallet")

        # ------------------------------------------------------------ O10
        with c.case("O10", "Done -> Complete closes the document; its pallets stop resolving") as case:
            fresh_offload(sim)
            base = len(sim.events())
            scan_pair("TAG-PAL-001", "BC-001")
            expect(on_edit_step(), "edit step did not appear")
            tap_btn("btnConfirmOffload")
            expect(d.find(text="Pallet recorded", retries=10) is not None, "no done prompt")
            d.tap(text="Done")
            expect(d.find(text="Close PO-000123 asâ€¦", retries=6) is not None, "no close prompt")
            case.shot(d.screenshot("O10_close_prompt"))
            d.tap(text="Complete")
            shown = wait_text("tvScanStatus", "closed")
            expect("PO-000123" in shown and "Complete" in shown, f"status {shown!r}")
            done = sim.wait_for(
                lambda e: e["dir"] == "in" and e["topic"].endswith("req/offload_complete"), since=base)
            expect(done and done["payload"].get("status") == "complete",
                   f"completion payload {done and done['payload']}")
            # pallets of the closed document no longer resolve
            scan_pair("TAG-PAL-002", "BC-002")
            shown = wait_text("tvScanStatus", "document")
            expect("document" in shown.lower(), f"status {shown!r}")
            case.note(f"post-close scan status: {shown!r}")

        # ------------------------------------------------------------ O11
        with c.case("O11", "Short and Over classifications are both accepted") as case:
            for status_label, wire in (("Short", "short"), ("Over", "over")):
                fresh_offload(sim)
                base = len(sim.events())
                scan_pair("TAG-PAL-003", "BC-003")
                expect(on_edit_step(), "edit step did not appear")
                tap_btn("btnConfirmOffload")
                expect(d.find(text="Pallet recorded", retries=10) is not None, "no done prompt")
                d.tap(text="Done")
                expect(d.find(text="Close ST-000077 asâ€¦", retries=6) is not None, "no close prompt")
                d.tap(text=status_label)
                shown = wait_text("tvScanStatus", "closed")
                expect("ST-000077" in shown, f"status {shown!r}")
                done = sim.wait_for(
                    lambda e: e["dir"] == "in" and e["topic"].endswith("req/offload_complete"),
                    since=base)
                expect(done and done["payload"].get("status") == wire,
                       f"completion payload {done and done['payload']}")
                case.note(f"{status_label}: {shown!r}")

        # ------------------------------------------------------------ O12
        with c.case("O12", "Failed completion re-offers the close prompt; retry closes") as case:
            fresh_offload(sim)
            scan_pair("TAG-PAL-003", "BC-003")
            expect(on_edit_step(), "edit step did not appear")
            tap_btn("btnConfirmOffload")
            expect(d.find(text="Pallet recorded", retries=10) is not None, "no done prompt")
            sim.cmd("fail-next", kind="offload_complete", code="INTERNAL_ERROR")
            d.tap(text="Done")
            expect(d.find(text="Close ST-000077 asâ€¦", retries=6) is not None, "no close prompt")
            d.tap(text="Complete")
            reprompt = d.find(text="Close ST-000077 asâ€¦", retries=10)
            expect(reprompt is not None, "close prompt not re-offered after failure")
            case.shot(d.screenshot("O12_reprompt"))
            d.tap(text="Complete")
            shown = wait_text("tvScanStatus", "closed")
            expect("ST-000077" in shown, f"status {shown!r}")

        # ------------------------------------------------------------ O13
        with c.case("O13", "Back to scan from the edit step keeps the scanned pair") as case:
            fresh_offload(sim)
            scan_pair("TAG-PAL-001", "BC-001")
            expect(on_edit_step(), "edit step did not appear")
            tap_btn("btnBackToScan")
            expect(d.find(id="etBagWeight", retries=1) is None, "edit card still visible")
            expect(field_text("etTag") == "TAG-PAL-001", f"tag lost: {field_text('etTag')!r}")
            expect(field_text("etBarcode") == "BC-001", f"barcode lost: {field_text('etBarcode')!r}")

    return c.finish()


if __name__ == "__main__":
    sys.exit(main())
