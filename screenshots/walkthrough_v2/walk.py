"""
PPNAM walkthrough — single-chunk recorder.

One screenrecord captures the whole flow (capped at 180s by the Android
screenrecord limit). Device is forced to stay awake first. After every
major navigation step we dump the UI XML and verify an expected text
marker is present; if not, we abort cleanly so the video / log shows
exactly where things went wrong instead of blindly continuing.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
import time
import uuid
from pathlib import Path

ADB = r"C:\Users\JonathanSystemOne\AppData\Local\Android\Sdk\platform-tools\adb.exe"
FFMPEG = r"C:\Users\JonathanSystemOne\AppData\Local\Programs\Python\Python313\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe"
HERE = Path(__file__).resolve().parent
MQTT_LOG = HERE.parent / "mqtt_log.txt"
OUT = HERE / "run"

# Verified coords (1080x2400, com.sysone.scanner)
LAUNCHER_ICON = (153, 884)
SETTINGS_GEAR = (772, 192)
PASSWORD_OK = (792, 1139)
# Main screen tiles
TILE_SAP = (270, 856)
TILE_TAG = (270, 1469)
TILE_OFFLOAD = (810, 1469)
# SAP Lookup screen
SAP_DOCNUM_FIELD = (540, 589)
SAP_DOCTYPE_SPINNER = (540, 802)
SAP_DOCTYPE_PURCHASE_ORDER = (540, 1010)
SAP_LOOKUP_BTN = (540, 1042)
# Product Request screen — checkboxes for PO 220008669 (2 products)
PRODUCT_CHECKBOXES = [(144, 964), (144, 1237)]
PRODUCT_SUBMIT = (540, 1500)
# Tag Assignment screen
TAG_PRODUCT_SPINNER = (540, 773)
TAG_PRODUCT_ITEM_2 = (540, 1230)
TAG_SUBMIT = (540, 1254)
TAG_SUBMIT_AFTER = (540, 1197)
ALL_ASSIGNED = (540, 1494)
CONFIRM_YES = (792, 1054)
PRINT_ALL = (540, 1029)
# Offload screen
OFFLOAD_SUBMIT = (540, 1556)
FINISH_SESSION = (540, 1751)
FINISH_YES = (792, 1029)
FINISH_DISMISS = (540, 1054)
PASSWORD = "Mit@s_"
PO_NUMBER = "220008669"


# --------------------------------------------------------------- adb wrap


def adb(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run([ADB, *args], capture_output=True, text=True, check=check)


def sh(cmd: str) -> str:
    return adb("shell", cmd).stdout


def tap(xy: tuple[int, int]) -> None:
    sh(f"input tap {xy[0]} {xy[1]}")


def swipe(x1: int, y1: int, x2: int, y2: int, ms: int = 400) -> None:
    sh(f"input swipe {x1} {y1} {x2} {y2} {ms}")


def hide_keyboard() -> None:
    """Dismiss the soft keyboard if visible. Otherwise the on-screen
    keyboard shifts dialog buttons upward and our tap coordinates
    target the keyboard instead of the intended button."""
    out = sh("dumpsys input_method | grep mInputShown")
    if "mInputShown=true" in out:
        # BACK closes the IME first without dismissing the dialog.
        sh("input keyevent KEYCODE_BACK")
        time.sleep(0.4)


def text_in(s: str) -> None:
    """Type `s` into the focused field one char at a time, passing each
    char as a discrete adb shell argv element so the device's shell
    doesn't reinterpret '@' or '_'. (KEYCODE_AT on this scanner maps to
    '8', so we cannot use keyevent.) Hides the IME afterwards so any
    follow-up tap hits the right coordinates."""
    for ch in s:
        adb("shell", "input", "text", ch, check=False)
    hide_keyboard()


def key(code: str) -> None:
    sh(f"input keyevent {code}")


def barcode_scan(value: str) -> None:
    sh(f'am broadcast -a com.scanner.broadcast --es data "{value}"')


def rfid_scan(value: str) -> None:
    sh(f'am broadcast -a com.rscja.scanner.action.scanner.RFID --es data "{value}"')


def gen_rfid() -> str:
    return "E28011C1A5" + uuid.uuid4().hex[:14].upper()


def dump_ui() -> str:
    sh("uiautomator dump /sdcard/u.xml >/dev/null 2>&1")
    tmp = HERE / "_u.xml"
    adb("pull", "/sdcard/u.xml", str(tmp), check=False)
    sh("rm -f /sdcard/u.xml")
    try:
        return tmp.read_text(encoding="utf-8", errors="replace")
    except FileNotFoundError:
        return ""


def find_bounds(xml: str, pattern: str) -> tuple[int, int] | None:
    rx = re.compile(pattern, re.IGNORECASE)
    for m in re.finditer(
        r"<node[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"[^>]*/?>", xml
    ):
        start = m.start()
        end = xml.find(">", start) + 1
        tag = xml[start:end]
        if rx.search(tag):
            x1, y1, x2, y2 = map(int, m.groups())
            return ((x1 + x2) // 2, (y1 + y2) // 2)
    return None


def tap_id(rid: str, timeout: float = 4.0) -> tuple[int, int] | None:
    """Find a node by resource-id (or text) substring in the live UI
    dump and tap its center. Returns the (x,y) tapped, or None if not
    found within `timeout`. Hides the soft keyboard first so bottom
    buttons aren't covered."""
    hide_keyboard()
    end = time.time() + timeout
    pat = re.escape(rid)
    while time.time() < end:
        xml = dump_ui()
        xy = find_bounds(xml, pat)
        if xy is not None:
            tap(xy)
            return xy
        time.sleep(0.4)
    return None


# --------------------------------------------------------------- recorder


class Recorder:
    def __init__(self, name: str, time_limit: int = 180) -> None:
        OUT.mkdir(parents=True, exist_ok=True)
        self.name = name
        self.device_path = f"/sdcard/{name}.mp4"
        self.local_mp4 = OUT / f"{name}.mp4"
        self.events: list[tuple[float, str]] = []
        self.t0 = 0.0
        self.proc: subprocess.Popen | None = None
        self.time_limit = time_limit

    def start(self) -> None:
        sh(f"rm -f {self.device_path}")
        self.proc = subprocess.Popen(
            [
                ADB,
                "shell",
                "screenrecord",
                "--time-limit",
                str(self.time_limit),
                "--bit-rate",
                "6000000",
                self.device_path,
            ],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        time.sleep(1.5)
        self.t0 = time.time()
        self.mark("rec_start")

    def mark(self, label: str) -> float:
        label = re.sub(r"[^A-Za-z0-9_.-]+", "_", label)[:80]
        t = time.time() - self.t0
        self.events.append((t, label))
        print(f"  [{t:6.2f}s] {label}", flush=True)
        return t

    def stop(self) -> None:
        # Send SIGINT via the device shell. pkill returns nonzero if no
        # process matches; that's fine.
        adb("shell", "pkill -INT screenrecord", check=False)
        if self.proc is not None:
            try:
                self.proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        time.sleep(2.0)
        adb("pull", self.device_path, str(self.local_mp4), check=False)
        adb("shell", f"rm -f {self.device_path}", check=False)
        (OUT / f"{self.name}.events.json").write_text(json.dumps(self.events, indent=2))

    def extract_frames(self) -> None:
        if not self.local_mp4.exists():
            print(f"!! no mp4 to extract from for {self.name}")
            return
        for idx, (t, label) in enumerate(self.events):
            png = OUT / f"{idx:03d}_{label}.png"
            ss = max(t, 0.05)
            subprocess.run(
                [
                    FFMPEG,
                    "-y",
                    "-loglevel",
                    "error",
                    "-ss",
                    f"{ss:.2f}",
                    "-i",
                    str(self.local_mp4),
                    "-frames:v",
                    "1",
                    str(png),
                ],
                check=False,
            )


# ------------------------------------------------------------------ MQTT


def mqtt_lines() -> list[str]:
    if not MQTT_LOG.exists():
        return []
    return MQTT_LOG.read_text(encoding="utf-8", errors="replace").splitlines()


def wait_for_assignment_barcode(
    rfid: str, since: int, timeout: float = 10.0
) -> tuple[str | None, int]:
    end = time.time() + timeout
    rfid_u = rfid.upper()
    while time.time() < end:
        lines = mqtt_lines()
        for i in range(since, len(lines)):
            line = lines[i]
            if "/assignment_result" not in line:
                continue
            parts = line.split("|", 2)
            if len(parts) < 3:
                continue
            try:
                payload = json.loads(parts[2])
            except Exception:
                continue
            if rfid_u in json.dumps(payload).upper():
                bc = payload.get("barcode") or payload.get("palletCode") or ""
                return (bc or None), len(lines)
        time.sleep(0.3)
    return None, len(mqtt_lines())


# ---------------------------------------------------------------- prep


def keep_awake() -> None:
    """Force display on and disable lock so the screen stays alive."""
    sh("svc power stayon true")
    sh("settings put system screen_off_timeout 1800000")
    # Wake + dismiss keyguard
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")
    sh("settings put system screen_brightness 200")


def reset_app() -> None:
    sh("am force-stop com.sysone.scanner")
    sh("pm clear com.sysone.scanner")
    key("KEYCODE_HOME")
    time.sleep(1.5)


def assert_screen(
    rec: Recorder, marker_label: str, *patterns: str, timeout: float = 8.0
) -> bool:
    """Poll UI dumps until ANY of `patterns` appears in the dump.
    Returns True on success, False on timeout (and marks the failure +
    saves the last XML for debugging)."""
    end = time.time() + timeout
    last_xml = ""
    while time.time() < end:
        last_xml = dump_ui()
        for p in patterns:
            if re.search(p, last_xml, re.IGNORECASE):
                rec.mark(f"{marker_label}_OK")
                return True
        time.sleep(0.5)
    rec.mark(f"{marker_label}_FAIL")
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"FAIL_{marker_label}.xml").write_text(last_xml or "<empty>")
    return False


# --------------------------------------------------------------- driver


def run() -> None:
    print("=== PPNAM Walkthrough v3 (single-chunk) ===")
    keep_awake()
    reset_app()

    rec = Recorder("walkthrough", time_limit=180)
    rec.start()

    # Phase 1 — launch + settings
    rec.mark("home")
    time.sleep(0.5)
    tap(LAUNCHER_ICON)
    rec.mark("tap_launcher")
    time.sleep(4.5)
    if not assert_screen(rec, "main_loaded", r"Lookup SAP Entry"):
        rec.stop()
        rec.extract_frames()
        return

    tap(SETTINGS_GEAR)
    rec.mark("tap_settings_gear")
    if not assert_screen(rec, "password_dialog", r"btnPopupPositive", timeout=4):
        rec.stop()
        rec.extract_frames()
        return
    text_in(PASSWORD)
    rec.mark("password_typed")
    time.sleep(0.4)
    xy = tap_id("btnPopupPositive", timeout=2.0)
    rec.mark(f"tap_access_{xy}")
    if not assert_screen(rec, "settings_top", r"MQTT|Scanner ID|Station", timeout=6):
        rec.stop()
        rec.extract_frames()
        return

    swipe(540, 1800, 540, 600, 400)
    time.sleep(0.6)
    rec.mark("settings_mid")
    swipe(540, 1800, 540, 600, 400)
    time.sleep(0.6)
    rec.mark("settings_bottom")

    key("KEYCODE_BACK")
    rec.mark("back_to_main")
    if not assert_screen(rec, "main_after_settings", r"Lookup SAP Entry", timeout=4):
        rec.stop()
        rec.extract_frames()
        return

    # Phase 2 — SAP lookup (manual entry, NOT a barcode scan)
    tap(TILE_SAP)
    rec.mark(f"tap_sap_tile_{TILE_SAP}")
    if not assert_screen(rec, "sap_screen", r"etDocNumber|spinnerDocType", timeout=6):
        rec.stop()
        rec.extract_frames()
        return

    tap(SAP_DOCNUM_FIELD)
    rec.mark("focus_docnum")
    time.sleep(0.4)
    text_in(PO_NUMBER)
    rec.mark(f"typed_po_{PO_NUMBER}")
    time.sleep(0.4)
    tap(SAP_DOCTYPE_SPINNER)
    rec.mark("open_doctype_spinner")
    time.sleep(1.0)
    tap(SAP_DOCTYPE_PURCHASE_ORDER)
    rec.mark("pick_purchase_order")
    time.sleep(0.5)
    tap(SAP_LOOKUP_BTN)
    rec.mark("tap_lookup")
    # SAP success auto-navigates to ProductRequest screen
    if not assert_screen(
        rec, "product_request", r"btnSubmitRequest|cbSelect|REQUEST", timeout=15
    ):
        rec.stop()
        rec.extract_frames()
        return

    # Phase 3 — Product Request (2 products). The list takes a moment to fully
    # render after assert_screen returns; tapping a checkbox too early misses
    # the row, so settle here before the first tap.
    time.sleep(7.5)
    rec.mark("product_request_settled")
    tap(PRODUCT_CHECKBOXES[0])
    rec.mark("check_p1")
    time.sleep(0.8)
    tap(PRODUCT_CHECKBOXES[1])
    rec.mark("check_p2")
    time.sleep(0.8)
    tap(PRODUCT_SUBMIT)
    rec.mark(f"tap_submit_products_{PRODUCT_SUBMIT}")
    # Auto-navigates to TagAssignment
    if not assert_screen(rec, "tag_assignment", r"etRfid|spinnerProduct", timeout=10):
        rec.stop()
        rec.extract_frames()
        return

    # Phase 4 — tag assignment: tag1 keeps default product, tag2 picks product 2
    cursor = len(mqtt_lines())
    captured: list[tuple[str, str]] = []

    plan = [("p1", False), ("p2", True)]
    for i, (which, switch) in enumerate(plan, start=1):
        if switch:
            tap(TAG_PRODUCT_SPINNER)
            rec.mark(f"t{i}_open_spinner")
            time.sleep(1.0)
            tap(TAG_PRODUCT_ITEM_2)
            rec.mark(f"t{i}_pick_p2")
            time.sleep(0.6)

        rfid = gen_rfid()
        rfid_scan(rfid)
        rec.mark(f"t{i}_scan_{rfid[-6:]}")
        time.sleep(1.0)
        # btnSubmit y-coord shifts after first tag (1254 -> 1197); tap_id finds it
        xy = tap_id("btnSubmit", timeout=2.0) or tap(
            TAG_SUBMIT if i == 1 else TAG_SUBMIT_AFTER
        )
        rec.mark(f"t{i}_tap_submit_{xy}")
        time.sleep(2.5)
        bc, cursor = wait_for_assignment_barcode(rfid, cursor, timeout=10.0)
        rec.mark(f"t{i}_bc_{(bc or 'NONE')[-6:]}")
        captured.append((rfid, bc or ""))

    # ALL ASSIGNED -> CONFIRM COMPLETION dialog -> YES
    xy = tap_id("btnAllAssigned", timeout=2.0) or tap(ALL_ASSIGNED)
    rec.mark(f"tap_all_assigned_{xy}")
    if not assert_screen(rec, "confirm_completion", r"CONFIRM|Are you sure", timeout=6):
        rec.stop()
        rec.extract_frames()
        return
    tap(CONFIRM_YES)
    rec.mark("confirm_yes")
    # ASSIGNMENTS COMPLETE popup -> PRINT ALL
    if not assert_screen(rec, "print_all_popup", r"PRINT|btnPopupPositive", timeout=6):
        rec.stop()
        rec.extract_frames()
        return
    tap(PRINT_ALL)
    rec.mark("tap_print_all")
    # Auto-navigates to Offload
    if not assert_screen(rec, "offload_screen", r"etBarcode|Offload", timeout=12):
        rec.stop()
        rec.extract_frames()
        return

    # Phase 5 — Offload each captured pallet (NEVER press BACK on this screen)
    for i, (rfid, barcode) in enumerate(captured, start=1):
        if not barcode:
            rec.mark(f"p{i}_no_barcode_skip")
            continue
        barcode_scan(barcode)
        rec.mark(f"p{i}_scan_bc_{barcode[-6:]}")
        time.sleep(1.5)
        rfid_scan(rfid)
        rec.mark(f"p{i}_scan_rfid_{rfid[-6:]}")
        time.sleep(1.5)
        tap(OFFLOAD_SUBMIT)
        rec.mark(f"p{i}_tap_submit")
        time.sleep(5.0)
        rec.mark(f"p{i}_after_submit")

    # FINISH SESSION -> CONFIRM FINISH dialog YES -> SESSION FINISHED popup FINISH
    tap(FINISH_SESSION)
    rec.mark("tap_finish_session")
    if not assert_screen(
        rec, "confirm_finish", r"CONFIRM FINISH|finish this session", timeout=6
    ):
        rec.stop()
        rec.extract_frames()
        return
    tap(FINISH_YES)
    rec.mark("finish_yes")
    time.sleep(3.0)
    # Final SESSION FINISHED popup. Button y-coord shifts with message length
    # (e.g. (540,1054) for short, (540,1176) for SAP-posting summary), so locate
    # by id rather than fixed coord.
    xy = tap_id("btnPopupPositive", timeout=15.0) or tap(FINISH_DISMISS)
    rec.mark(f"session_finished_{xy}")
    time.sleep(2.0)

    rec.stop()
    rec.extract_frames()
    (OUT / "pallets.json").write_text(json.dumps(captured, indent=2))
    print("\nDone. Pallets:")
    for r, b in captured:
        print(f"  rfid={r} barcode={b}")


if __name__ == "__main__":
    sys.exit(run() or 0)
