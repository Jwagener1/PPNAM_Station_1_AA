# Station 1 Login MQTT Contract — SUPERSEDED

This document is superseded by **contract v3.0.0**: see
[`docs/Station1_MQTT_Contract_v3.md`](docs/Station1_MQTT_Contract_v3.md)
(authoritative source: `PPNAM-Station-1-App/docs rev 2/Station1_MQTT_Contract_Rev_2.md`
in the Windows repo; shared login/session authority: Station 2 `RFID_MQTT_CONTRACT.md`,
schema 4.1).

What changed for this app in 3.0.0 (all implemented as of 2026-08-25):

- Authentication requests use the schema 4.1 envelope (`messageId`, `schemaVersion: "4.1"`,
  `deviceId`, six-fractional-digit `timestampUtc`); responses are correlated on
  `inResponseToMessageId` and branched on `accepted`/`errorCode`. Envelope/routing failures
  arrive on `res/request_rejected`.
- The SCRAM proof response is `res/scram_proof_result` (badge login still answers
  `res/operator_context`).
- `allowedTabs` values are `tag_assignment` and `offload`; a missing or empty list enables
  no workflows (fail closed).
- Tag Assignment consumes `res/tag_scan_result` (echoing `tagId`) instead of treating the
  PUBACK as success.
- Bag Pairing is replaced by the two-step Offload workflow: `offload_scan` →
  `offload_scan_result` (bagWeight/bagCount/batchReference prefill) → operator edit/confirm →
  `offload_confirm` → `offload_confirm_result`.
- A workflow rejection with `AUTHENTICATION_REQUIRED`/`OPERATOR_SESSION_INVALID` clears the
  local session and returns the operator to login (§8 re-authentication).
- *(3.1.0)* Every matched `offload_scan_result` carries four flat document fields
  (`documentType`, `documentNumber`, `palletsScanned`, `palletsExpected`) resolved from the
  tag+barcode lookup — never scanner-side state. The scanner repeats the reference on that
  pallet's `offload_confirm` (whose accepted result returns post-commit progress) and, via
  the "Are you done?" prompt, on `offload_complete` with `status: short/complete/over` to
  close the document (§6.4).
