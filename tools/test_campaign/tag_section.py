"""Campaign section: Tag Assignment (contract §5).

    python tools/test_campaign/tag_section.py
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
c = Campaign("tag_assignment")


def fresh_session(sim):
    sim.cmd("reset")
    d.relaunch(wait=4)
    expect(d.find(id="etUsername") is not None, "login screen did not appear")
    d.type_into("etUsername", "op.both")
    d.type_into("etPassword", "both123!")
    d.key("KEYCODE_BACK")
    d.tap(id="btnLogin")
    expect(d.find(id="tileTagAssignment", retries=8) is not None, "login failed")
    d.tap(id="tileTagAssignment")
    expect(d.find(id="tvLastTag", retries=6) is not None, "Tag Assignment did not open")


def status_text(retries=8) -> str:
    node = d.find(id="tvSendStatus", retries=retries)
    return node.text if node else ""


def wait_status(want_substring: str, timeout=12.0) -> str:
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        last = status_text(retries=1)
        if want_substring.lower() in last.lower():
            return last
        time.sleep(0.5)
    return last


def main():
    with SimControl() as sim:
        with c.case("T1", "Accepted tag scan shows success and reaches the station") as case:
            fresh_session(sim)
            base = len(sim.events())
            d.scan_rfid("TAG-OK-100")
            shown = wait_status("assigned")
            expect("assigned" in shown.lower(), f"status was {shown!r}")
            last_tag = d.find(id="tvLastTag")
            expect(last_tag and last_tag.text == "TAG-OK-100", f"tvLastTag {last_tag}")
            seen = sim.wait_for(lambda e: e["dir"] == "in" and e["topic"].endswith("req/tag_scan")
                                and e["payload"].get("tagId") == "TAG-OK-100", since=base)
            expect(seen is not None, "sim never saw the tag_scan")
            expect(seen["payload"].get("operatorSessionId"), "request carried no operatorSessionId")
            case.shot(d.screenshot("T1_accepted"))

        with c.case("T2", "Tag held by another scanner surfaces TAG_ALREADY_IN_USE") as case:
            d.scan_rfid("TAG-USED-001")
            shown = wait_status("in use")
            expect("in use" in shown.lower(), f"status was {shown!r}")
            case.note(f"status: {shown!r}")

        with c.case("T3", "Unknown tag surfaces TAG_UNKNOWN") as case:
            d.scan_rfid("JUNK-1")
            shown = wait_status("unknown")
            expect("unknown" in shown.lower(), f"status was {shown!r}")
            case.note(f"status: {shown!r}")

        with c.case("T4", "Re-scanning an own tag is an idempotent success") as case:
            d.scan_rfid("TAG-OK-100")
            shown = wait_status("assigned")
            expect("assigned" in shown.lower(), f"status was {shown!r}")
            case.note(f"status: {shown!r}")

        with c.case("T5", "Station silence surfaces the 10s timeout, next scan recovers") as case:
            sim.cmd("swallow-next", kind="tag_scan")
            d.scan_rfid("TAG-OK-101")
            shown = wait_status("respond", timeout=14)
            expect(shown.strip(), "no timeout status shown")
            case.note(f"timeout status: {shown!r}")
            case.shot(d.screenshot("T5_timeout"))
            d.scan_rfid("TAG-OK-101")
            shown = wait_status("assigned")
            expect("assigned" in shown.lower(), f"recovery status was {shown!r}")

        with c.case("T6", "Forced INTERNAL_ERROR is surfaced as a station error") as case:
            sim.cmd("fail-next", kind="tag_scan", code="INTERNAL_ERROR")
            d.scan_rfid("TAG-OK-102")
            shown = wait_status("error")
            expect("error" in shown.lower(), f"status was {shown!r}")
            case.note(f"status: {shown!r}")

        with c.case("T7", "Expired session sends the operator back to login") as case:
            sim.cmd("expire-sessions")
            d.scan_rfid("TAG-OK-103")
            expect(d.find(id="etUsername", retries=10) is not None,
                   "app did not return to the login screen")
            case.shot(d.screenshot("T7_session_expiry"))

    return c.finish()


if __name__ == "__main__":
    sys.exit(main())
