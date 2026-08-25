"""Campaign section: Settings (PIN gate, diagnostics, broker form, save/reconnect).

Never changes the actual broker values — the save case re-saves the current ones
(password left blank keeps the provisioned credential).

    python tools/test_campaign/settings_section.py
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
c = Campaign("settings")

PIN = "079545"
DEVICE_ID = "scanner_5c64df8d86a8"


def open_settings():
    d.relaunch(wait=4)
    expect(d.find(id="etUsername") is not None, "login screen did not appear")
    d.tap(id="btnSettings")
    expect(d.find(id="etPin", retries=6) is not None, "settings PIN gate did not appear")


def enter_pin(pin: str):
    d.type_into("etPin", pin)
    d.key("KEYCODE_BACK")
    d.tap(id="btnUnlock")
    time.sleep(0.8)


def main():
    with SimControl() as sim:
        with c.case("S1", "Wrong PIN shows the attempts-left error and keeps the gate") as case:
            open_settings()
            enter_pin("000000")
            error = d.find(id="tvPinError", retries=5)
            expect(error is not None and "attempt" in error.text.lower(), f"error {error}")
            expect(d.find(id="etBrokerHost", retries=1) is None, "settings fields leaked past the gate")
            case.note(f"error: {error.text!r}")
            case.shot(d.screenshot("S1_wrong_pin"))

        with c.case("S2", "Five wrong PINs trigger the 30s lockout") as case:
            for _ in range(4):  # S1 already burned one attempt
                enter_pin("111111")
            lockout = d.find(id="tvPinLockout", retries=5)
            expect(lockout is not None and "try again" in lockout.text.lower(), f"lockout {lockout}")
            case.note(f"lockout: {lockout.text!r}")
            # correct PIN during lockout must NOT unlock
            enter_pin(PIN)
            expect(d.find(id="etBrokerHost", retries=1) is None, "correct PIN bypassed the lockout")

        with c.case("S3", "Correct PIN reveals the broker form prefilled with current settings") as case:
            open_settings()  # fresh activity clears the in-memory lockout
            enter_pin(PIN)
            host = d.find(id="etBrokerHost", retries=6)
            expect(host is not None, "broker form did not appear")
            expect(host.text == "mqtt.sysone.co.za", f"host prefill {host.text!r}")
            port = d.find(id="etBrokerPort")
            expect(port is not None and port.text == "443", f"port prefill {port and port.text!r}")
            ws = d.scroll_to("swBrokerWebSocket")
            tls = d.scroll_to("swBrokerTls")
            expect(ws is not None and tls is not None, "transport switches missing")
            user = d.scroll_to("etBrokerUsername")
            expect(user is not None and user.text, "username prefill empty")
            pw = d.scroll_to("etBrokerPassword")
            # An empty field reports its hint; masked dots would mean the credential is echoed.
            expect(pw is not None and (pw.text == "Password" or not pw.text.strip("•·* ")),
                   f"password field must not echo the credential: {pw and pw.text!r}")
            case.shot(d.screenshot("S3_broker_form"))

        with c.case("S4", "Invalid port blocks the save locally") as case:
            port_field = d.scroll_to("etBrokerPort")
            expect(port_field is not None, "port field not reachable")
            d.type_into("etBrokerPort", "0")
            d.key("KEYCODE_BACK")
            save = d.scroll_to("btnSaveSettings")
            expect(save is not None, "save button not reachable")
            d.tap(xy=save.center)
            time.sleep(2)
            expect(d.scroll_to("etBrokerHost") is not None,
                   "app restarted despite invalid port")
            port_field = d.scroll_to("etBrokerPort")
            expect(port_field is not None, "port field not reachable after failed save")
            d.type_into("etBrokerPort", "443")  # restore
            d.key("KEYCODE_BACK")

        with c.case("S5", "Diagnostics show the derived device id and app version") as case:
            dev = d.scroll_to("tvDeviceId")
            expect(dev is not None and dev.text == DEVICE_ID, f"device id {dev and dev.text!r}")
            ver = d.scroll_to("tvVersion")
            expect(ver is not None and ver.text.startswith("v"), f"version {ver and ver.text!r}")
            case.note(f"deviceId={dev.text} version={ver.text}")

        with c.case("S6", "Diagnostics pills: broker Connected, station Online") as case:
            d.scroll_to("pillBroker")
            broker = d.inner_text("pillBroker")
            station = d.inner_text("pillStation")
            expect(broker == "Connected", f"broker pill {broker!r}")
            expect(station == "Online", f"station pill {station!r}")
            case.shot(d.screenshot("S6_pills"))

        with c.case("S7", "Station offline flips the station pill without blaming the broker") as case:
            sim.cmd("station", state="offline")
            deadline = time.time() + 20
            station_text = ""
            while time.time() < deadline:
                station_text = d.inner_text("pillStation", retries=1)
                if station_text == "Offline":
                    break
                time.sleep(1)
            broker = d.inner_text("pillBroker")
            sim.cmd("station", state="online")
            expect(station_text == "Offline", f"station pill {station_text!r}")
            expect(broker == "Connected", f"broker pill wrongly changed: {broker!r}")
            case.shot(d.screenshot("S7_station_offline"))

        with c.case("S8", "Save with unchanged values restarts and reconnects (blank password kept)") as case:
            base = len(sim.events())
            save = d.scroll_to("btnSaveSettings")
            expect(save is not None, "save button not reachable")
            d.tap(xy=save.center)
            time.sleep(6)
            # app restarted to MainActivity-or-login; device presence must come back online
            presence = sim.wait_for(
                lambda e: e["dir"] == "presence" and e["topic"].endswith(DEVICE_ID)
                and e["payload"] == "online", since=base, timeout=25)
            expect(presence is not None, "scanner did not republish online presence after save")
            expect(d.find(id="etUsername", retries=10) is not None,
                   "app did not land back on the login screen")
            deadline = time.time() + 15
            pill_text = ""
            while time.time() < deadline and pill_text != "Connected":
                pill_text = d.inner_text("connectionPill", retries=1)
                time.sleep(1)
            expect(pill_text == "Connected", f"connection pill {pill_text!r}")
            case.shot(d.screenshot("S8_after_save"))

    return c.finish()


if __name__ == "__main__":
    sys.exit(main())
