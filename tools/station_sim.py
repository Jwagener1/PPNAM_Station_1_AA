#!/usr/bin/env python3
"""Station 1 backend simulator — MQTT contract v3.1.0.

Stands in for the Windows Station 1 backend so the Android scanner app can be
driven through every workflow. All business behavior lives in simlib/ (pytest-
covered); this file is the MQTT shell: presence, routing, and a control channel.

Usage:
    python tools/station_sim.py [--host mqtt.sysone.co.za] [--port 443]
                                [--username admin] [--password admin]
                                [--no-tls] [--no-websocket]

Control channel (for test scripts), JSON on PPNAM/sim_control/station_1/cmd:
    {"cmd": "fail-next", "kind": "tag_scan", "code": "INTERNAL_ERROR"}
    {"cmd": "swallow-next", "kind": "offload_confirm"}   # forced timeout
    {"cmd": "station", "state": "offline"}               # or "online"
    {"cmd": "expire-sessions"}
    {"cmd": "reset"}                                     # reseed the world
    {"cmd": "state"}
    {"cmd": "events", "since": 0}                        # captured traffic
Replies (and every ack) go to PPNAM/sim_control/station_1/out.

Stdin commands for manual runs: state | offline | online | reset |
fail <kind> <CODE> | quit
"""
from __future__ import annotations

import argparse
import json
import sys
import threading
import time
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

from simlib.envelope import validate_auth_request
from simlib.world import AUTH_RESPONSE_SUFFIX, WORKFLOW_TAB, World

STATION_TOPIC = "PPNAM/station_1"
CONTROL_CMD = "PPNAM/sim_control/station_1/cmd"
CONTROL_OUT = "PPNAM/sim_control/station_1/out"
AUTH_TYPES = set(AUTH_RESPONSE_SUFFIX)
WORKFLOW_TYPES = set(WORKFLOW_TAB)


def now_iso6() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f") + "Z"


class StationSim:
    def __init__(self, args):
        self.world = World()
        self.lock = threading.Lock()
        self.events: list[dict] = []  # captured req/res traffic, seq-numbered

        self.client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"StationSim_{int(time.time())}",
            transport="websockets" if args.websocket else "tcp",
            protocol=mqtt.MQTTv311,
        )
        self.client.username_pw_set(args.username, args.password)
        if args.tls:
            self.client.tls_set()
        self.client.will_set(STATION_TOPIC, "offline", qos=2, retain=True)
        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message
        self.args = args

    # ---------------------------------------------------------------- events
    def _record(self, direction: str, topic: str, payload) -> None:
        with self.lock:
            self.events.append({
                "seq": len(self.events),
                "at": now_iso6(),
                "dir": direction,
                "topic": topic,
                "payload": payload,
            })

    # ---------------------------------------------------------------- mqtt
    def connect(self):
        self.client.connect(self.args.host, self.args.port, keepalive=15)
        self.client.loop_start()

    def _on_connect(self, client, userdata, flags, reason_code, properties=None):
        print(f"[sim] connected: {reason_code}")
        client.publish(STATION_TOPIC, "online", qos=2, retain=True)
        client.subscribe([
            (f"{STATION_TOPIC}/+", 2),        # scanner presence
            (f"{STATION_TOPIC}/+/req/+", 1),  # all scanner requests
            (CONTROL_CMD, 1),
        ])

    def _publish_response(self, device_id: str, suffix: str, payload: dict) -> None:
        topic = f"{STATION_TOPIC}/{device_id}/res/{suffix}"
        body = json.dumps(payload)
        self._record("out", topic, payload)
        print(f"[sim] -> {topic}: {body}")
        self.client.publish(topic, body, qos=1)

    def _on_message(self, client, userdata, msg):
        topic = msg.topic
        if topic == CONTROL_CMD:
            self._handle_control(msg.payload)
            return

        parts = topic.split("/")
        if len(parts) == 3:  # PPNAM/station_1/{deviceId}: scanner presence
            presence = msg.payload.decode("utf-8", "replace")
            self._record("presence", topic, presence)
            print(f"[sim] presence {parts[2]}: {presence}")
            return
        if len(parts) != 5 or parts[3] != "req":
            return

        device_id, request_type = parts[2], parts[4]
        try:
            payload_preview = json.loads(msg.payload.decode("utf-8"))
        except Exception:
            payload_preview = msg.payload.decode("utf-8", "replace")
        self._record("in", topic, payload_preview)
        print(f"[sim] <- {topic}: {msg.payload.decode('utf-8', 'replace')}")

        try:
            if request_type in AUTH_TYPES:
                self._handle_auth(topic, device_id, msg.payload)
            elif request_type in WORKFLOW_TYPES:
                self._handle_workflow(device_id, request_type, payload_preview)
            else:
                self._reject(device_id, payload_preview, "authentication_request_unsupported")
        except Exception as e:
            print(f"[sim] ERROR handling {topic}: {e!r}")

    def _handle_auth(self, topic: str, device_id: str, raw_body: bytes) -> None:
        parsed, err = validate_auth_request(topic, raw_body, now=datetime.now(timezone.utc))
        if err is not None:
            try:
                body = json.loads(raw_body.decode("utf-8"))
            except Exception:
                body = {}
            self._reject(device_id, body if isinstance(body, dict) else {}, err)
            return
        with self.lock:
            suffix, response = self.world.handle_auth(parsed)
        self._publish_response(device_id, suffix, response)

    def _handle_workflow(self, device_id: str, request_type: str, payload) -> None:
        if not isinstance(payload, dict):
            payload = {}
        with self.lock:
            result = self.world.handle_workflow(device_id, request_type, payload)
        if result is None:
            print(f"[sim] swallowed {request_type} (forced timeout)")
            return
        suffix, response = result
        self._publish_response(device_id, suffix, response)

    def _reject(self, device_id: str, body: dict, error_code: str) -> None:
        message_id = body.get("messageId") if isinstance(body, dict) else None
        now = now_iso6()
        payload = {
            "messageId": f"response-{message_id}" if message_id else "response-unparseable",
            "schemaVersion": "4.1",
            "deviceId": device_id,
            "timestampUtc": now,
            "serverReceivedAtUtc": now,
            "serverSentAtUtc": now,
            "processingDurationMs": 1,
            "accepted": False,
            "errorCode": error_code,
            "reason": "Request rejected before dispatch.",
        }
        if message_id:
            payload["inResponseToMessageId"] = message_id
        self._publish_response(device_id, "request_rejected", payload)

    # ---------------------------------------------------------------- control
    def _control_reply(self, payload: dict) -> None:
        self.client.publish(CONTROL_OUT, json.dumps(payload), qos=1)

    def _handle_control(self, raw: bytes) -> None:
        try:
            command = json.loads(raw.decode("utf-8"))
            cmd = command["cmd"]
        except Exception as e:
            self._control_reply({"ok": False, "error": f"bad command: {e!r}"})
            return
        print(f"[sim] control: {command}")
        with self.lock:
            if cmd == "fail-next":
                self.world.fail_next(command["kind"], command["code"])
                reply = {"ok": True, "cmd": cmd}
            elif cmd == "swallow-next":
                self.world.swallow_next(command["kind"])
                reply = {"ok": True, "cmd": cmd}
            elif cmd == "station":
                state = command.get("state")
                if state not in ("online", "offline"):
                    reply = {"ok": False, "error": "state must be online|offline"}
                else:
                    self.client.publish(STATION_TOPIC, state, qos=2, retain=True)
                    reply = {"ok": True, "cmd": cmd, "state": state}
            elif cmd == "expire-sessions":
                self.world.expire_sessions()
                reply = {"ok": True, "cmd": cmd}
            elif cmd == "reset":
                self.world.reset()
                self.events.clear()
                reply = {"ok": True, "cmd": cmd}
            elif cmd == "state":
                reply = {"ok": True, "cmd": cmd, "state": self.world.state()}
            elif cmd == "events":
                since = int(command.get("since", 0))
                reply = {"ok": True, "cmd": cmd,
                         "events": [e for e in self.events if e["seq"] >= since]}
            else:
                reply = {"ok": False, "error": f"unknown cmd {cmd!r}"}
        self._control_reply(reply)

    # ---------------------------------------------------------------- lifecycle
    def shutdown(self):
        self.client.publish(STATION_TOPIC, "offline", qos=2, retain=True)
        time.sleep(0.4)
        self.client.loop_stop()
        self.client.disconnect()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="mqtt.sysone.co.za")
    parser.add_argument("--port", type=int, default=443)
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--no-tls", dest="tls", action="store_false")
    parser.add_argument("--no-websocket", dest="websocket", action="store_false")
    parser.add_argument("--headless", action="store_true",
                        help="no stdin CLI; run until killed (driven via the control topic)")
    args = parser.parse_args()

    sim = StationSim(args)
    sim.connect()
    print("[sim] Station 1 v3.1.0 simulator running. Ctrl+C to quit.")

    if args.headless:
        try:
            while True:
                time.sleep(3600)
        except KeyboardInterrupt:
            pass
        finally:
            sim.shutdown()
        return

    try:
        while True:
            line = input("> ").strip()
            if not line:
                continue
            if line == "quit":
                break
            elif line in ("offline", "online"):
                sim.client.publish(STATION_TOPIC, line, qos=2, retain=True)
            elif line == "state":
                print(json.dumps(sim.world.state(), indent=2))
            elif line == "reset":
                sim.world.reset()
                print("[sim] world reset")
            elif line.startswith("fail "):
                bits = line.split()
                if len(bits) == 3:
                    sim.world.fail_next(bits[1], bits[2])
                    print(f"[sim] next {bits[1]} -> {bits[2]}")
                else:
                    print("usage: fail <kind> <CODE>")
            else:
                print(f"[sim] unknown command: {line}")
    except (KeyboardInterrupt, EOFError):
        pass
    finally:
        sim.shutdown()


if __name__ == "__main__":
    sys.exit(main())
