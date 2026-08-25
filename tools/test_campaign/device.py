"""adb driver for the C72 scanner — used by campaign scripts.

    from device import Device
    d = Device()
    d.launch()
    d.tap(id="etUsername"); d.text("op.both")
    d.scan_rfid("TAG-OK-1"); d.screenshot("after_scan")
"""
from __future__ import annotations

import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe")
PACKAGE = "com.mitas.ppnam.station1"
SHOTS = Path(__file__).resolve().parent / "shots"


class Node:
    def __init__(self, element):
        self.element = element
        self.resource_id = element.get("resource-id", "")
        self.text = element.get("text", "")
        self.desc = element.get("content-desc", "")
        self.enabled = element.get("enabled") == "true"
        self.clickable = element.get("clickable") == "true"
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", element.get("bounds", ""))
        self.bounds = tuple(map(int, m.groups())) if m else (0, 0, 0, 0)

    @property
    def center(self):
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2

    def __repr__(self):
        return f"<Node {self.resource_id or self.desc!r} text={self.text!r} {self.bounds}>"


class Device:
    def __init__(self, serial: str | None = None):
        self.serial = serial

    def adb(self, *args, timeout=30) -> str:
        cmd = [ADB] + (["-s", self.serial] if self.serial else []) + list(args)
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return result.stdout

    def shell(self, *args, **kw) -> str:
        return self.adb("shell", *args, **kw)

    # ---------------------------------------------------------------- app
    def launch(self, wait: float = 2.5):
        self.shell("monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
        time.sleep(wait)

    def stop(self):
        self.shell("am", "force-stop", PACKAGE)

    def relaunch(self, wait: float = 2.5):
        self.stop()
        time.sleep(0.5)
        self.launch(wait)

    def clear_data(self):
        """Full reset: wipes prefs INCLUDING broker provisioning."""
        self.shell("pm", "clear", PACKAGE)

    def current_activity(self) -> str:
        out = self.shell("dumpsys", "activity", "activities")
        m = re.search(r"topResumedActivity.*?(\S+/\S+)\}", out)
        return m.group(1) if m else ""

    # ---------------------------------------------------------------- ui
    def ui(self) -> list[Node]:
        self.shell("uiautomator", "dump", "/sdcard/ui.xml")
        xml = self.shell("cat", "/sdcard/ui.xml")
        start = xml.find("<?xml")
        tree = ET.fromstring(xml[start:] if start >= 0 else xml)
        return [Node(e) for e in tree.iter("node")]

    def find(self, id: str | None = None, text: str | None = None,
             desc: str | None = None, retries: int = 6, interval: float = 1.0):
        """Finds a node by resource id suffix / exact text / content-desc, with retries."""
        for _ in range(retries):
            for node in self.ui():
                if id and node.resource_id.endswith(f"id/{id}"):
                    return node
                if text is not None and node.text == text:
                    return node
                if desc is not None and node.desc == desc:
                    return node
            time.sleep(interval)
        return None

    def swipe(self, x1: int, y1: int, x2: int, y2: int, ms: int = 300):
        self.shell("input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))
        time.sleep(0.5)

    def scroll_to(self, id: str, max_swipes: int = 4):
        """Finds a node, swiping up (then back down) through scrollable content."""
        node = self.find(id=id, retries=1)
        for _ in range(max_swipes):
            if node is not None:
                return node
            self.swipe(540, 1300, 540, 500)
            node = self.find(id=id, retries=1)
        for _ in range(max_swipes):
            if node is not None:
                return node
            self.swipe(540, 500, 540, 1300)
            node = self.find(id=id, retries=1)
        return node

    def wait_field(self, id: str, predicate, timeout: float = 10.0):
        """Waits until the node exists and predicate(text) is true; returns last text."""
        deadline = time.time() + timeout
        last = None
        while time.time() < deadline:
            node = self.find(id=id, retries=1)
            if node is not None:
                last = node.text
                if predicate(last):
                    return last
            time.sleep(0.5)
        return last

    def wait_gone(self, id: str, timeout: float = 10.0) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.find(id=id, retries=1) is None:
                return True
            time.sleep(0.5)
        return False

    def tap(self, id: str | None = None, text: str | None = None,
            desc: str | None = None, xy: tuple | None = None):
        if xy is None:
            node = self.find(id=id, text=text, desc=desc)
            assert node is not None, f"no node id={id} text={text} desc={desc}"
            xy = node.center
        self.shell("input", "tap", str(xy[0]), str(xy[1]))
        time.sleep(0.4)

    def text(self, value: str):
        """Types into the focused field. adb input text cannot carry spaces raw."""
        self.shell("input", "text", value.replace(" ", "%s"))
        time.sleep(0.3)

    def type_into(self, field_id: str, value: str):
        self.tap(id=field_id)
        # Clear any existing content first.
        self.shell("input", "keyevent", "--longpress", "KEYCODE_MOVE_END")
        self.shell("input", "keyevent", *(["KEYCODE_DEL"] * 40))
        self.text(value)

    def key(self, keycode: str):
        self.shell("input", "keyevent", keycode)
        time.sleep(0.3)

    def back(self):
        self.key("KEYCODE_BACK")

    # ---------------------------------------------------------------- scans
    def scan_rfid(self, tag: str):
        self.shell("am", "broadcast", "-a", "com.rscja.scanner.action.scanner.RFID",
                   "--es", "data", tag)
        time.sleep(0.4)

    def scan_barcode(self, code: str):
        self.shell("am", "broadcast", "-a", "com.scanner.broadcast",
                   "--es", "data", code)
        time.sleep(0.4)

    # ---------------------------------------------------------------- observe
    def screenshot(self, name: str) -> Path:
        SHOTS.mkdir(exist_ok=True)
        remote = "/sdcard/shot.png"
        self.shell("screencap", "-p", remote)
        local = SHOTS / f"{name}.png"
        self.adb("pull", remote, str(local))
        return local

    def logcat_clear(self):
        self.adb("logcat", "-c")

    def logcat(self, pattern: str | None = None, lines: int = 200) -> str:
        out = self.adb("logcat", "-d", "-t", str(lines))
        if pattern:
            return "\n".join(l for l in out.splitlines() if re.search(pattern, l))
        return out
