"""Campaign section: presence and reconnect (contract §8).

    python tools/test_campaign/presence_section.py
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
c = Campaign("presence")

DEVICE_ID = "scanner_5c64df8d86a8"


def login_to_main(sim):
    sim.cmd("reset")
    d.relaunch(wait=4)
    expect(d.find(id="etUsername") is not None, "login screen did not appear")
    d.type_into("etUsername", "op.both")
    d.type_into("etPassword", "both123!")
    d.key("KEYCODE_BACK")
    d.tap(id="btnLogin")
    expect(d.find(id="tileTagAssignment", retries=8) is not None, "login failed")


def pill_text() -> str:
    node = d.find(id="connectionPill", retries=1)
    return node.text if node else ""


def wait_pill(want: str, timeout=30) -> str:
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        last = pill_text()
        if last == want:
            return last
        time.sleep(1)
    return last


def main():
    with SimControl() as sim:
        with c.case("P1", "Station offline shows the blocking overlay on Main; online clears it") as case:
            login_to_main(sim)
            sim.cmd("station", state="offline")
            overlay = None
            deadline = time.time() + 15
            while time.time() < deadline:
                overlay = d.find(id="layoutStationOffline", retries=1)
                if overlay is not None:
                    break
                time.sleep(1)
            expect(overlay is not None, "station-offline overlay never appeared")
            case.shot(d.screenshot("P1_station_offline"))
            sim.cmd("station", state="online")
            expect(d.wait_gone("layoutStationOffline", timeout=15), "overlay did not clear")

        with c.case("P2", "Network drop fires the Last Will; reconnect republishes online presence") as case:
            base = len(sim.events())
            d.shell("svc", "wifi", "disable")
            try:
                lwt = sim.wait_for(
                    lambda e: e["dir"] == "presence" and e["topic"].endswith(DEVICE_ID)
                    and e["payload"] == "offline", since=base, timeout=60)
                expect(lwt is not None, "broker never delivered the scanner's Last Will")
                case.note("LWT offline observed")
                shown = wait_pill("Reconnecting", timeout=20) or pill_text()
                case.note(f"pill during outage: {shown!r}")
            finally:
                d.shell("svc", "wifi", "enable")
            back = sim.wait_for(
                lambda e: e["dir"] == "presence" and e["topic"].endswith(DEVICE_ID)
                and e["payload"] == "online", since=base, timeout=90)
            expect(back is not None, "scanner did not republish online after reconnect")
            shown = wait_pill("Connected", timeout=30)
            expect(shown == "Connected", f"pill after reconnect {shown!r}")
            case.shot(d.screenshot("P2_reconnected"))

        with c.case("P3", "Workflows still work after the reconnect (fresh session)") as case:
            # The broker drop may have invalidated nothing server-side — the session
            # survives; a tag scan must still round-trip.
            d.tap(id="tileTagAssignment")
            expect(d.find(id="tvLastTag", retries=6) is not None, "Tag Assignment did not open")
            base = len(sim.events())
            d.scan_rfid("TAG-OK-200")
            seen = sim.wait_for(
                lambda e: e["dir"] == "out" and "tag_scan_result" in e["topic"], since=base)
            expect(seen is not None and seen["payload"].get("accepted"),
                   f"scan after reconnect failed: {seen and seen['payload']}")
            case.shot(d.screenshot("P3_scan_after_reconnect"))

    return c.finish()


if __name__ == "__main__":
    sys.exit(main())
