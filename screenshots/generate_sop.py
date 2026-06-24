"""
PPNAM SOP Generator
===================
Drives the scanner app through every screen via ADB, captures screenshots,
and assembles a complete SOP.md.

Prerequisites
-------------
1.  ADB is installed and on PATH (or set ADB below).
2.  The scanner device is connected via USB with USB debugging enabled.
3.  mock_backend.py is running in a separate terminal BEFORE this script starts:

        python mock_backend.py

4.  Python packages:  paho-mqtt  (pip install paho-mqtt)

Usage
-----
    python generate_sop.py

Output
------
    sop_images/    — PNG screenshots (one per step)
    SOP.md         — assembled SOP document

Tuning
------
If a step times out, increase STEP_TIMEOUT.
If coordinate taps miss their targets, update the COORDS dict for your device
resolution (default is 1080 × 2400, com.sysone.scanner on Chainway C72).
"""

from __future__ import annotations

import json
import re
import ssl
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

import paho.mqtt.client as mqtt

# ─────────────────────────────────── config ────────────────────────────────────
ADB = r"C:\Users\JonathanSystemOne\AppData\Local\Android\Sdk\platform-tools\adb.exe"

HERE = Path(__file__).resolve().parent
IMAGES_DIR = HERE / "sop_images"
MQTT_LOG = HERE / "mqtt_log.txt"
SCENARIO_FILE = HERE / "scenario.txt"
SOP_FILE = HERE / "SOP.md"

STEP_TIMEOUT = 12.0   # seconds to wait for a screen/popup

BROKER_HOST = "mqtt.sysone.co.za"
BROKER_PORT = 443
BROKER_USER = "admin"
BROKER_PASS = "admin"
BROKER_WS_PATH = "/mqtt"


def publish_station_status(status: str, station: int = 1) -> None:
    """Directly publish a retained station status message on the MQTT broker.
    Used to force the station-offline overlay on the scanner app for screenshot capture.
    The mock_backend normally keeps this 'online'; calling with 'offline' overrides it."""
    done = threading.Event()
    client = mqtt.Client(
        client_id=f"sopgen_{uuid.uuid4().hex[:6]}",
        transport="websockets",
        protocol=mqtt.MQTTv311,
    )
    ctx = ssl.create_default_context()
    client.tls_set_context(ctx)
    client.username_pw_set(BROKER_USER, BROKER_PASS)
    client.ws_set_options(path=BROKER_WS_PATH)

    def on_connect(c, u, f, rc):
        if rc == 0:
            c.publish(f"PPNAM/station_{station}/status", status, qos=1, retain=True)

    def on_publish(c, u, mid):
        done.set()

    client.on_connect = on_connect
    client.on_publish = on_publish
    try:
        client.connect(BROKER_HOST, BROKER_PORT)
        client.loop_start()
        done.wait(timeout=15)
    finally:
        client.loop_stop()
        try:
            client.disconnect()
        except Exception:
            pass
    time.sleep(0.5)

# ── screen coordinates (1080 × 2400, com.sysone.scanner) ─────────────────────
COORDS = {
    # launcher
    "launcher_icon":          (153, 884),
    # main dashboard
    "settings_gear":          (772, 192),
    "tile_sap":               (270, 856),
    "tile_tag":               (270, 1469),
    "tile_offload":           (810, 1469),
    # password dialog
    "password_ok":            (792, 1139),
    # SAP lookup screen
    "sap_docnum_field":       (540, 589),
    "sap_doctype_spinner":    (540, 802),
    "sap_doctype_po":         (540, 1010),
    "sap_lookup_btn":         (540, 1042),
    # product request screen
    "product_cb_1":           (144, 964),
    "product_submit":         (540, 1500),
    # tag assignment screen
    "tag_product_spinner":    (540, 773),
    "tag_product_item_2":     (540, 1230),
    "tag_submit":             (540, 1254),
    "tag_submit_after":       (540, 1197),
    "all_assigned_btn":       (540, 1494),
    "confirm_yes":            (792, 1054),
    "print_all_btn":          (540, 1029),
    # offload screen
    "offload_submit":         (540, 1556),
    "finish_session":         (540, 1751),
    "finish_yes":             (792, 1029),
    "finish_dismiss":         (540, 1054),
    # settings screen
    "settings_unassign_btn":  (540, 1600),
    "settings_reassign_btn":  (540, 1700),
    # popup positive
    "popup_positive":         (792, 1054),
}

APP_PACKAGE = "com.sysone.scanner"
PO_NUMBER = "220012017"
VENDOR_REF = "PPNAM-PO-2026"   # free-text reference shown on SAP lookup (PO only)
PASSWORD = "Mit@s_"


# ─────────────────────────────────── adb helpers ──────────────────────────────
def adb(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run([ADB, *args], capture_output=True, text=True, check=check)


def sh(cmd: str) -> str:
    return adb("shell", cmd, check=False).stdout


def tap(name_or_xy: str | tuple[int, int]) -> None:
    if isinstance(name_or_xy, str):
        xy = COORDS[name_or_xy]
    else:
        xy = name_or_xy
    sh(f"input tap {xy[0]} {xy[1]}")


def swipe(x1: int, y1: int, x2: int, y2: int, ms: int = 400) -> None:
    sh(f"input swipe {x1} {y1} {x2} {y2} {ms}")


def hide_keyboard() -> None:
    if "mInputShown=true" in sh("dumpsys input_method | grep mInputShown"):
        sh("input keyevent KEYCODE_BACK")
        time.sleep(0.4)


def type_text(s: str) -> None:
    for ch in s:
        adb("shell", "input", "text", ch, check=False)
    hide_keyboard()


def rfid_scan(tag: str) -> None:
    sh(f'am broadcast -a com.rscja.scanner.action.scanner.RFID --es data "{tag}"')


def barcode_scan(code: str) -> None:
    sh(f'am broadcast -a com.scanner.broadcast --es data "{code}"')


def gen_rfid() -> str:
    return "E28011C1A5" + uuid.uuid4().hex[:14].upper()


def select_doctype_po() -> None:
    """Open the doc-type spinner and select 'Purchase Order' using D-pad navigation.
    This avoids the hardcoded Y tap that overlaps the LOOKUP button bounds."""
    tap_id("spinnerDocType", timeout=3) or tap("sap_doctype_spinner")
    time.sleep(0.8)
    sh("input keyevent KEYCODE_DPAD_DOWN")   # highlight first item: "Purchase Order"
    time.sleep(0.3)
    sh("input keyevent KEYCODE_ENTER")        # confirm selection
    time.sleep(0.5)


def do_sap_lookup(doc_number: str = PO_NUMBER, wait_products: bool = True,
                  vendor_ref: str = VENDOR_REF) -> bool:
    """Fill-in the SAP lookup screen, tap LOOKUP, and optionally wait for the
    Product Request screen.  Returns True when the target screen is reached."""
    if not wait_for([r"etDocNumber|spinnerDocType"], timeout=8):
        return False
    tap("sap_docnum_field")
    time.sleep(0.4)
    type_text(doc_number)
    time.sleep(0.4)
    # Vendor reference field only appears for Purchase Order — fill if visible
    if tap_id("etVendorReference", timeout=2):
        time.sleep(0.2)
        type_text(vendor_ref)
        time.sleep(0.3)
    select_doctype_po()
    tap_id("btnSubmit", timeout=3) or tap("sap_lookup_btn")
    if wait_products:
        return wait_for([r"cbSelect|btnSubmitRequest|tvDocNumberDisplay"], timeout=30)
    return True


def dump_ui() -> str:
    sh("uiautomator dump /sdcard/u.xml")
    tmp = HERE / "_u.xml"
    adb("pull", "/sdcard/u.xml", str(tmp), check=False)
    sh("rm -f /sdcard/u.xml")
    try:
        return tmp.read_text(encoding="utf-8", errors="replace")
    except FileNotFoundError:
        return ""


def find_center(xml: str, pattern: str) -> tuple[int, int] | None:
    rx = re.compile(pattern, re.IGNORECASE)
    for m in re.finditer(r'<node[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*/?>',xml):
        start = m.start()
        end = xml.find(">", start) + 1
        tag = xml[start:end]
        if rx.search(tag):
            x1, y1, x2, y2 = map(int, m.groups())
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def tap_id(rid: str, timeout: float = 5.0) -> tuple[int, int] | None:
    hide_keyboard()
    end = time.time() + timeout
    while time.time() < end:
        xy = find_center(dump_ui(), re.escape(rid))
        if xy:
            tap(xy)
            return xy
        time.sleep(0.4)
    return None


def wait_for(patterns: list[str], timeout: float = STEP_TIMEOUT) -> bool:
    end = time.time() + timeout
    while time.time() < end:
        xml = dump_ui()
        for p in patterns:
            if re.search(p, xml, re.IGNORECASE):
                return True
        time.sleep(0.5)
    return False


# ─────────────────────────────────── screenshot ───────────────────────────────
def screenshot_binary(name: str, label: str) -> str:
    """Capture a screenshot via screencap-and-pull (works on all ADB versions)."""
    IMAGES_DIR.mkdir(parents=True, exist_ok=True)
    path = IMAGES_DIR / f"{name}.png"
    sh(f"screencap -p /sdcard/{name}.png")
    adb("pull", f"/sdcard/{name}.png", str(path), check=False)
    sh(f"rm -f /sdcard/{name}.png")
    print(f"  📸 {path.name}  ({label})")
    return f"sop_images/{name}.png"


cap = screenshot_binary  # use the pull-based method for reliability


# ─────────────────────────────────── scenario control ─────────────────────────
def set_scenario(s: str) -> None:
    """Tell mock_backend.py to switch its failure scenario at runtime."""
    SCENARIO_FILE.write_text(s, encoding="utf-8")
    time.sleep(0.3)  # give mock_backend a moment to read the file


def clear_scenario() -> None:
    set_scenario("success")


# ─────────────────────────────────── mqtt log helpers ─────────────────────────
def read_log_lines() -> list[str]:
    try:
        return MQTT_LOG.read_text(encoding="utf-8", errors="replace").splitlines()
    except FileNotFoundError:
        return []


def wait_assignment_barcode(rfid: str, since: int, timeout: float = 10.0) -> tuple[str | None, int]:
    end = time.time() + timeout
    rfid_u = rfid.upper()
    while time.time() < end:
        lines = read_log_lines()
        for line in lines[since:]:
            if "/assignment_result" not in line:
                continue
            parts = line.split("|", 2)
            if len(parts) < 3:
                continue
            try:
                data = json.loads(parts[2])
            except Exception:
                continue
            if rfid_u in json.dumps(data).upper():
                bc = data.get("barcode") or data.get("palletCode") or ""
                return bc or None, len(lines)
        time.sleep(0.3)
    return None, len(read_log_lines())


# ─────────────────────────────────── device prep ──────────────────────────────
def keep_awake() -> None:
    sh("svc power stayon true")
    sh("settings put system screen_off_timeout 1800000")
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")
    sh("settings put system screen_brightness 200")


SETTINGS_XML = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="mqtt_host">mqtt.sysone.co.za</string>
    <int name="mqtt_port" value="443" />
    <string name="mqtt_protocol">ws://</string>
    <boolean name="mqtt_use_ssl" value="true" />
    <boolean name="mqtt_validate_cert" value="true" />
    <string name="mqtt_username">admin</string>
    <string name="mqtt_password">admin</string>
    <int name="scanner_int" value="1" />
    <int name="station_int" value="1" />
</map>
"""


def push_settings() -> None:
    """Push correct MQTT settings back onto the device after pm clear wipes them."""
    xml_path = HERE / "_settings.xml"
    xml_path.write_text(SETTINGS_XML, encoding="utf-8")
    # Push to /data/local/tmp (world-readable), then copy into app prefs using run-as
    adb("push", str(xml_path), "/data/local/tmp/_ppnam_settings.xml", check=False)
    sh(f"run-as {APP_PACKAGE} mkdir -p /data/data/{APP_PACKAGE}/shared_prefs")
    sh(f"run-as {APP_PACKAGE} cp /data/local/tmp/_ppnam_settings.xml /data/data/{APP_PACKAGE}/shared_prefs/settings.xml")
    sh("rm -f /data/local/tmp/_ppnam_settings.xml")
    xml_path.unlink(missing_ok=True)
    time.sleep(0.5)


def reset_app() -> None:
    sh(f"am force-stop {APP_PACKAGE}")
    sh(f"pm clear {APP_PACKAGE}")
    push_settings()
    sh("input keyevent KEYCODE_HOME")
    time.sleep(1.5)


def launch_app() -> bool:
    tap("launcher_icon")
    time.sleep(4.0)
    return wait_for([r"Lookup SAP Entry", r"tile_sap", r"STEP 1"], timeout=8.0)


# ─────────────────────────────────── SOP builder ──────────────────────────────
class SopBuilder:
    def __init__(self) -> None:
        self.sections: list[str] = []

    def h1(self, text: str) -> "SopBuilder":
        self.sections.append(f"\n# {text}\n")
        return self

    def h2(self, text: str) -> "SopBuilder":
        self.sections.append(f"\n## {text}\n")
        return self

    def h3(self, text: str) -> "SopBuilder":
        self.sections.append(f"\n### {text}\n")
        return self

    def p(self, text: str) -> "SopBuilder":
        self.sections.append(f"\n{text}\n")
        return self

    def img(self, path: str, alt: str) -> "SopBuilder":
        self.sections.append(
            f'\n<div align="center">'
            f'<img src="{path}" width="260" alt="{alt}" '
            f'style="border-radius:20px; box-shadow:0 4px 16px rgba(0,0,0,0.25);"/>'
            f'</div>\n'
            f'\n<p align="center"><em>{alt}</em></p>\n'
        )
        return self

    def note(self, text: str) -> "SopBuilder":
        self.sections.append(f"\n> **Note:** {text}\n")
        return self

    def warn(self, text: str) -> "SopBuilder":
        self.sections.append(f"\n> **⚠ Warning:** {text}\n")
        return self

    def bullet(self, *items: str) -> "SopBuilder":
        lines = "\n".join(f"- {i}" for i in items)
        self.sections.append(f"\n{lines}\n")
        return self

    def save(self, path: Path) -> None:
        path.write_text("".join(self.sections), encoding="utf-8")
        print(f"\n✅  SOP written to {path}")


sop = SopBuilder()


# ─────────────────────────────────── walkthrough ──────────────────────────────
def run() -> None:
    print("=== PPNAM SOP Generator ===")
    clear_scenario()
    keep_awake()
    push_settings()   # ensure correct broker settings before first launch

    # ── header ─────────────────────────────────────────────────────────────────
    sop.h1("PPNAM Station 1 — Scanner App Standard Operating Procedure")
    sop.p(
        "**Document:** PPNAM-SOP-001  \n"
        "**System:** PPNAM Station 1 AA (Android Scanner App)  \n"
        "**Applies to:** Chainway C72 / compatible RFID scanner running `com.sysone.scanner`"
    )

    sop.h2("Table of Contents")
    sop.p(
        "1. [Overview](#1-overview)  \n"
        "2. [Prerequisites](#2-prerequisites)  \n"
        "3. [App Launch & Dashboard](#3-app-launch--dashboard)  \n"
        "4. [Settings Configuration](#4-settings-configuration)  \n"
        "5. [Step 1 – SAP Document Lookup](#5-step-1--sap-document-lookup)  \n"
        "6. [Step 2 – Product Selection](#6-step-2--product-selection)  \n"
        "7. [Step 3 – RFID Tag Assignment](#7-step-3--rfid-tag-assignment)  \n"
        "8. [Step 4 – Pallet Offloading](#8-step-4--pallet-offloading)  \n"
        "9. [Completing a Session](#9-completing-a-session)  \n"
        "10. [Error Messages & Recovery](#10-error-messages--recovery)  \n"
        "11. [Unassign Mode](#11-unassign-mode)  \n"
        "12. [Reassign Mode](#12-reassign-mode)  "
    )

    sop.h2("1. Overview")
    sop.p(
        "The PPNAM Station 1 Scanner App is used by goods-receiving operators to register "
        "incoming pallets against SAP Purchase Orders or Stock Transfer Requests. "
        "The workflow is: **SAP Lookup → Product Selection → RFID Tag Assignment → Offloading**."
    )

    sop.h2("2. Prerequisites")
    sop.bullet(
        "Scanner device is charged and powered on.",
        "Wi-Fi is connected to the site network.",
        "Station 1 PC is running with MQTT broker online.",
        "App is installed: `com.sysone.scanner`.",
        "Settings are configured (MQTT host, scanner ID, station ID — see Section 4).",
    )

    # ────────────────────────── PHASE 0: STATION OFFLINE ─────────────────────
    print("\n─── Phase 0: Station offline overlay ───")
    publish_station_status("offline")
    time.sleep(1.0)
    reset_app()
    if launch_app():
        time.sleep(1.5)
        p_offline = cap("36_station_offline", "Dashboard — station offline overlay")
    else:
        p_offline = None
        print("!! Could not launch app for offline overlay test")
    publish_station_status("online")
    time.sleep(2.0)
    print("  ✓ Station status restored to online")

    # ────────────────────────── PHASE 1: HAPPY PATH ──────────────────────────
    print("\n─── Phase 1: Happy path ───")
    reset_app()

    # 3. Dashboard ──────────────────────────────────────────────────────────────
    sop.h2("3. App Launch & Dashboard")
    sop.p("Tap the **PPNAM Scanner** icon on the home screen to launch the app.")
    if not launch_app():
        print("!! App did not load — check ADB connection and mock_backend")
        sys.exit(1)

    time.sleep(1.0)
    p_dash_idle = cap("01_main_dashboard_idle", "Main dashboard — no active session")
    sop.img(p_dash_idle, "Main dashboard — no active session")
    sop.p(
        "The dashboard shows four workflow tiles. When no session is active the tag "
        "assignment and offload tiles are greyed out. The tiles become enabled as you "
        "progress through the workflow."
    )
    sop.bullet(
        "**Lookup SAP Entry** — start a new receiving session (Step 1).",
        "**Tag Assignment** — assign RFID tags to pallets (Step 3, enabled after product selection).",
        "**Offload** — scan pallets off the truck (Step 4, enabled after tag assignment).",
        "**Settings (⚙)** — configure MQTT, scanner, and station settings.",
    )

    sop.h3("3.1 Station Offline State")
    sop.p(
        "If Station 1 is not running or the network is unavailable, the dashboard shows a "
        "full-screen offline overlay blocking all workflow tiles:"
    )
    if p_offline:
        sop.img(p_offline, "Dashboard — station offline overlay")
    sop.p(
        "The overlay clears automatically once Station 1 comes back online and the MQTT "
        "connection is restored. Check that the Station 1 PC is running and connected, "
        "or verify MQTT/Wi-Fi settings (Section 4)."
    )

    # 4. Settings ───────────────────────────────────────────────────────────────
    sop.h2("4. Settings Configuration")
    sop.p(
        "Tap the **⚙ gear icon** (top-right of the dashboard). "
        "Enter the supervisor password when prompted."
    )

    tap("settings_gear")
    time.sleep(1.0)
    p_pwd = cap("02_settings_password_prompt", "Password prompt")
    sop.img(p_pwd, "Settings — password prompt")
    sop.p("Type the supervisor password and tap **ACCESS**.")

    if wait_for([r"btnPopupPositive", r"ACCESS"], timeout=5):
        type_text(PASSWORD)
        time.sleep(0.4)
        tap_id("btnPopupPositive", timeout=3)
    time.sleep(1.5)

    if wait_for([r"MQTT|Scanner ID|Station"], timeout=6):
        p_settings_top = cap("03_settings_top", "Settings — top section")
        sop.img(p_settings_top, "Settings — top section (MQTT and scanner IDs)")
        sop.p(
            "**MQTT Settings:** Enter the broker host, port, and credentials provided by your IT "
            "team. **Scanner ID** and **Station ID** identify this device on the network."
        )

        # Scroll down to reveal middle section (longer, slower swipe for this device)
        swipe(540, 1700, 540, 400, 600)
        time.sleep(1.2)
        p_settings_mid = cap("04_settings_mid", "Settings — middle section")
        sop.img(p_settings_mid, "Settings — middle section")

        # Second scroll to reach bottom (Unassign / Reassign section)
        swipe(540, 1700, 540, 400, 600)
        time.sleep(1.2)
        p_settings_bot = cap("05_settings_bottom", "Settings — bottom section")
        sop.img(p_settings_bot, "Settings — bottom section (Unassign / Reassign buttons)")
        sop.p(
            "Scroll to the bottom to access **Unassign Mode** and **Reassign Mode** "
            "(see Sections 11 and 12)."
        )
    sop.note("Changes are saved automatically. Tap the **back arrow** to return to the dashboard.")

    sh("input keyevent KEYCODE_BACK")
    time.sleep(2.5)
    # Wait for dashboard to be fully visible and MQTT to reconnect
    wait_for([r"tileSapLookup|tvGuideText|tvStatus|STEP 1|Lookup SAP"], timeout=10)
    time.sleep(1.0)

    # 5. SAP Lookup ─────────────────────────────────────────────────────────────
    sop.h2("5. Step 1 – SAP Document Lookup")
    sop.p(
        "Tap **Lookup SAP Entry** on the dashboard. "
        "Enter the SAP document number and select the document type, then tap **LOOKUP**."
    )

    tap_id("tileSapLookup", timeout=5) or tap("tile_sap")
    time.sleep(2.5)
    if not wait_for([r"etDocNumber|spinnerDocType|Lookup SAP"], timeout=10):
        print("!! SAP lookup screen not found")
        sys.exit(1)

    p_sap_empty = cap("06_sap_lookup_empty", "SAP Lookup — empty")
    sop.img(p_sap_empty, "SAP Lookup screen — empty")
    sop.bullet(
        "**Document Number** — enter the SAP PO number or Transfer Request number.",
        "**Document Type** — select *Purchase Order* or *Stock Transfer* from the dropdown.",
        "**Vendor Reference** — (Purchase Order only) enter an optional internal reference for logging.",
    )

    tap("sap_docnum_field")
    time.sleep(0.4)
    type_text(PO_NUMBER)
    time.sleep(0.4)

    # Open dropdown and select first item ("Purchase Order") using D-pad navigation
    # to avoid accidentally tapping the LOOKUP button which overlaps with dropdown items
    tap_id("spinnerDocType", timeout=3) or tap("sap_doctype_spinner")
    time.sleep(0.8)
    sh("input keyevent KEYCODE_DPAD_DOWN")   # focus first item: "Purchase Order"
    time.sleep(0.3)
    sh("input keyevent KEYCODE_ENTER")        # select it
    time.sleep(0.6)

    # Vendor reference field appears after "Purchase Order" is selected
    if tap_id("etVendorReference", timeout=2):
        time.sleep(0.2)
        type_text(VENDOR_REF)
        time.sleep(0.3)

    p_sap_filled = cap("07_sap_lookup_filled", "SAP Lookup — filled in")
    sop.img(p_sap_filled, "SAP Lookup screen — PO number entered, vendor reference and type selected")

    tap_id("btnSubmit", timeout=3) or tap("sap_lookup_btn")
    time.sleep(0.5)
    p_sap_loading = cap("08_sap_lookup_loading", "SAP Lookup — loading")
    sop.img(p_sap_loading, "SAP Lookup — waiting for station response")
    sop.p("The app contacts the Station 1 PC. A progress indicator is displayed while waiting.")

    # The MQTT sap_result arrives in ~0.5-1 s; success popup holds 2 s then auto-navigates.
    # screencap is fast (<0.5 s). We're at ~1.0 s from tap after the loading cap.
    # Sleep 0.3 s more → capture at ~1.3 s, well within the 2 s popup window.
    time.sleep(0.3)
    p_sap_success = cap("09_sap_lookup_success", "SAP Lookup — success popup")
    sop.img(p_sap_success, "SAP Lookup — success confirmation (auto-dismisses after 2 s)")
    sop.p(
        "On success, the app shows a brief confirmation and automatically navigates "
        "to the **Product Selection** screen."
    )

    # Wait for popup to hold (2 s) + dismiss + navigate to ProductRequestActivity
    time.sleep(4)  # popup hold + activity transition + buffer

    # Confirm we are on the Product Request screen
    if not wait_for([r"tvDocNumberDisplay|tvStatus|btnSubmitRequest|cbSelect"], timeout=30):
        print(f"!! Product request screen not reached after SAP lookup — UI: {dump_ui()[:400]}")
        sys.exit(1)

    # 6. Product Selection ──────────────────────────────────────────────────────
    sop.h2("6. Step 2 – Product Selection")
    sop.p(
        "The product list shows all open lines from the SAP document. "
        "Tick the checkbox next to each product that is being received in this delivery, "
        "then tap **REQUEST**."
    )

    # Wait for product list to fully render (products are fetched automatically)
    if not wait_for([r"cbSelect|btnSubmitRequest"], timeout=20):
        print("!! Product list not loaded — check that mock backend sent sap_products_response")

    p_product_list = cap("10_product_request", "Product Request — products loaded")
    sop.img(p_product_list, "Product Selection — product list loaded")

    tap("product_cb_1")
    time.sleep(0.8)
    p_product_checked = cap("11_product_selected", "Product Request — item checked")
    sop.img(p_product_checked, "Product Selection — product ticked")
    sop.p(
        "Tick each product line being received. If multiple products are on the document "
        "tick all that apply. Tap **REQUEST** when done."
    )

    set_scenario("slow")
    tap("product_submit")
    time.sleep(0.8)
    p_prod_working = cap("38_product_request_working", "Product Request — Working popup")
    sop.img(p_prod_working, "Product Request — Working popup while Station 1 processes the selection")
    sop.p("A **Working** popup appears while the app waits for Station 1 to acknowledge the selection.")
    time.sleep(4.0)  # slow delay (~3.5 s) + buffer; success popup visible at ~4.0–6.0 s from tap
    p_prod_success = cap("39_product_request_success", "Product Request — Success popup")
    sop.img(p_prod_success, "Product Request — success confirmation (auto-dismisses after 2 s)")
    sop.p("On success, a brief confirmation appears and the app navigates to Tag Assignment.")
    clear_scenario()
    time.sleep(2.0)  # wait for success popup to dismiss and navigation to complete
    if not wait_for([r"etRfid|spinnerProduct|TagAssignment|RFID"], timeout=10):
        print("!! Tag assignment screen not reached")
        sys.exit(1)

    # 7. Tag Assignment ─────────────────────────────────────────────────────────
    sop.h2("7. Step 3 – RFID Tag Assignment")
    sop.p(
        "The Tag Assignment screen is where RFID tags are paired with pallets. "
        "For each pallet on the truck, scan its RFID tag and tap **SUBMIT**."
    )

    p_tag_empty = cap("12_tag_assignment_empty", "Tag Assignment — empty")
    sop.img(p_tag_empty, "Tag Assignment — ready to scan first tag")
    sop.bullet(
        "**Product spinner** — select the product for this pallet if multiple products are active.",
        "**Pallet sequence** — enter the pallet number (auto-increments by default).",
        "**Scan RFID Tag** — place the RFID tag in range of the scanner, or type the tag ID.",
        "**SUBMIT** — confirms the tag and creates the pallet record in Station 1.",
    )

    # Assign first tag
    cursor = len(read_log_lines())
    captured: list[tuple[str, str]] = []

    rfid1 = gen_rfid()
    rfid_scan(rfid1)
    time.sleep(1.0)
    p_tag_scanned = cap("13_tag_scanned", "Tag Assignment — RFID scanned")
    sop.img(p_tag_scanned, "Tag Assignment — RFID tag detected, ready to submit")
    sop.p("The scanned tag ID appears in the RFID field. Review it, then tap **SUBMIT**.")

    set_scenario("slow")
    xy = tap_id("btnSubmit", timeout=3) or tap("tag_submit")
    time.sleep(0.8)
    p_tag_working = cap("40_tag_assign_working", "Tag Assignment — Working popup")
    sop.img(p_tag_working, "Tag Assignment — Working popup while Station 1 creates the pallet record")
    sop.p("A **Working** popup appears while Station 1 creates the pallet record and generates a barcode.")
    time.sleep(4.0)  # slow delay + buffer; success popup visible at ~4.0–6.0 s from tap
    p_tag_success = cap("40b_tag_assign_success", "Tag Assignment — Success popup")
    sop.img(p_tag_success, "Tag Assignment — success popup showing pallet barcode (auto-dismisses after 2 s)")
    sop.p(
        "On success, the station returns a barcode for the pallet. "
        "The popup auto-dismisses after 2 s and the pallet is added to the assignment list."
    )
    clear_scenario()
    time.sleep(2.0)  # allow success popup to dismiss
    bc1, cursor = wait_assignment_barcode(rfid1, cursor)
    captured.append((rfid1, bc1 or ""))

    p_tag_assigned_1 = cap("14_tag_assigned_1", "Tag Assignment — first pallet assigned")
    sop.img(p_tag_assigned_1, "Tag Assignment — pallet 1 in the list, ready for next scan")
    sop.p(
        "The pallet is added to the assignment list below. Repeat for each pallet on the truck."
    )

    # Assign second tag
    rfid2 = gen_rfid()
    rfid_scan(rfid2)
    time.sleep(1.0)
    xy2 = tap_id("btnSubmit", timeout=3) or tap("tag_submit_after")
    time.sleep(2.5)
    bc2, cursor = wait_assignment_barcode(rfid2, cursor)
    captured.append((rfid2, bc2 or ""))

    p_tag_assigned_2 = cap("15_tag_assigned_2", "Tag Assignment — two pallets assigned")
    sop.img(p_tag_assigned_2, "Tag Assignment — all pallets assigned")

    # All Assigned
    sop.p(
        "When all RFID tags have been scanned, tap **ALL ASSIGNED** to confirm that "
        "all pallets for this delivery have been registered."
    )
    xy_all = tap_id("btnAllAssigned", timeout=3) or tap("all_assigned_btn")
    time.sleep(1.0)
    if wait_for([r"CONFIRM|Are you sure|btnPopupPositive"], timeout=5):
        p_confirm_all = cap("16_all_assigned_confirm", "All Assigned — confirm dialog")
        sop.img(p_confirm_all, "All Assigned — confirmation dialog")
        sop.p("Tap **YES** to confirm all pallets are assigned and proceed to printing.")
        tap("confirm_yes")
        time.sleep(1.5)

    # Print All popup
    if wait_for([r"PRINT|btnPopupPositive"], timeout=6):
        p_print_popup = cap("17_print_all_popup", "Print All — popup")
        sop.img(p_print_popup, "Print All — popup after All Assigned")
        sop.p(
            "A popup confirms assignments are complete and prompts to print all labels. "
            "Tap **PRINT ALL** to send all pallet labels to the label printer."
        )
        tap_id("btnPopupPositive", timeout=3) or tap("print_all_btn")
        time.sleep(0.8)
        p_print_working = cap("41_print_working", "Label Printing — Printing popup")
        sop.img(p_print_working, "Label Printing — Printing popup while labels are sent to the printer")
        sop.p("A **Printing** popup appears while labels are being sent to the printer.")
        # Mock responds after ~1.5 s delay; success popup visible at ~2.0–4.0 s from tap
        time.sleep(1.8)  # total ~2.6 s from tap
        p_print_success = cap("42_print_success", "Label Printing — Success popup")
        sop.img(p_print_success, "Label Printing — success confirmation (auto-dismisses after 2 s)")
        sop.p("On success, all labels are printed and the app navigates to the **Offload** screen.")
        time.sleep(2.0)  # wait for navigation to offload

    # 8. Offloading ─────────────────────────────────────────────────────────────
    sop.h2("8. Step 4 – Pallet Offloading")
    sop.p(
        "After printing, the app navigates to the **Offload** screen. "
        "As each pallet is removed from the truck, scan its **barcode** first, "
        "then scan its **RFID tag**, and tap **SUBMIT**."
    )

    if not wait_for([r"etBarcode|Offload|OFFLOAD"], timeout=12):
        print("!! Offload screen not reached")
    time.sleep(1.0)

    p_offload_empty = cap("18_offload_empty", "Offload — empty")
    sop.img(p_offload_empty, "Offload screen — ready to scan first pallet")
    sop.bullet(
        "**Barcode** — scan the printed label barcode or type it manually.",
        "**RFID Tag** — scan the pallet's RFID tag.",
        "**Bag Count** — enter the actual number of bags on this pallet.",
        "**SUBMIT** — records the offload and validates the barcode/RFID pair.",
    )

    for i, (rfid, barcode) in enumerate(captured, start=1):
        if not barcode:
            continue
        barcode_scan(barcode)
        time.sleep(1.2)
        p_bc_scanned = cap(f"19_offload_bc_{i}", f"Offload — barcode scanned (pallet {i})")
        sop.img(p_bc_scanned, f"Offload — barcode scanned for pallet {i}")
        sop.p(f"Pallet {i}: scan the barcode. The barcode field populates automatically.")

        rfid_scan(rfid)
        time.sleep(1.2)
        p_rfid_scanned = cap(f"20_offload_rfid_{i}", f"Offload — RFID scanned (pallet {i})")
        sop.img(p_rfid_scanned, f"Offload — RFID scanned for pallet {i}")
        sop.p("Then scan the RFID tag. Both fields are now populated.")

        if i == 1:
            # Use slow scenario to capture Working + Success popups for first pallet
            set_scenario("slow")
            tap("offload_submit")
            time.sleep(0.8)
            p_off_working = cap("43_offload_working", "Offload — Working popup")
            sop.img(p_off_working, "Offload — Working popup while Station 1 validates and records the pallet")
            sop.p("A **Working** popup appears while Station 1 validates the barcode/RFID pair.")
            time.sleep(4.0)  # slow delay + buffer; success popup visible at ~4.0–6.0 s from tap
            p_off_success = cap("43b_offload_success", "Offload — Success popup")
            sop.img(p_off_success, "Offload — success confirmation (auto-dismisses after 2 s)")
            sop.p("On success, the fields clear ready for the next pallet. Repeat for every pallet.")
            clear_scenario()
            time.sleep(2.0)
            p_offload_done = cap(f"21_offload_done_{i}", f"Offload — pallet {i} complete")
            sop.img(p_offload_done, f"Offload — pallet {i} recorded, fields cleared for next scan")
        else:
            tap("offload_submit")
            time.sleep(4.0)
            p_offload_done = cap(f"21_offload_done_{i}", f"Offload — pallet {i} complete")
            sop.img(p_offload_done, f"Offload — pallet {i} successfully recorded")
            sop.p(
                "A success message confirms the offload. The fields clear ready for the next pallet. "
                "Repeat for every pallet."
            )

    # 9. Complete session ───────────────────────────────────────────────────────
    sop.h2("9. Completing a Session")
    sop.p(
        "When all pallets are offloaded, tap **FINISH SESSION**. "
        "Confirm when prompted. The station will post the pallets to SAP."
    )

    tap("finish_session")
    time.sleep(1.0)
    if wait_for([r"CONFIRM FINISH|finish this session|btnPopupPositive"], timeout=6):
        p_finish_confirm = cap("22_finish_confirm", "Finish Session — confirmation dialog")
        sop.img(p_finish_confirm, "Finish Session — confirmation dialog")
        sop.p("Tap **YES** to confirm. The station will post the receiving data to SAP.")
        tap("finish_yes")
        time.sleep(3.0)

    if wait_for([r"SESSION FINISHED|btnPopupPositive"], timeout=15):
        p_finished = cap("23_session_finished", "Session Finished — popup")
        sop.img(p_finished, "Session Finished — SAP posting complete")
        sop.p(
            "The Session Finished popup summarises the SAP posting result. "
            "Tap **FINISH** (or **DISMISS**) to return to the dashboard."
        )
        tap_id("btnPopupPositive", timeout=10) or tap("finish_dismiss")
        time.sleep(2.0)

    p_dash_done = cap("24_main_dashboard_complete", "Dashboard — session complete")
    sop.img(p_dash_done, "Dashboard — returned to idle after session")
    sop.note(
        "The dashboard resets to the idle state. The operator can start a new session "
        "immediately by tapping **Lookup SAP Entry** again."
    )

    # ─────────────────── PHASE 2: ERROR SCENARIOS ────────────────────────────
    print("\n─── Phase 2: Error scenarios ───")

    sop.h2("10. Error Messages & Recovery")
    sop.p(
        "The app displays a dismissible popup with an error title and recovery instructions "
        "whenever a step fails. Each error type and its resolution is described below."
    )

    # 10a. SAP lookup failure ───────────────────────────────────────────────────
    sop.h3("10.1 SAP Lookup Failed")
    reset_app()
    clear_scenario()
    set_scenario("fail_sap")
    launch_app()
    tap("tile_sap")
    time.sleep(2.0)
    if wait_for([r"etDocNumber|spinnerDocType"], timeout=8):
        tap("sap_docnum_field")
        time.sleep(0.3)
        type_text("999999999")
        time.sleep(0.3)
        select_doctype_po()
        tap_id("btnSubmit", timeout=3) or tap("sap_lookup_btn")
        time.sleep(3.0)
        if wait_for([r"DISMISS|btnPopupPositive|not found|Failed"], timeout=8):
            p_sap_fail = cap("25_error_sap_lookup", "Error — SAP lookup failed")
            sop.img(p_sap_fail, "Error — SAP lookup failed popup")
            sop.p("**Cause:** The document number is not found in SAP, or the document is already closed.")
            sop.bullet(
                "Verify the document number on the delivery note.",
                "Confirm the correct document type (Purchase Order vs Stock Transfer) is selected.",
                "Contact your supervisor if the document should be open.",
            )
            tap_id("btnPopupNegative", timeout=3) or tap_id("DISMISS", timeout=3)
    clear_scenario()

    # 10b. Tag assignment failure ───────────────────────────────────────────────
    sop.h3("10.2 RFID Tag Assignment Failed")
    reset_app()
    # Run through to tag assignment with success, then switch to fail
    launch_app()
    tap("tile_sap")
    time.sleep(2.0)
    if do_sap_lookup():
        time.sleep(2.0)
        tap("product_cb_1")
        time.sleep(0.5)
        tap("product_submit")
    if wait_for([r"etRfid|spinnerProduct"], timeout=10):
        set_scenario("fail_assignment")
        rfid_err = gen_rfid()
        rfid_scan(rfid_err)
        time.sleep(1.0)
        tap_id("btnSubmit", timeout=3) or tap("tag_submit")
        time.sleep(3.0)
        if wait_for([r"DISMISS|btnPopupNegative|already assigned|Failed"], timeout=8):
            p_assign_fail = cap("26_error_assignment", "Error — assignment failed")
            sop.img(p_assign_fail, "Error — RFID tag assignment failed popup")
            sop.p("**Cause:** The RFID tag is already assigned to another pallet or is not readable.")
            sop.bullet(
                "Try scanning the RFID tag again — the tag may have been misread.",
                "If the tag is already assigned: go to **Settings → Unassign Mode**, "
                "scan the tag to free it, then return to Tag Assignment.",
                "Replace a physically damaged tag and re-scan.",
            )
            tap_id("btnPopupNegative", timeout=3) or tap_id("DISMISS", timeout=3)
    clear_scenario()

    # 10c. Print failure ────────────────────────────────────────────────────────
    sop.h3("10.3 Print All Failed")
    reset_app()
    launch_app()
    tap("tile_sap")
    time.sleep(2.0)
    if do_sap_lookup():
        time.sleep(2.0)
        tap("product_cb_1")
        time.sleep(0.5)
        tap("product_submit")
    if wait_for([r"etRfid|spinnerProduct"], timeout=10):
        cursor2 = len(read_log_lines())
        rfid_p = gen_rfid()
        rfid_scan(rfid_p)
        time.sleep(1.0)
        tap_id("btnSubmit", timeout=3) or tap("tag_submit")
        time.sleep(2.5)
        wait_assignment_barcode(rfid_p, cursor2)
        # All Assigned → confirm → print with fail
        set_scenario("fail_print")
        tap_id("btnAllAssigned", timeout=3) or tap("all_assigned_btn")
        time.sleep(1.0)
        if wait_for([r"CONFIRM|Are you sure"], timeout=5):
            tap("confirm_yes")
            time.sleep(1.5)
        if wait_for([r"PRINT|btnPopupPositive"], timeout=6):
            tap_id("btnPopupPositive", timeout=3) or tap("print_all_btn")
            time.sleep(3.0)
        if wait_for([r"DISMISS|btnPopupNegative|Printer failed|Failed"], timeout=8):
            p_print_fail = cap("27_error_print", "Error — print failed")
            sop.img(p_print_fail, "Error — Print All failed popup")
            sop.p("**Cause:** The label printer is offline, out of labels, or unreachable on the network.")
            sop.bullet(
                "Check that the TSC label printer is powered on and the green light is steady.",
                "Verify the printer's network cable or Wi-Fi connection.",
                "Tap **REPRINT** (if shown) to retry once the printer is ready.",
                "Contact your supervisor if the printer cannot be reached.",
            )
            tap_id("btnPopupNegative", timeout=3) or tap_id("DISMISS", timeout=3)
    clear_scenario()

    # 10d. Offload failure ──────────────────────────────────────────────────────
    sop.h3("10.4 Offload Failed")
    reset_app()
    # Navigate to offload screen and trigger a failure
    launch_app()
    # Brief note without going through full flow again
    sop.p(
        "If an offload scan fails (e.g., mismatched barcode/RFID pair) a popup is shown:"
    )
    set_scenario("fail_offload")
    # We can show this by replaying the offload step with an invalid tag
    tap("tile_sap")
    time.sleep(2.0)
    if do_sap_lookup():
        time.sleep(2.0)
        tap("product_cb_1")
        time.sleep(0.5)
        tap("product_submit")
    if wait_for([r"etRfid|spinnerProduct"], timeout=10):
        cursor3 = len(read_log_lines())
        clear_scenario()  # assign successfully
        rfid_off = gen_rfid()
        rfid_scan(rfid_off)
        time.sleep(1.0)
        tap_id("btnSubmit", timeout=3) or tap("tag_submit")
        time.sleep(2.5)
        bc_off, _ = wait_assignment_barcode(rfid_off, cursor3)
        # All Assigned → confirm → print → offload screen
        tap_id("btnAllAssigned", timeout=3) or tap("all_assigned_btn")
        time.sleep(1.0)
        if wait_for([r"CONFIRM|Are you sure"], timeout=5):
            tap("confirm_yes")
            time.sleep(1.5)
        if wait_for([r"PRINT|btnPopupPositive"], timeout=6):
            tap_id("btnPopupPositive", timeout=3) or tap("print_all_btn")
            time.sleep(3.0)
        if wait_for([r"etBarcode|Offload"], timeout=12):
            # now fail the offload
            set_scenario("fail_offload")
            if bc_off:
                barcode_scan(bc_off)
                time.sleep(1.0)
            rfid_scan(gen_rfid())  # wrong RFID → mismatch
            time.sleep(1.0)
            tap("offload_submit")
            time.sleep(4.0)
            if wait_for([r"DISMISS|btnPopupNegative|do not match|Failed"], timeout=8):
                p_offload_fail = cap("28_error_offload", "Error — offload failed")
                sop.img(p_offload_fail, "Error — offload barcode/RFID mismatch popup")
                sop.p("**Cause:** The barcode and RFID tag scanned do not belong to the same pallet.")
                sop.bullet(
                    "Re-scan the barcode on the label of the pallet.",
                    "Then re-scan the RFID tag attached to the same pallet.",
                    "Ensure you are scanning the label and tag on the **same** pallet.",
                    "Use **Reassign Mode** (Settings → Reassign) if a tag has been moved to a different pallet.",
                )
                tap_id("btnPopupNegative", timeout=3) or tap_id("DISMISS", timeout=3)
    clear_scenario()

    # ─────────────────── PHASE 3: UNASSIGN ────────────────────────────────────
    print("\n─── Phase 3: Unassign / Reassign ───")

    sop.h2("11. Unassign Mode")
    sop.p(
        "Use **Unassign Mode** to remove an RFID tag from its pallet assignment. "
        "This is needed when a tag has been assigned in error or needs to be reused."
    )
    sop.p("Access: **Dashboard → ⚙ Settings → (scroll to bottom) → UNASSIGN MODE**")

    reset_app()
    launch_app()
    tap("settings_gear")
    time.sleep(1.0)
    if wait_for([r"btnPopupPositive", r"ACCESS"], timeout=5):
        type_text(PASSWORD)
        time.sleep(0.4)
        tap_id("btnPopupPositive", timeout=3)
    time.sleep(1.5)
    # Scroll to bottom to find Unassign button
    swipe(540, 1800, 540, 600, 400)
    time.sleep(0.6)
    swipe(540, 1800, 540, 600, 400)
    time.sleep(0.6)

    p_settings_unassign = cap("29_settings_unassign_btn", "Settings — Unassign button visible")
    sop.img(p_settings_unassign, "Settings — scroll to bottom to find Unassign Mode button")

    if wait_for([r"UNASSIGN|unassign", r"btnUnassign"], timeout=5):
        tap_id("btnUnassign", timeout=3) or tap("settings_unassign_btn")
        time.sleep(2.0)
        if wait_for([r"Unassign Mode|tvLastScanned"], timeout=6):
            p_unassign_empty = cap("30_unassign_empty", "Unassign Mode — empty")
            sop.img(p_unassign_empty, "Unassign Mode — waiting for RFID scan")
            sop.p("Scan the RFID tag you want to unassign. The app automatically sends the unassign request.")

            rfid_un = gen_rfid()
            rfid_scan(rfid_un)
            time.sleep(3.0)
            p_unassign_result = cap("31_unassign_result", "Unassign Mode — result")
            sop.img(p_unassign_result, "Unassign Mode — success confirmation")
            sop.p("A green status message confirms the tag has been unassigned.")

            # Unassign error scenario
            sop.h3("11.1 Unassign Failed")
            sop.p("If the RFID tag is not found in the system, an error popup is displayed:")
            set_scenario("fail_unassign")
            rfid_un_fail = gen_rfid()
            rfid_scan(rfid_un_fail)
            time.sleep(3.0)
            if wait_for([r"DISMISS|btnPopupNegative|not found|Failed"], timeout=8):
                p_unassign_fail = cap("44_error_unassign", "Unassign Mode — error popup")
                sop.img(p_unassign_fail, "Unassign Mode — tag not found error popup")
            sop.bullet(
                "Verify the correct RFID tag was scanned.",
                "Confirm the tag was previously assigned in an active session.",
                "Contact your supervisor if the tag cannot be found.",
            )
            clear_scenario()
    sh("input keyevent KEYCODE_BACK")
    time.sleep(1.0)

    # ─────────────────── PHASE 4: REASSIGN ────────────────────────────────────
    sop.h2("12. Reassign Mode")
    sop.p(
        "Use **Reassign Mode** to move an RFID tag from one pallet barcode to another. "
        "This is needed when a tag was accidentally placed on the wrong pallet."
    )
    sop.p("Access: **Dashboard → ⚙ Settings → (scroll to bottom) → REASSIGN MODE**")

    # Scroll to bottom again
    swipe(540, 1800, 540, 600, 400)
    time.sleep(0.6)
    swipe(540, 1800, 540, 600, 400)
    time.sleep(0.6)

    p_settings_reassign = cap("32_settings_reassign_btn", "Settings — Reassign button visible")
    sop.img(p_settings_reassign, "Settings — Reassign Mode button at bottom of settings")

    if wait_for([r"REASSIGN|reassign", r"btnReassign"], timeout=5):
        tap_id("btnReassign", timeout=3) or tap("settings_reassign_btn")
        time.sleep(2.0)
        if wait_for([r"Reassign Mode|etBarcode|etRfid"], timeout=6):
            p_reassign_empty = cap("33_reassign_empty", "Reassign Mode — empty")
            sop.img(p_reassign_empty, "Reassign Mode — ready to scan")
            sop.p(
                "1. Scan the **barcode** on the target pallet label (destination barcode).  \n"
                "2. Scan the **RFID tag** to be moved.  \n"
                "3. Tap **SUBMIT**."
            )

            # Simulate scanning
            barcode_scan("1234567890")
            time.sleep(0.8)
            rfid_scan(gen_rfid())
            time.sleep(0.8)
            p_reassign_filled = cap("34_reassign_filled", "Reassign Mode — fields populated")
            sop.img(p_reassign_filled, "Reassign Mode — barcode and RFID filled in, ready to submit")

            tap_id("btnSubmit", timeout=3)
            time.sleep(3.0)
            p_reassign_result = cap("35_reassign_result", "Reassign Mode — result")
            sop.img(p_reassign_result, "Reassign Mode — success confirmation")
            sop.p("A green status message confirms the tag has been reassigned.")

            # Reassign error scenario
            sop.h3("12.1 Reassign Failed")
            sop.p("If the barcode is not found in any active session, an error popup is displayed:")
            set_scenario("fail_reassign")
            barcode_scan("9999999999")
            time.sleep(0.5)
            rfid_scan(gen_rfid())
            time.sleep(0.5)
            tap_id("btnSubmit", timeout=3)
            time.sleep(3.0)
            if wait_for([r"DISMISS|btnPopupNegative|not found|Failed"], timeout=8):
                p_reassign_fail = cap("45_error_reassign", "Reassign Mode — error popup")
                sop.img(p_reassign_fail, "Reassign Mode — barcode not found error popup")
            sop.bullet(
                "Verify the barcode was scanned correctly.",
                "Ensure the barcode belongs to a pallet in an active receiving session.",
                "Contact your supervisor if the pallet cannot be found.",
            )
            clear_scenario()

    # ─────────────────── SAVE SOP ─────────────────────────────────────────────
    sop.h2("13. Troubleshooting Summary")
    sop.p("Quick reference for common issues:")
    sop.p(
        "| Error | Likely Cause | Action |\n"
        "|---|---|---|\n"
        "| SAP lookup failed | Wrong doc number / doc closed | Check doc number and type |\n"
        "| Tag already assigned | Tag used twice | Use Unassign Mode first |\n"
        "| Print failed | Printer offline | Power cycle printer, check network |\n"
        "| Offload mismatch | Wrong barcode/RFID combo | Re-scan the correct pallet |\n"
        "| MQTT connect failed | Wi-Fi or broker issue | Check Wi-Fi; update Settings |\n"
        "| Unassign failed | Tag not assigned | Verify tag ID with supervisor |"
    )

    sop.save(SOP_FILE)
    print(f"\nDone! Screenshots: {IMAGES_DIR}")
    print(f"SOP:              {SOP_FILE}")


if __name__ == "__main__":
    run()
