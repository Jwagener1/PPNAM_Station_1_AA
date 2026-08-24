# Station 1 — Operator Login over MQTT (Android side)

The Android app now requires an operator login at launch, mirroring PPNAM Station 2 AA's login
(SCRAM-SHA-256 credentials or an RFID badge scan). **The Station 1 Windows app does not yet
implement the station side of these messages** — until it does, every login attempt will time out
with "Station did not respond".

The station side can be ported from the Station 2 Windows app's SCRAM handler; the Android client
(`AuthClient.kt`, `ScramCrypto.kt`) is a direct port of Station 2 AA's `ScramExchange`/`ScramCrypto`.

## Topics

All on Station 1's existing per-device namespace (see `MqttTopics.kt`):

| Request (`PPNAM/station_{n}/{deviceId}/req/…`) | Response (`…/{deviceId}/res/…`) |
|---|---|
| `scram_start_requested` | `scram_challenge` |
| `scram_proof_requested` | `operator_context` |
| `login_requested` (badge) | `operator_context` |
| `reader_logout_requested` | none required |

## Payloads

Every request carries Station 1's usual envelope: `ts` (ISO-8601 UTC) and `deviceId`
(`scanner_{n}`).

`scram_start_requested`: `username`, `clientNonce`, `purpose` (`"login"`).

`scram_challenge`: `challengeId`, `serverNonce` (combined nonce, must extend the client nonce),
`salt` (base64), `iterations`, `serverFirstMessage` (verbatim `r=…,s=…,i=…`).

`scram_proof_requested`: `challengeId`, `clientFinalWithoutProof`, `clientProof` (base64),
`purpose`.

`login_requested`: `badgeTag`.

`operator_context`: `serverSignature` (base64 — the client verifies it before trusting the
session; SCRAM proof responses only), `operatorSessionId`, `operatorId`, `displayName`, `role`,
`sessionState` (`"active"`; `"closed"` is treated as a failed login), and `allowedTabs` — the
sub-apps this operator may open. Recognised values: `"tag_assignment"`, `"bag_pairing"`.
Omitted/empty fails OPEN (both tiles enabled), matching Station 2's display-hint semantics; the
station must still authorise every request server-side.

`reader_logout_requested`: `operatorSessionId`.

## Provisional sub-app messages (final contract TBD)

The stripped-down app currently publishes these fire-and-forget requests; the message set will be
finalised together with the station side:

- `req/tag_scan` — `{ ts, deviceId, operatorSessionId, tagId }` (Tag Assignment: sent
  automatically on every RFID scan)
- `req/bag_pairing` — `{ ts, deviceId, operatorSessionId, tagId, barcode, bagWeight, bagCount,
  batchReference }` (Bag Pairing: sent when the operator submits a fully confirmed pairing)

## Rejections

A rejection is a response on the same `res/` topic with `"status": "rejected"` and a
human-readable `reason`, e.g. `{"status":"rejected","reason":"Unknown username or password"}`.
The client times out after 10 s if nothing answers.
