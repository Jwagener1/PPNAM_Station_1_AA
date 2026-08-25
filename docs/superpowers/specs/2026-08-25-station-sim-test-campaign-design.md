# Station 1 Backend Simulator (v3.1.0) + Full App Test Campaign — Design

Date: 2026-08-25
Status: Approved (user, this date)
Contract authority: `docs/Station1_MQTT_Contract_v3.md` (v3.1.0)

## Goal

A Python simulator standing in for the Station 1 Windows backend, faithful to MQTT
contract v3.1.0, plus an adb-driven test harness — used to exercise every section of the
Android scanner app to a 100% pass test matrix, iterating app fixes until flawless.

## Why a new simulator

`tools/mqtt_simulator.py` implements the retired 2.x receiving contract (§9): old message
families, configured scanner ids, no authentication. v3.1.0 needs server-side SCRAM,
session enforcement, the new workflows, and document resolution. The old file stays as
reference; the new simulator is `tools/station_sim.py` + `tools/simlib/`.

## Components

### 1. `tools/simlib/` (pure logic, pytest-covered, no MQTT imports)

- **`scram.py`** — server side of SCRAM-SHA-256 per contract §4.3: verifier creation
  (PBKDF2-HMAC-SHA-256 → clientKey/storedKey/serverKey), challenge issue (combined nonce,
  base64 salt, iterations, `serverFirstMessage`, 60 s one-use expiry), proof validation
  (reconstruct `authMessage`, recover clientKey = proof XOR clientSignature, compare
  SHA-256(clientKey) to storedKey), `serverSignature` computation. Username SCRAM
  escaping (`=`→`=3D`, `,`→`=2C`), NFKC password normalization.
- **`envelope.py`** — §4.1 auth envelope validation: topic shape, requestType/messageId/
  deviceId/correlationKey limits, `schemaVersion == "4.1"`, six-fractional-digit UTC
  timestamp format with staleness (15 min) and future skew (2 min) windows, duplicate
  JSON property rejection (case-insensitive, any depth), sensitive-name rejection
  (`password`, `managerPassword` → `plaintext_credentials_forbidden`). Returns either a
  parsed request or an auth error code.
- **`world.py`** — in-memory business state + workflow handlers as pure functions
  (payload dict in → (response suffix, payload dict) out):
  - Operators (from `tools/sim_operators.json`): username, password (verifier derived at
    startup), badge tag, role, `allowedTabs`. Seed: `op.both` (both tabs), `op.tag`
    (tag_assignment only), `op.off` (offload only), plus a badge-only operator.
  - Operator sessions: id, device binding, expiry; validation yields
    `AUTHENTICATION_REQUIRED` / `OPERATOR_SESSION_INVALID` / `ACTION_NOT_ALLOWED`.
  - Tag Assignment world: open tag pool; `tag_scan` assigns (idempotent per §5); fixture
    tag pre-assigned to another scanner → `TAG_ALREADY_IN_USE`; unknown tag →
    `TAG_UNKNOWN`.
  - Offload world: documents (`purchase_order` PO-000123 expecting 12; `stock_transfer`
    ST-000077 expecting 3; one closed document) and pallets (tag+barcode pairs with
    prefill `bagWeight`/`bagCount`/`batchReference`). Fixtures reach every §7 code:
    `PAIR_MISMATCH`, `BARCODE_NOT_FOUND`, `TAG_ALREADY_OFFLOADED`, `DOCUMENT_UNKNOWN`,
    `DOCUMENT_REQUIRED`, `DOCUMENT_MISMATCH`, `INVALID_BAG_WEIGHT`, `INVALID_BAG_COUNT`,
    `BATCH_REFERENCE_REQUIRED`. Confirm is idempotent on identical replay; conflicting
    values → `TAG_ALREADY_OFFLOADED`; accepted confirm carries post-commit progress.
    `offload_complete` validates status ∈ {short, complete, over}, closes the document,
    idempotent replay.
  - Replay store per §4.6: `(deviceId, requestType, messageId)` + raw-body hash; same
    identity + same body → replay stored result; changed body → `message_id_reused`.
- **`faults.py`** — one-shot fault injection: `fail-next <errorCode|kind>`, forced
  timeouts (swallow a request), station offline/online, `expire-session`.

### 2. `tools/station_sim.py` (MQTT shell)

paho-mqtt over TLS websockets to `mqtt.sysone.co.za:443` (same defaults as the app);
retained presence + LWT on `PPNAM/station_1` (QoS 2); subscribes
`PPNAM/station_1/+` and `PPNAM/station_1/+/req/+`; auth responses on
`.../res/{type}` and envelope failures on `.../res/request_rejected` (QoS 1); workflow
responses per §5-6. Control channel: MQTT topic `PPNAM/sim_control/station_1/cmd`
(commands as JSON; state/acks echoed on `.../out`) so test scripts drive faults
non-interactively; stdin CLI kept for manual use.

### 3. `tools/test_campaign/`

adb-driven scripts (Python): install/launch app, drive UI (`input tap`/`input text`
against dumped view coordinates), inject scans as broadcasts —
RFID: `am broadcast -a com.rscja.scanner.action.scanner.RFID --es data <tag>`;
barcode: `am broadcast -a com.scanner.broadcast --es data <code>` — and assert on the
simulator's observed traffic (control channel `state`), logcat, and screenshots.
Device: physical C72 via adb.

### 4. `docs/TEST_MATRIX.md`

Functional coverage matrix from contract §12 (app-side items) plus every app section:

1. Provisioning/Settings: PIN gate (wrong PIN, lockout), broker fields validation, save →
   reconnect, credential persistence across restart, diagnostics pills.
2. Login: SCRAM success (both/tag-only/offload-only operators), wrong password, unknown
   user, server-signature validation, challenge expiry, timeout, logout, session expiry
   mid-workflow, badge login if the UI exposes it.
3. Workflow selection: exact `allowedTabs` gating; empty/missing list fails closed.
4. Tag Assignment: accepted scan, `TAG_UNKNOWN`, `TAG_ALREADY_IN_USE`, re-scan
   idempotency, pending state + 10 s timeout, session rejection → login.
5. Offload: matched scan with prefill+document fields, each scan error code, edit values,
   confirm accepted (edited and unchanged), post-commit progress display, idempotent
   confirm, conflicting confirm, each confirm error code, done-prompt →
   short/complete/over each accepted, `INVALID_PAYLOAD`, completion idempotency, return
   to scanning.
6. Presence/reconnect: station offline pill, broker drop/reconnect, retained presence
   correctness, app relaunch mid-session.

Each row: id, steps, expected (contract reference), observed, verdict. Campaign iterates
— every failure becomes an app fix (TDD) or a flagged contract ambiguity — until all rows
pass.

## Testing the simulator itself

pytest under `tools/tests/`: SCRAM vectors round-tripped against an independent
client-side derivation following §4.3's 15 steps; envelope validation table; world
handlers for every workflow rule and error code; replay semantics. The simulator judges
the app, so it is verified first.

## Out of scope

Station-side SQL/SAP behavior beyond the contract; the retired 2.x flows; Manager/Admin
scoped authorization (§4.5 — reserved, no scanner flow exists); load/perf testing.
