import paho.mqtt.client as mqtt
import ssl
import time

msgs = []

def on_connect(c, u, f, rc, p=None):
    print(f"Connected rc={rc}")
    c.subscribe("PPNAM/#", qos=1)
    print("Subscribed to PPNAM/#")

def on_message(c, u, m):
    text = m.payload.decode("utf-8", errors="replace")
    line = f"{m.topic}: {text[:100]}"
    msgs.append(line)
    print(f"  RX {line}")

c = mqtt.Client(client_id="spy_001", transport="websockets", protocol=mqtt.MQTTv311)
c.ws_set_options(path="/mqtt")
c.username_pw_set("admin", "admin")
ctx = ssl.create_default_context()
c.tls_set_context(ctx)
c.on_connect = on_connect
c.on_message = on_message
c.connect("mqtt.sysone.co.za", 443, 30)
c.loop_start()
print("Watching for 25 seconds...")
time.sleep(25)
c.loop_stop()
print(f"\nTotal: {len(msgs)} messages")
