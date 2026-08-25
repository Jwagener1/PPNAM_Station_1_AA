"""Control-channel client for station_sim.py — used by campaign scripts.

    from simctl import SimControl
    with SimControl() as sim:
        sim.cmd("reset")
        events = sim.cmd("events", since=0)["events"]
"""
from __future__ import annotations

import json
import queue
import threading
import time
import uuid

import paho.mqtt.client as mqtt

CONTROL_CMD = "PPNAM/sim_control/station_1/cmd"
CONTROL_OUT = "PPNAM/sim_control/station_1/out"


class SimControl:
    def __init__(self, host="mqtt.sysone.co.za", port=443, username="admin",
                 password="admin", tls=True, websocket=True, timeout=10.0):
        self.timeout = timeout
        self._replies: queue.Queue = queue.Queue()
        self._connected = threading.Event()
        self.client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"SimCtl_{uuid.uuid4().hex[:8]}",
            transport="websockets" if websocket else "tcp",
            protocol=mqtt.MQTTv311,
        )
        self.client.username_pw_set(username, password)
        if tls:
            self.client.tls_set()
        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message
        self.client.connect(host, port, keepalive=15)
        self.client.loop_start()
        if not self._connected.wait(timeout):
            raise TimeoutError("SimControl could not connect to the broker")

    def _on_connect(self, client, userdata, flags, reason_code, properties=None):
        client.subscribe(CONTROL_OUT, qos=1)
        self._connected.set()

    def _on_message(self, client, userdata, msg):
        try:
            self._replies.put(json.loads(msg.payload.decode("utf-8")))
        except Exception:
            pass

    def cmd(self, cmd: str, **fields) -> dict:
        """Sends a command and returns the sim's reply (raises on timeout/nack)."""
        # Drain stale replies so we correlate with our own command.
        while not self._replies.empty():
            try:
                self._replies.get_nowait()
            except queue.Empty:
                break
        self.client.publish(CONTROL_CMD, json.dumps({"cmd": cmd, **fields}), qos=1)
        deadline = time.time() + self.timeout
        while time.time() < deadline:
            try:
                reply = self._replies.get(timeout=max(0.1, deadline - time.time()))
            except queue.Empty:
                break
            if reply.get("cmd") == cmd or not reply.get("ok", True):
                if not reply.get("ok"):
                    raise RuntimeError(f"sim rejected {cmd}: {reply}")
                return reply
        raise TimeoutError(f"no reply to {cmd!r} — is station_sim.py running?")

    def events(self, since: int = 0) -> list[dict]:
        return self.cmd("events", since=since)["events"]

    def wait_for(self, predicate, since: int = 0, timeout: float = 12.0, poll: float = 0.5):
        """Polls captured traffic until an event matches `predicate(event)`; returns it."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            for event in self.events(since=since):
                if predicate(event):
                    return event
            time.sleep(poll)
        return None

    def close(self):
        self.client.loop_stop()
        self.client.disconnect()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
