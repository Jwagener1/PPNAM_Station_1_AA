"""SOP screenshot capture for the updated Station 1 app (com.mitas.ppnam.station1aa).

Drives the app on a USB-connected C72 through every screen and state the SOP
documents, using the test-campaign Device driver and the station simulator.

Prereqs: `python tools/station_sim.py --headless` running; C72 on adb; app
installed and provisioned with broker credentials.

    python screenshots/capture_sop.py

Output: screenshots/sop_images/*.png
"""
from __future__ import annotations

import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "test_campaign"))
from device import Device          # noqa: E402
from simctl import SimControl      # noqa: E402

OUT = Path(__file__).resolve().parent / "sop_images"
d = Device()

PIN = "079545"


def shot(name: str) -> None:
    OUT.mkdir(exist_ok=True)
    d.shell("screencap", "-p", "/sdcard/shot.png")
    d.adb("pull", "/sdcard/shot.png", str(OUT / f"{name}.png"))
    print(f"  [shot] {name}")


def require(condition, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def wait_pill(pill_id: str = "connectionPill", want: str = "Connected", timeout: float = 25) -> None:
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        last = d.inner_text(pill_id, retries=1)
        if last == want:
            return
        time.sleep(1)
    raise AssertionError(f"pill {pill_id} never showed {want!r}; last {last!r}")


def wait_status(node_id: str, want: str, timeout: float = 14) -> str:
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        node = d.find(id=node_id, retries=1)
        last = node.text if node else ""
        if want.lower() in last.lower():
            return last
        time.sleep(0.5)
    raise AssertionError(f"{node_id} never showed {want!r}; last {last!r}")


def find_text_contains(sub: str, retries: int = 10):
    for _ in range(retries):
        node = next((n for n in d.ui() if sub in n.text), None)
        if node is not None:
            return node
        time.sleep(1)
    return None


def login(user: str, password: str) -> None:
    d.type_into("etUsername", user)
    d.type_into("etPassword", password)
    d.key("KEYCODE_BACK")
    d.tap(id="btnLogin")
    require(d.find(id="tvOperator", retries=10) is not None, f"login as {user} failed")


def logout() -> None:
    d.tap(id="layoutOperator")
    time.sleep(0.8)
    d.tap(text="Log Out")
    require(d.find(id="etUsername", retries=8) is not None, "logout did not return to login")


def enter_pin(pin: str) -> None:
    d.type_into("etPin", pin)
    d.key("KEYCODE_BACK")
    d.tap(id="btnUnlock")
    time.sleep(0.8)


def main() -> int:
    with SimControl() as sim:
        # ───────────────────────────── A. Login screen ─────────────────────────
        print("[A] login screens")
        sim.cmd("reset")
        d.relaunch(wait=4)
        require(d.find(id="etUsername", retries=8) is not None, "login screen did not appear")
        wait_pill()
        shot("01_login_empty")

        d.type_into("etUsername", "op.both")
        d.type_into("etPassword", "both123!")
        d.key("KEYCODE_BACK")
        shot("02_login_filled")

        d.type_into("etPassword", "wrong-pass")
        d.key("KEYCODE_BACK")
        d.tap(id="btnLogin")
        error = d.find(id="tvLoginError", retries=8)
        require(error is not None and error.text.strip(), "login error not shown")
        shot("03_login_error")

        # ───────────────────────────── B. Dashboard ────────────────────────────
        print("[B] dashboard")
        d.type_into("etPassword", "both123!")
        d.key("KEYCODE_BACK")
        d.tap(id="btnLogin")
        require(d.find(id="tvOperator", retries=10) is not None, "login failed")
        wait_pill()
        shot("04_dashboard")

        d.tap(id="layoutOperator")
        require(find_text_contains("Log out?", retries=6) is not None, "logout dialog missing")
        shot("05_logout_dialog")
        d.tap(text="Cancel")
        time.sleep(0.8)

        # station-offline overlay
        sim.cmd("station", state="offline")
        require(find_text_contains("MAIN STATION OFFLINE", retries=20) is not None,
                "offline overlay did not appear")
        shot("06_station_offline")
        sim.cmd("station", state="online")
        deadline = time.time() + 25
        while time.time() < deadline and find_text_contains("MAIN STATION OFFLINE", retries=1):
            time.sleep(1)
        require(find_text_contains("MAIN STATION OFFLINE", retries=1) is None,
                "offline overlay did not clear")

        # ───────────────────────────── C. Settings ─────────────────────────────
        print("[C] settings")
        d.tap(id="btnSettings")
        require(d.find(id="etPin", retries=8) is not None, "settings PIN gate missing")
        shot("07_settings_pin_gate")

        enter_pin("000000")
        error = d.find(id="tvPinError", retries=6)
        require(error is not None and error.text.strip(), "PIN error not shown")
        shot("08_settings_pin_error")
        d.back()
        time.sleep(0.8)

        d.tap(id="btnSettings")
        require(d.find(id="etPin", retries=8) is not None, "settings PIN gate missing (2nd)")
        enter_pin(PIN)
        require(d.find(id="etBrokerHost", retries=8) is not None, "broker form did not unlock")
        shot("09_settings_broker")

        require(d.scroll_to("pillStation") is not None, "diagnostics pills not reachable")
        shot("10_settings_diagnostics")

        require(d.scroll_to("tvDeviceId") is not None, "device id row not reachable")
        shot("11_settings_device")
        d.back()
        time.sleep(1.0)

        # ───────────────────────────── D. Gated operator ───────────────────────
        print("[D] gated operator")
        logout()
        login("op.tag", "tag123!")
        wait_pill()
        off_tile = d.find(id="tileOffload")
        require(off_tile is not None and not off_tile.enabled, "offload tile not disabled for op.tag")
        shot("12_dashboard_gated")
        logout()

        # ───────────────────────────── E. Tag Assignment ───────────────────────
        print("[E] tag assignment")
        login("op.both", "both123!")
        d.tap(id="tileTagAssignment")
        require(d.find(id="tvLastTag", retries=8) is not None, "Tag Assignment did not open")
        shot("13_tag_empty")

        d.scan_rfid("TAG-OK-100")
        wait_status("tvSendStatus", "assigned")
        shot("14_tag_assigned")

        d.scan_rfid("TAG-USED-001")
        wait_status("tvSendStatus", "in use")
        shot("15_tag_in_use")

        sim.cmd("swallow-next", kind="tag_scan")
        d.scan_rfid("TAG-OK-101")
        wait_status("tvSendStatus", "no response", timeout=16)
        shot("16_tag_timeout")
        d.back()
        time.sleep(1.0)

        # ───────────────────────────── F. Offload ──────────────────────────────
        print("[F] offload")
        sim.cmd("reset")
        d.relaunch(wait=4)
        require(d.find(id="etUsername", retries=8) is not None, "login screen did not appear")
        wait_pill()
        login("op.both", "both123!")
        d.tap(id="tileOffload")
        require(d.find(id="etTag", retries=8) is not None, "Offload did not open")
        shot("17_offload_empty")

        # rejection first, while the document is still open
        d.scan_rfid("TAG-PAL-001")
        d.scan_barcode("BC-002")
        d.tap(id="btnMatchPallet")
        wait_status("tvScanStatus", "mismatch")
        shot("18_offload_mismatch")

        # good pair
        d.scan_rfid("TAG-PAL-001")
        d.scan_barcode("BC-001")
        time.sleep(0.5)
        shot("19_offload_pair_scanned")
        d.tap(id="btnMatchPallet")
        require(d.find(id="etBagWeight", retries=8) is not None, "edit step did not appear")
        d.wait_field("etBagWeight", lambda t: t in ("25", "25.0"))
        time.sleep(0.5)
        shot("20_offload_edit")

        confirm = d.scroll_to("btnConfirmOffload")
        require(confirm is not None, "confirm button not reachable")
        d.tap(xy=confirm.center)
        require(d.find(text="Pallet recorded", retries=10) is not None, "done prompt missing")
        shot("21_offload_done_prompt")

        d.tap(text="Done")
        require(find_text_contains("Close PO-000123", retries=8) is not None, "close prompt missing")
        shot("22_offload_close_prompt")

        d.tap(text="Complete")
        wait_status("tvScanStatus", "closed")
        shot("23_offload_closed")

        # ───────────────────────────── G. Wrap up ──────────────────────────────
        print("[G] wrap up")
        d.back()
        time.sleep(0.8)
        logout()

    count = len(list(OUT.glob("*.png")))
    print(f"\ndone — {count} screenshots in {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
