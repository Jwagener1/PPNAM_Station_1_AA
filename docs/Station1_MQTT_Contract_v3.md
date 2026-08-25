# PPNAM Station 1 - Scanner MQTT Contract

| Item | Value |
|---|---|
| Contract version | 3.0.0 |
| Status | Normative Station 1 scanner contract |
| Last updated | 2026-08-25 |
| Target client | PPNAM Station 1 Android handheld scanners (any number; no fixed roles) |
| Topic structure | Fleet-wide namespaced structure per `C:\Dev\Clients\PPNAM\MQTT_TOPIC_STRUCTURE.md` |
| Authentication schema | `"4.1"` (shared Station 2 authority) |
| Workflow QoS | `1`, retain `false` |
| Presence | Retained `online`/`offline` + Last Will on base topic nodes, QoS 2 |

This contract defines everything a Station 1 Android scanner and the Station 1 Windows
backend exchange over MQTT: presence, schema 4.1 authentication, and the two scanner
workflows — **Tag Assignment** and **Offload**. The desktop remains authoritative for all
other receiving business state.

Version 3.0.0 replaces the 2.x receiving contract with the stripped-down scanner model:

- Scanners have **no fixed roles**. Any scanner can perform any workflow its signed-in
  operator is permitted; permissions come from the login response and are enforced on the
  scanner by what it offers and sends. The station additionally authorizes every request
  server-side.
- Device ids are **derived unique ids**, not configured reader numbers.
- All topics use the fleet-wide namespaced structure; presence and Last Will live on base
  topic nodes.
- The 2.x receiving message families (desktop-targeted assignment, staged offload,
  discrepancy gates, SAP/print flows, broadcasts) are **removed** — see Section 9.

The words **MUST**, **MUST NOT**, **SHOULD**, and **MAY** are normative.

## 1. Topic structure, subscriptions, and transport

All topics are lowercase and case-sensitive, under the fleet-wide structure
(`C:\Dev\Clients\PPNAM\MQTT_TOPIC_STRUCTURE.md`):

```text
PPNAM/station_1                                station presence (retained online/offline + LWT)
PPNAM/station_1/{deviceId}                     scanner presence (retained online/offline + LWT)
PPNAM/station_1/{deviceId}/req/{requestType}   scanner -> station request
PPNAM/station_1/{deviceId}/res/{responseType}  station -> scanner response
```

There is **no `/status` sub-topic** — presence is the retained payload on the base node
itself. `res` directly under the station (`PPNAM/station_1/res/...`) is a reserved
broadcast tree; version 3.0.0 defines no broadcast messages, and scanners MUST tolerate
(ignore) unknown messages there.

Subscriptions:

| Participant | Subscribes to | Purpose |
|---|---|---|
| Station backend | `PPNAM/station_1/+` | scanner presence |
| Station backend | `PPNAM/station_1/+/req/+` | all scanner requests (auth + workflow) |
| Scanner | `PPNAM/station_1` | station presence |
| Scanner | `PPNAM/station_1/{ownDeviceId}/res/+` | all of its responses, `request_rejected` included |

Transport rules:

| Setting | Requirement |
|---|---|
| Encoding | One UTF-8 JSON object per message. |
| Workflow QoS / retain | QoS 1, retain `false`. |
| Presence QoS / retain | QoS 2, retain `true`; raw text `online` or `offline`. |
| Timestamps | UTC ISO 8601 ending in `Z`. Authentication timestamps use exactly six fractional digits (`yyyy-MM-dd'T'HH:mm:ss.ffffff'Z'`). |
| Property names | Lower-camel case, case-sensitive as shown. |
| Topic segments | A `deviceId` or request/response type never contains `/`, `+`, or `#`; both sides reject violations loudly. |
| Broker credentials | Transport-only; configured externally, never placed in JSON payloads. |

## 2. Device identity

`deviceId` is the scanner's **derived unique id**: `scanner_` followed by the first 12 hex
characters of SHA-256 of a per-device identifier (Wi-Fi MAC, falling back to `ANDROID_ID`
where Android withholds the MAC), e.g. `scanner_5c64df8d86a8`. It is generated once on the
device, persisted, and shown in the app's Settings → Diagnostics for enrolment.

- The station MUST treat device ids as **opaque case-sensitive strings** with a `scanner_`
  prefix — never as numbers, and never as role identifiers.
- The fixed identities `scanner_1` / `scanner_2` and the Reader 1 / Reader 2 role split
  are **retired**. Station-side validation MUST NOT require a device to match a configured
  `Reader1DeviceId`/`Reader2DeviceId` pair; where deployments want an allow-list, it is an
  enrolment list of derived ids, not a role binding.
- The topic `deviceId` segment and the payload `deviceId` MUST match exactly on every
  request.
- Each physical scanner uses a unique MQTT client id (transport identity, distinct from
  `deviceId`).

## 3. Sessions and permissions

An operator signs in on the scanner (Section 4). The accepted login response carries
`allowedTabs` — the workflows this operator may use. For Station 1 the defined values are:

```json
"allowedTabs": ["tag_assignment", "offload"]
```

either value alone, or both.

- The station MUST send `allowedTabs` explicitly on every accepted login; the scanner
  enables exactly the listed workflows and treats a missing or empty list as **no
  workflows enabled** (fail closed).
- Enforcement is on the scanner: it only offers, and only sends, requests for allowed
  workflows. The station additionally rejects a workflow request whose operator session
  does not permit it (`ACTION_NOT_ALLOWED`) — scanner-side gating is UX, not security.
- Every workflow request carries the `operatorSessionId` returned by login. Requests with
  a missing, expired, closed, or other-device session are rejected
  (`AUTHENTICATION_REQUIRED` / `OPERATOR_SESSION_INVALID`).

## 4. Schema 4.1 authentication and authorization

Station 1 does not define its own login protocol: the authentication, operator-session,
replay, credential-storage, and response-correlation rules are the shared Station 2 schema
4.1 contract (source authority: Station 2 repository `RFID_MQTT_CONTRACT.md` and
`DOCS/Deployment/SCHEMA_4_1_ANDROID_BROKER_HANDOFF.md`). If wording here conflicts with
the Station 2 authority for a shared behavior, Station 2 wins; this document wins for
Station 1 workflow behavior.

Request/response pairs, on the namespaced topics of Section 1:

| Request `req/{type}` | Response `res/{type}` | Purpose |
|---|---|---|
| `scram_start_requested` | `scram_challenge` | Start password login (or a Manager/Admin scoped authorization). |
| `scram_proof_requested` | `scram_proof_result` | Prove password knowledge; receive the operator session. |
| `login_requested` | `operator_context` | Badge login. |
| `reader_logout_requested` | `operator_context` | Close this device's session. |

Envelope and routing failures are published to
`PPNAM/station_1/{deviceId}/res/request_rejected`; scanners subscribe to the full `res/+`
wildcard, never only the success suffixes.

### 4.1 Authentication request envelope

Every schema 4.1 authentication request contains:

```json
{
  "messageId": "auth-operation-id",
  "schemaVersion": "4.1",
  "deviceId": "scanner_5c64df8d86a8",
  "timestampUtc": "2026-08-25T06:00:00.000000Z",
  "correlationKey": "optional-trace-reference"
}
```

Rules:

- `operatorSessionId` is omitted before login and required for signed-in requests such as
  reader logout.
- Omit unused optional fields; normal requests do not send `null` or empty values.
- `messageId` identifies one logical operation, not a delivery attempt. Duplicate identity
  is `(deviceId, requestType, messageId)`.
- An uncertain request is retried with the identical topic and exact UTF-8 body. Do not
  reserialize the JSON, reorder properties, change whitespace, or refresh the timestamp on
  retry. Reusing the identity with a different raw body returns `message_id_reused`
  without a mutation.

Station 1 envelope validation:

| Input | Exact rule |
|---|---|
| Topic | Exactly five segments: `PPNAM/station_1/{deviceId}/req/{requestType}`. `PPNAM`, `station_1`, and `req` use this exact case. |
| `requestType` | Lowercase supported suffix, maximum 100 characters, no control characters. |
| `messageId` | Required, maximum 128 characters, no control characters. |
| `schemaVersion` | Exact case-sensitive value `"4.1"`. |
| `deviceId` | Required, maximum 100 characters, exact case-sensitive match to the topic device; no whitespace, control characters, `/`, `+`, or `#`. |
| Device acceptance | Opaque `scanner_`-prefixed id (Section 2). Optional deployment enrolment list of derived ids; no reader-role matching. |
| `correlationKey` | Optional, maximum 250 characters, no control characters. |
| `timestampUtc` | Exact format `yyyy-MM-dd'T'HH:mm:ss.ffffff'Z'`. Default accepted age 15 minutes, default future skew 2 minutes; deployment settings may adjust within server bounds. |
| JSON properties | Lower-camel names as shown. Duplicate property names (case-insensitive, any depth) are rejected. |
| Sensitive names | `password` or `managerPassword` anywhere in the JSON tree is rejected before dispatch (`plaintext_credentials_forbidden`). |

### 4.2 Authentication response envelope

Every direct schema 4.1 authentication response contains:

```json
{
  "messageId": "response-auth-operation-id",
  "inResponseToMessageId": "auth-operation-id",
  "schemaVersion": "4.1",
  "deviceId": "scanner_5c64df8d86a8",
  "operatorSessionId": "session-id-when-applicable",
  "timestampUtc": "2026-08-25T06:00:00.100000Z",
  "serverReceivedAtUtc": "2026-08-25T06:00:00.000000Z",
  "serverSentAtUtc": "2026-08-25T06:00:00.100000Z",
  "processingDurationMs": 100,
  "accepted": true,
  "reason": "SCRAM challenge issued.",
  "nextAction": "stable_scanner_action"
}
```

For a normally processed request, response `messageId` is exactly
`response-{request.messageId}`. `errorCode`, `errorMessage`, and optional result
properties are omitted when null; the Android decoder MUST tolerate absent optional
properties. The scanner branches on `accepted`, `errorCode`, and `nextAction`, correlates
with `inResponseToMessageId`, deduplicates responses by response `messageId`, and never
treats `nextAction` as authorization.

Stable `nextAction` values in 3.0.0: `submit_scram_proof`, `login`, `start_scram`,
`restart_scram`, `retry`, and `workflow_selection` (an accepted login: proceed to the
`allowedTabs`-gated workflow selection; replaces 2.x `active_receiving_sessions`, which
retires with receiving sessions). `submit_scoped_action` is reserved with Section 4.5.
Scanner behavior is driven by `nextAction`, never by parsing free-text `reason`.

### 4.3 SCRAM-SHA-256 password login

Start request on `PPNAM/station_1/{deviceId}/req/scram_start_requested`:

```json
{
  "messageId": "auth-start-001",
  "schemaVersion": "4.1",
  "deviceId": "scanner_5c64df8d86a8",
  "timestampUtc": "2026-08-25T06:00:00.000000Z",
  "username": "operator1",
  "clientNonce": "cryptographically-random-nonce",
  "purpose": "login"
}
```

The `scram_challenge` response adds `challengeId`, `serverNonce`, base64 `salt`,
`iterations`, `serverFirstMessage`, `expiresAtUtc`, and
`nextAction: "submit_scram_proof"`. A challenge expires 60 seconds after issue and is
one-use.

Proof request on `PPNAM/station_1/{deviceId}/req/scram_proof_requested`:

```json
{
  "messageId": "auth-proof-001",
  "schemaVersion": "4.1",
  "deviceId": "scanner_5c64df8d86a8",
  "timestampUtc": "2026-08-25T06:00:01.000000Z",
  "challengeId": "challenge-id",
  "clientFinalWithoutProof": "c=biws,r=combined-server-nonce",
  "clientProof": "base64-proof",
  "purpose": "login"
}
```

The `scram_proof_result` adds `serverSignature`, operator identity, role,
`allowedActions`, `allowedTabs`, `operatorSessionId`, `sessionState`,
`sessionExpiresAtUtc`, and `nextAction`. The scanner MUST validate `serverSignature`
(constant-time compare) before accepting the session.

Android derives and validates the proof exactly as follows:

1. Normalize the password with Unicode NFKC, then encode it as UTF-8.
2. Base64-decode the response `salt`.
3. Compute `saltedPassword = PBKDF2-HMAC-SHA-256(passwordBytes, saltBytes, iterations, 32 bytes)`.
4. Compute `clientKey = HMAC-SHA-256(saltedPassword, UTF8("Client Key"))`.
5. Compute `storedKey = SHA-256(clientKey)`.
6. Escape the username for SCRAM by replacing `=` with `=3D` and `,` with `=2C`.
7. Recreate `clientFirstBare = "n={escapedUsername},r={originalClientNonce}"`.
8. Use the returned `serverFirstMessage` exactly as sent.
9. Create `clientFinalWithoutProof = "c=biws,r={serverNonce}"`.
10. Create `authMessage = clientFirstBare + "," + serverFirstMessage + "," + clientFinalWithoutProof`.
11. Compute `clientSignature = HMAC-SHA-256(storedKey, UTF8(authMessage))`.
12. XOR the 32 bytes of `clientKey` and `clientSignature`, then standard-Base64 encode the result as `clientProof`.
13. Compute `serverKey = HMAC-SHA-256(saltedPassword, UTF8("Server Key"))`.
14. Compute `expectedServerSignature = Base64(HMAC-SHA-256(serverKey, UTF8(authMessage)))`.
15. After an accepted proof response, compare `expectedServerSignature` to response `serverSignature` in constant time. If it does not match, discard the response and session.

The client nonce is trimmed, contains no comma, is at most 200 characters, and comes from
a cryptographically secure random generator. The proof request MUST repeat the exact
`purpose` (and scope fields, where used) from the challenge.

Representative accepted login proof result:

```json
{
  "messageId": "response-auth-proof-001",
  "inResponseToMessageId": "auth-proof-001",
  "schemaVersion": "4.1",
  "deviceId": "scanner_5c64df8d86a8",
  "operatorSessionId": "reader-session-id",
  "timestampUtc": "2026-08-25T06:00:01.100000Z",
  "serverReceivedAtUtc": "2026-08-25T06:00:01.000000Z",
  "serverSentAtUtc": "2026-08-25T06:00:01.100000Z",
  "processingDurationMs": 100,
  "correlationKey": "challenge-id",
  "accepted": true,
  "reason": "SCRAM proof accepted.",
  "nextAction": "workflow_selection",
  "serverSignature": "base64-server-signature",
  "operatorId": "user-id",
  "displayName": "Operator One",
  "username": "operator1",
  "role": "Operator",
  "roleLabel": "Operator",
  "allowedActions": [],
  "allowedTabs": ["tag_assignment", "offload"],
  "sessionState": "Active",
  "sessionExpiresAtUtc": "2026-08-25T22:00:01.000000Z"
}
```

`allowedTabs` on a scanner login carries the Section 3 workflow values only. (Desktop tab
names from desktop sign-in do not appear on scanner logins.)

### 4.4 Badge login and reader logout

Badge login on `PPNAM/station_1/{deviceId}/req/login_requested`:

```json
{
  "messageId": "badge-login-001",
  "schemaVersion": "4.1",
  "deviceId": "scanner_5c64df8d86a8",
  "timestampUtc": "2026-08-25T06:05:00.000000Z",
  "badgeTag": "TAG-JSMITH"
}
```

The `operator_context` response contains acceptance, operator identity, role,
`allowedActions`, `allowedTabs` (Section 3 values), session state/expiry, and the new
`operatorSessionId`. The handler accepts only an active row in
`station1_operator_badges`; badges are provisioned separately from desktop users. An
accepted badge context uses `nextAction: "workflow_selection"`; a rejected one uses
`accepted: false`, `errorCode: "badge_rejected"`, and `nextAction: "login"`.

`reader_logout_requested` uses the signed-in envelope with no additional request fields.
An accepted `operator_context` logout response has `operatorSessionId: ""`,
`sessionState: "Closed"`, and `nextAction: "login"`; it closes only the session bound to
that exact device. A replay returns the stored already-closed context without closing the
session twice.

### 4.5 Manager/Admin scoped authorization (reserved)

The schema 4.1 SCRAM exchange also supports `purpose: "manager_action"` with
`actionTarget`/`managerAction`, returning a one-use, 60-second, device/target/action-bound
`authorizationToken` (stored server-side only as a hash). The mechanism is implemented at
the authentication boundary, but **version 3.0.0 defines no scanner workflow that consumes
a scoped token** — the stripped workflows in Sections 5-6 are Operator actions. The
mechanism is reserved for future privileged scanner actions; until one is defined,
scanners MUST NOT expose a Manager/Admin scanner flow.

### 4.6 Replay and idempotency (authentication)

Station 1 stores `(messageId, requestType, deviceId)`, the request-body hash, correlation
metadata, response route, and a replay-safe serialized result in
`station1_processed_mqtt_messages`:

1. New identity: validate and execute.
2. Same identity + same body while processing: return/await the same logical outcome; no
   second mutation.
3. Same identity + same body after commit: replay the stored result.
4. Same identity + changed body: reject (`message_id_reused`) and audit; do not execute.
5. A token-bearing result is exact-replayable only from its bounded memory cache; after
   restart/cache loss the same retry gets `authorization_reauthentication_required`
   without a second token or proof mutation.

## 5. Workflow: Tag Assignment

The operator opens Tag Assignment (permitted by `allowedTabs`) and scans RFID tags. The
scanner sends each scanned tag automatically; the station decides what the tag means and
answers success or failure. No document context, no product selection, no desktop-targeted
rows on the scanner.

Request on `PPNAM/station_1/{deviceId}/req/tag_scan` (sent automatically on every scan):

```json
{
  "ts": "2026-08-25T08:15:30.125Z",
  "deviceId": "scanner_5c64df8d86a8",
  "operatorSessionId": "reader-session-id",
  "tagId": "E280689400005015ABCD1234"
}
```

Response on `PPNAM/station_1/{deviceId}/res/tag_scan_result`:

```json
{
  "ts": "2026-08-25T08:15:30.180Z",
  "deviceId": "scanner_5c64df8d86a8",
  "tagId": "E280689400005015ABCD1234",
  "accepted": true,
  "reason": "Tag assigned.",
  "errorCode": null
}
```

Rules:

- The station MUST answer every `tag_scan` with a `tag_scan_result` echoing the same
  `tagId` (the scanner correlates on it). `accepted: false` carries a stable `errorCode`
  (Section 7) and a sanitized operator-readable `reason`.
- Re-scanning the same tag is a new request; the station answers each one (idempotently —
  a tag already assigned by this scan reports success, a tag assigned elsewhere reports
  `TAG_ALREADY_IN_USE`).
- The scanner SHOULD show a pending state until the result arrives and SHOULD surface a
  timeout after 10 seconds without one. A PUBACK is transport-only and never shown as
  business success.

## 6. Workflow: Offload

The operator opens Offload (permitted by `allowedTabs`), scans a pallet's RFID tag and a
barcode. The scanner sends the pair; the station validates the match and, when valid,
returns the pallet's expected packaging values — `bagWeight`, `bagCount`,
`batchReference` — as prefill. The operator may edit any of the three values, then
confirms; the scanner sends the final values (unchanged values are sent back verbatim) and
the station answers with the committed result.

### 6.1 Scan step

Request on `PPNAM/station_1/{deviceId}/req/offload_scan`:

```json
{
  "ts": "2026-08-25T09:10:00.000Z",
  "deviceId": "scanner_5c64df8d86a8",
  "operatorSessionId": "reader-session-id",
  "tagId": "E280689400005015ABCD1234",
  "barcode": "BC-000123"
}
```

Response on `PPNAM/station_1/{deviceId}/res/offload_scan_result`:

```json
{
  "ts": "2026-08-25T09:10:00.090Z",
  "deviceId": "scanner_5c64df8d86a8",
  "tagId": "E280689400005015ABCD1234",
  "barcode": "BC-000123",
  "matched": true,
  "reason": "Tag and barcode match.",
  "errorCode": null,
  "bagWeight": 25.0,
  "bagCount": 40,
  "batchReference": "BATCH-2026-0815"
}
```

- `matched: true` MUST include all three prefill values. `matched: false` carries a stable
  `errorCode` (e.g. `PAIR_MISMATCH`, `BARCODE_NOT_FOUND`, `TAG_ALREADY_OFFLOADED`) and
  omits the prefill values; the scanner returns to scanning.
- `bagWeight` is a JSON number (kilograms), `bagCount` a positive JSON integer,
  `batchReference` a string.

### 6.2 Confirm step

Request on `PPNAM/station_1/{deviceId}/req/offload_confirm`, sent after the operator
reviews/edits the values (unchanged values are repeated verbatim):

```json
{
  "ts": "2026-08-25T09:11:05.000Z",
  "deviceId": "scanner_5c64df8d86a8",
  "operatorSessionId": "reader-session-id",
  "tagId": "E280689400005015ABCD1234",
  "barcode": "BC-000123",
  "bagWeight": 24.5,
  "bagCount": 40,
  "batchReference": "BATCH-2026-0815"
}
```

Response on `PPNAM/station_1/{deviceId}/res/offload_confirm_result`:

```json
{
  "ts": "2026-08-25T09:11:05.110Z",
  "deviceId": "scanner_5c64df8d86a8",
  "tagId": "E280689400005015ABCD1234",
  "barcode": "BC-000123",
  "accepted": true,
  "reason": "Offload recorded.",
  "errorCode": null
}
```

Rules:

- The confirm is self-contained: it carries the tag, barcode, and final values, and the
  station re-validates the pair at confirm time. There is no server-side pairing context
  the scanner must keep alive between scan and confirm.
- The station MUST be idempotent on a re-sent identical confirm for an already-committed
  offload: reply `accepted: true` again without a second mutation. A confirm for a pallet
  offloaded with **different** values, or offloaded from another scan, is rejected with
  `TAG_ALREADY_OFFLOADED`.
- Value validation failures use `INVALID_BAG_WEIGHT`, `INVALID_BAG_COUNT`, or
  `BATCH_REFERENCE_REQUIRED`; the scanner keeps the operator on the edit screen.
- Timeout guidance as in Section 5: pending state, 10-second timeout, PUBACK is not
  success.

## 7. Workflow envelope and error codes

Workflow messages (Sections 5-6) use the lightweight envelope shown above: `ts`,
`deviceId`, `operatorSessionId` on requests; `ts`, `deviceId`, and the echoed correlating
fields (`tagId`, and `barcode` for offload) on responses. Workflow messages do not carry
`messageId`, `schemaVersion`, `workflowRevision`, `sessionId`, or `stateVersion` — those
belong to the schema 4.1 authentication envelope and the retired 2.x receiving contract
respectively.

Authentication error codes (lowercase) are unchanged from 2.x — the implemented schema 4.1
set: `authentication_payload_invalid`, `authentication_request_unsupported`,
`authentication_unavailable`, `duplicate_json_property`, `message_id_required`,
`message_id_reused`, `schema_version_unsupported`, `device_id_mismatch`,
`rfid_settings_unavailable`, `rfid_settings_invalid`, `rfid_device_not_configured`,
`correlation_key_invalid`, `timestamp_invalid`, `timestamp_stale`, `timestamp_future`,
`plaintext_credentials_forbidden`, `login_method_invalid`, `badge_required`,
`badge_rejected`, `scram_start_invalid`, `scram_purpose_invalid`, `scram_scope_required`,
`scram_verifier_migration_required`, `scram_challenge_not_found`,
`scram_challenge_reused`, `scram_challenge_expired`, `scram_scope_mismatch`,
`scram_client_final_invalid`, `scram_proof_invalid`, `authentication_failed`,
`permission_denied`, `operator_session_invalid`,
`authorization_reauthentication_required`. Android compares them exactly.
(`rfid_device_not_configured` now means: not on the deployment's enrolment list, where one
is configured — see Section 2.)

Workflow error codes (uppercase), the complete 3.0.0 set:

| Code | Meaning |
|---|---|
| `INVALID_PAYLOAD` | JSON/required field/type/format invalid. |
| `AUTHENTICATION_REQUIRED` | No active operator session for this device. |
| `OPERATOR_SESSION_INVALID` | Session closed, expired, inactive, or bound to another device. |
| `ACTION_NOT_ALLOWED` | The operator's `allowedTabs` does not permit this workflow. |
| `TAG_REQUIRED` | RFID value missing/invalid. |
| `TAG_UNKNOWN` | Tag does not resolve to anything actionable at this station. |
| `TAG_ALREADY_IN_USE` | Active tag binding exists elsewhere (tag assignment). |
| `TAG_ALREADY_OFFLOADED` | This pallet/tag was already offloaded (offload scan or conflicting confirm). |
| `BARCODE_REQUIRED` | Barcode missing/invalid. |
| `BARCODE_NOT_FOUND` | Barcode does not resolve to an eligible pallet. |
| `PAIR_MISMATCH` | Tag and barcode resolve to different pallets. |
| `INVALID_BAG_WEIGHT` | Bag weight is not a positive number in the allowed range. |
| `INVALID_BAG_COUNT` | Bag count is not a positive whole number in the allowed range. |
| `BATCH_REFERENCE_REQUIRED` | Batch reference missing/invalid. |
| `DATABASE_FAILED` | Station transaction did not commit; no success response exists. |
| `INTERNAL_ERROR` | Sanitized unexpected station error. |

## 8. Presence and reconnect

| Participant | Topic | On connect | Last Will |
|---|---|---|---|
| Station backend | `PPNAM/station_1` | retained `online` (QoS 2) | retained `offline` on the same topic |
| Scanner | `PPNAM/station_1/{deviceId}` | retained `online` (QoS 2) | retained `offline` on the same topic |

On graceful shutdown each participant publishes retained `offline` itself; the Last Will
covers unclean disconnects. On reconnect: the station restores its two subscription
filters; a scanner restores `PPNAM/station_1/{ownDeviceId}/res/+` and `PPNAM/station_1`,
republishes retained `online`, and re-authenticates when its session is closed or expired.
Workflow state needs no resynchronization — both workflows are stateless request/response.

## 9. Removed from the 2.x contract

The following 2.x elements are retired and MUST NOT be implemented, subscribed, or
published by 3.0.0 clients or the station:

- **Old topic layout:** un-namespaced `PPNAM/{deviceId}/...` topics, all `/status`
  sub-topics, and bare three-segment command/result topics
  (`PPNAM/{reader}/assignment_v2`, `PPNAM/station_1/assignment_result`, ...).
- **Fixed identities and roles:** configured `scanner_1`/`scanner_2` device ids, the
  Scanner 1 / Scanner 2 role split, and `Reader1DeviceId`/`Reader2DeviceId` role matching.
- **Receiving message families:** `tag_assignment_request`, `assignment` /
  `assignment_v2`, `unassign`, `reassign` and their `*_result`s; `offload_start`, staged
  `offload_v2` (`ScanTag`/`ScanLabel`/`ConfirmPallet`), `offload_result`,
  `all_offloaded`, `all_offloaded_result`, and the scanner discrepancy reports; `sap`,
  `sap_products_request`, `sap_products_selected`, `sap_products_response`,
  `all_assigned`, `print_all`, `bag_weight`, `offload` (atomic legacy).
- **Envelope machinery:** `workflowRevision`, receiving `sessionId`, `stateVersion`,
  workflow `messageId`/outbox replay, the Section 13/14 workflow error/nextAction
  catalogues beyond the sets defined here, and the Scanner 1/Scanner 2 state machines.

Desktop-side processes those flows served (product selection, pallet planning, label
printing, SAP posting, reconciliation, discrepancy handling) are desktop-only and out of
MQTT scope.

## 10. Implementation deltas (as of 2026-08-25)

What each side must change to meet 3.0.0. Neither side should treat this contract as
describing current shipped behavior until these land:

**Station 1 Windows backend:**

1. Authentication topic routing accepts only the retired four-segment
   `PPNAM/{deviceId}/req/{type}` shape (`RfidDeviceInitializer.TryProcessAuthenticationRequestAsync`)
   and replies on `PPNAM/{deviceId}/res/{type}` — must move to the Section 1 namespaced
   shape. The subscription list still contains the retired un-namespaced filters — prune
   to the two Section 1 filters.
2. Device validation (`RequireConfiguredDevice` → `Reader1DeviceId`/`Reader2DeviceId`)
   must be replaced per Section 2 (opaque derived ids; optional enrolment list).
3. `allowedTabs` on scanner logins must carry the Section 3 values
   (`tag_assignment`, `offload`) per operator permissions, and accepted logins must
   return `nextAction: "workflow_selection"` (the implemented handlers still emit the
   retired `active_receiving_sessions`).
4. New handlers: `tag_scan` → `tag_scan_result`, `offload_scan` → `offload_scan_result`,
   `offload_confirm` → `offload_confirm_result` (Sections 5-6). The 2.x receiving
   handlers and their topics retire with them.

**Station 1 Android app:**

1. Consume `tag_scan_result` (today `tag_scan` is fire-and-forget; "Sent" reflects only
   PUBACK).
2. Replace the one-shot Bag Pairing screen (operator types all three values) with the
   Section 6 two-step Offload flow: scan tag+barcode → prefill from
   `offload_scan_result` → edit/confirm → `offload_confirm`. Wire suffixes and the
   `allowedTabs` value rename from `bag_pairing` to `offload`.
3. Await `scram_proof_result` (not `operator_context`) after the SCRAM proof
   (`AuthClient.kt`), per the shared Station 2 v4.1 authority.
4. Treat missing/empty `allowedTabs` as no workflows enabled (today the app fails open
   with both tiles enabled).

## 11. Logging, redaction, and audit

For every message, diagnostic output logs direction, topic, QoS, retain, device id,
operator session id where permitted, request/response type, result/error code, and
duration. Before any diagnostic write, recursively redact sensitive properties:
`password`, `managerPassword`, `clientProof`, `serverSignature`, `authorizationToken`,
SCRAM verifier keys, broker/SAP/SQL secrets, cookies, and credentials.
`station1_processed_mqtt_messages.response_payload` never stores a raw scoped token.

## 12. Acceptance tests

- Topic/payload device matching on the five-segment namespaced topics; old-layout topics
  are not subscribed and not answered.
- Retained presence + Last Will on both base nodes; no `/status` sub-topics.
- Schema 4.1 suite unchanged: SCRAM start/proof success plus invalid, expired, used,
  replayed, and changed-body cases; plaintext credential rejection; badge login;
  replay-safe logout; secret redaction.
- Derived device ids accepted opaquely; retired fixed ids rejected only where an enrolment
  list is configured and does not include them.
- `allowedTabs` gating: scanner enables exactly the listed workflows; station rejects a
  non-permitted workflow request with `ACTION_NOT_ALLOWED`.
- `tag_scan` → `tag_scan_result` success, `TAG_UNKNOWN`, `TAG_ALREADY_IN_USE`, and
  session-rejection paths; result echoes `tagId`.
- `offload_scan` matched (with all three prefill values), `PAIR_MISMATCH`,
  `BARCODE_NOT_FOUND`, and `TAG_ALREADY_OFFLOADED` paths.
- `offload_confirm` accepted with edited and with unchanged values; identical-confirm
  idempotent replay; conflicting-values rejection; value validation codes.
- SQL failure produces no success response.

## 13. Revision history

| Version | Date | Change |
|---|---|---|
| `3.0.0` | 2026-08-25 | Stripped-down scanner contract: fleet-wide namespaced topics and base-node presence/LWT; derived unique device ids; fixed scanner roles removed in favor of login-driven `allowedTabs` (`tag_assignment`, `offload`) enforced on the scanner; new `tag_scan` and two-step `offload_scan`/`offload_confirm` workflows with backend prefill; 2.x receiving message families, broadcasts, and envelope machinery retired; SCRAM proof response confirmed as `scram_proof_result`. |
| `2.3.0` | 2026-08-25 | Android handoff release; made Station 2 schema 4.1 the shared login/session authority, added exact Android subscriptions/state/persistence rules, exact SCRAM derivation and validation behavior, corrected implemented authentication error names, documented Station 1 capability cutover status, and clarified `sapPostStatus: "Pending"`. |
| `2.2.0` | 2026-08-24 | Added duplicate-safe Scanner 2 potential shortage/over-receipt report and durable desktop Needs Action workflow. |
| `2.1.0` | 2026-08-24 | Added schema 4.1 authentication/session foundation and coordinated-migration boundary. |
| `2.0.0` | 2026-08-20 | Established fixed-row assignment, desktop Print All, staged Scanner 2 pairing, final All Pallets Offloaded gate, replay, and reconnect target. |
