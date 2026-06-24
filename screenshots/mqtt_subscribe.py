"""
PPNAM MQTT subscriber.

Subscribes to PPNAM/# on mqtt.sysone.co.za:443 (WSS+TLS, MQTT v3, admin/admin)
and prints every message as a single line:
    EPOCHMS|TOPIC|PAYLOAD

Designed to be tailed with PowerShell (Get-Content -Wait) to capture the
`barcode` field returned in PPNAM/station_<N>/assignment_result so the
walkthrough can offload pallets with the real backend-printed barcode.

Run:
    python mqtt_subscribe.py [out.log]
Default output file is mqtt_log.txt next to this script.
"""

import json
import os
import ssl
import sys
import time
import uuid

import paho.mqtt.client as mqtt

HOST = "mqtt.sysone.co.za"
PORT = 443
USER = "admin"
PASSWORD = "admin"
PATH = "/mqtt"
TOPIC = "PPNAM/#"

OUT = (
    sys.argv[1]
    if len(sys.argv) > 1
    else os.path.join(os.path.dirname(os.path.abspath(__file__)), "mqtt_log.txt")
)


def write(line: str) -> None:
    sys.stdout.write(line + "\n")
    sys.stdout.flush()
    with open(OUT, "a", encoding="utf-8") as fh:
        fh.write(line + "\n")


def on_connect(client, userdata, flags, rc, properties=None):
    write(f"# CONNECTED rc={rc}")
    client.subscribe(TOPIC, qos=1)
    write(f"# SUBSCRIBED {TOPIC}")


def on_message(client, userdata, msg):
    try:
        payload = msg.payload.decode("utf-8", errors="replace")
    except Exception as e:
        payload = f"<decode-err {e}>"
    line = f"{int(time.time() * 1000)}|{msg.topic}|{payload}"
    write(line)

    # If it's an assignment_result, also surface the barcode on its own line
    # so a tail watcher can grep for it easily.
    if msg.topic.endswith("/assignment_result"):
        try:
            j = json.loads(payload)
            barcode = j.get("barcode") or ""
            pallet = j.get("palletCode") or ""
            status = j.get("status") or ""
            write(f"# BARCODE status={status} pallet={pallet} barcode={barcode}")
        except Exception:
            pass


def on_disconnect(client, userdata, rc, properties=None):
    write(f"# DISCONNECTED rc={rc}")


def main():
    # Truncate log on each run so the walkthrough sees a clean file.
    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write(f"# START {time.strftime('%Y-%m-%d %H:%M:%S')}\n")

    client_id = "PPNAM_Watcher_" + uuid.uuid4().hex[:8]
    client = mqtt.Client(
        client_id=client_id,
        transport="websockets",
        protocol=mqtt.MQTTv311,
    )
    client.ws_set_options(path=PATH)
    client.username_pw_set(USER, PASSWORD)

    ctx = ssl.create_default_context()
    client.tls_set_context(ctx)

    client.on_connect = on_connect
    client.on_message = on_message
    client.on_disconnect = on_disconnect

    write(f"# CONNECTING wss://{HOST}:{PORT}{PATH} as {USER} clientId={client_id}")
    client.connect(HOST, PORT, keepalive=30)
    client.loop_forever()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        write("# STOP (KeyboardInterrupt)")
