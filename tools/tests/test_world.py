"""Business world + workflow handlers per contract v3.1.0 §3-7.

Uses the deterministic seed world documented in simlib.world:
  operators  op.both / op.tag / op.off / op.none (passwords "<name>123!"),
             badge "BADGE-001" (both tabs), badges otherwise rejected
  documents  PO-000123 purchase_order, 5 of 12 already offloaded
             ST-000077 stock_transfer, 0 of 3
  pallets    TAG-PAL-001/BC-001, TAG-PAL-002/BC-002        -> PO-000123
             TAG-PAL-003/BC-003                            -> ST-000077
             TAG-PAL-OFF/BC-OFF   already offloaded
             TAG-PAL-NODOC/BC-NODOC  belongs to a closed document
  tags       TAG-OK-* / E280* assignable, TAG-USED-001 held by another scanner
"""
import json
from datetime import datetime, timezone

import pytest

from simlib.envelope import validate_auth_request
from simlib.world import World
from scram_client import client_proof

DEVICE = "scanner_5c64df8d86a8"
NOW_EPOCH = 1_777_000_000.0


@pytest.fixture
def clock():
    return {"now": NOW_EPOCH}


@pytest.fixture
def world(clock):
    return World(clock=lambda: clock["now"])


def _now_iso(clock) -> str:
    return datetime.fromtimestamp(clock["now"], tz=timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%S.%f"
    ) + "Z"


def auth_req(world, clock, request_type, message_id, **fields):
    """Builds, envelope-validates, and dispatches an auth request; returns
    (response_suffix, response_payload)."""
    payload = {
        "messageId": message_id,
        "schemaVersion": "4.1",
        "deviceId": DEVICE,
        "timestampUtc": _now_iso(clock),
        **fields,
    }
    topic = f"PPNAM/station_1/{DEVICE}/req/{request_type}"
    now = datetime.fromtimestamp(clock["now"], tz=timezone.utc)
    parsed, err = validate_auth_request(topic, json.dumps(payload), now=now)
    assert err is None, f"test bug: envelope invalid: {err}"
    return world.handle_auth(parsed)


def login(world, clock, username="op.both", password=None, device=DEVICE, tag="L"):
    password = password or username.split(".")[1] + "123!"
    suffix, challenge = auth_req(
        world, clock, "scram_start_requested", f"start-{tag}-{username}",
        username=username, clientNonce=f"nonce-{tag}", purpose="login",
    )
    assert challenge["accepted"], challenge
    cfwp, proof, expected_sig = client_proof(
        password, username, f"nonce-{tag}", challenge["serverFirstMessage"]
    )
    suffix, result = auth_req(
        world, clock, "scram_proof_requested", f"proof-{tag}-{username}",
        challengeId=challenge["challengeId"], clientFinalWithoutProof=cfwp,
        clientProof=proof, purpose="login",
    )
    assert suffix == "scram_proof_result"
    assert result["accepted"], result
    assert result["serverSignature"] == expected_sig
    return result


def wf(world, suffix, session_id, **fields):
    """Dispatches a workflow request; returns (response_suffix, payload)."""
    payload = {"ts": "2026-08-25T09:10:00.000Z", "deviceId": DEVICE, **fields}
    if session_id is not None:
        payload["operatorSessionId"] = session_id
    return world.handle_workflow(DEVICE, suffix, payload)


# ================================================================ SCRAM login
def test_scram_challenge_response_shape(world, clock):
    suffix, ch = auth_req(
        world, clock, "scram_start_requested", "start-1",
        username="op.both", clientNonce="nonce-1", purpose="login",
    )
    assert suffix == "scram_challenge"
    assert ch["accepted"]
    assert ch["messageId"] == "response-start-1"
    assert ch["inResponseToMessageId"] == "start-1"
    assert ch["schemaVersion"] == "4.1"
    assert ch["deviceId"] == DEVICE
    assert ch["nextAction"] == "submit_scram_proof"
    for key in ("challengeId", "serverNonce", "salt", "iterations",
                "serverFirstMessage", "expiresAtUtc"):
        assert key in ch, key


def test_full_login_returns_session_and_tabs(world, clock):
    result = login(world, clock, "op.both")
    assert result["nextAction"] == "workflow_selection"
    assert result["allowedTabs"] == ["tag_assignment", "offload"]
    assert result["operatorSessionId"]
    assert result["sessionState"] == "Active"
    assert result["role"]
    assert result["displayName"]


def test_single_tab_operators(world, clock):
    assert login(world, clock, "op.tag", tag="t")["allowedTabs"] == ["tag_assignment"]
    assert login(world, clock, "op.off", tag="o")["allowedTabs"] == ["offload"]
    assert login(world, clock, "op.none", tag="n")["allowedTabs"] == []


def test_wrong_password_rejected(world, clock):
    suffix, ch = auth_req(
        world, clock, "scram_start_requested", "start-wp",
        username="op.both", clientNonce="nonce-wp", purpose="login",
    )
    cfwp, proof, _ = client_proof("wrong", "op.both", "nonce-wp", ch["serverFirstMessage"])
    suffix, result = auth_req(
        world, clock, "scram_proof_requested", "proof-wp",
        challengeId=ch["challengeId"], clientFinalWithoutProof=cfwp,
        clientProof=proof, purpose="login",
    )
    assert not result["accepted"]
    assert result["errorCode"] == "scram_proof_invalid"
    assert "operatorSessionId" not in result or not result["operatorSessionId"]


def test_unknown_user_start_rejected(world, clock):
    suffix, result = auth_req(
        world, clock, "scram_start_requested", "start-uu",
        username="ghost", clientNonce="n", purpose="login",
    )
    assert suffix == "scram_challenge"
    assert not result["accepted"]
    assert result["errorCode"] == "authentication_failed"


# ================================================================ replay (§4.6)
def test_identical_start_replay_returns_stored_challenge(world, clock):
    fields = dict(username="op.both", clientNonce="nonce-r", purpose="login")
    _, first = auth_req(world, clock, "scram_start_requested", "start-r", **fields)
    _, replay = auth_req(world, clock, "scram_start_requested", "start-r", **fields)
    assert replay == first  # exact stored result, same challengeId


def test_same_message_id_changed_body_rejected(world, clock):
    auth_req(world, clock, "scram_start_requested", "start-c",
             username="op.both", clientNonce="nonce-c1", purpose="login")
    _, result = auth_req(world, clock, "scram_start_requested", "start-c",
                         username="op.both", clientNonce="nonce-c2", purpose="login")
    assert not result["accepted"]
    assert result["errorCode"] == "message_id_reused"


# ================================================================ badge + logout
def test_badge_login_accepted(world, clock):
    suffix, result = auth_req(world, clock, "login_requested", "badge-1",
                              badgeTag="BADGE-001")
    assert suffix == "operator_context"
    assert result["accepted"]
    assert result["operatorSessionId"]
    assert result["nextAction"] == "workflow_selection"
    assert set(result["allowedTabs"]) == {"tag_assignment", "offload"}


def test_unknown_badge_rejected(world, clock):
    suffix, result = auth_req(world, clock, "login_requested", "badge-2",
                              badgeTag="BADGE-NOPE")
    assert not result["accepted"]
    assert result["errorCode"] == "badge_rejected"
    assert result["nextAction"] == "login"


def test_logout_closes_session(world, clock):
    session = login(world, clock)["operatorSessionId"]
    suffix, result = auth_req(world, clock, "reader_logout_requested", "logout-1",
                              operatorSessionId=session)
    assert suffix == "operator_context"
    assert result["accepted"]
    assert result["operatorSessionId"] == ""
    assert result["sessionState"] == "Closed"
    assert result["nextAction"] == "login"
    # closed session no longer works for workflows
    _, tag_result = wf(world, "tag_scan", session, tagId="TAG-OK-1")
    assert tag_result["errorCode"] == "OPERATOR_SESSION_INVALID"


def test_logout_replay_is_idempotent(world, clock):
    session = login(world, clock)["operatorSessionId"]
    _, first = auth_req(world, clock, "reader_logout_requested", "logout-r",
                        operatorSessionId=session)
    _, replay = auth_req(world, clock, "reader_logout_requested", "logout-r",
                         operatorSessionId=session)
    assert replay == first


# ================================================================ session gating
def test_workflow_without_session(world, clock):
    suffix, result = wf(world, "tag_scan", None, tagId="TAG-OK-1")
    assert suffix == "tag_scan_result"
    assert result["errorCode"] == "AUTHENTICATION_REQUIRED"
    assert not result["accepted"]


def test_workflow_with_unknown_session(world, clock):
    _, result = wf(world, "tag_scan", "no-such-session", tagId="TAG-OK-1")
    assert result["errorCode"] == "AUTHENTICATION_REQUIRED"


def test_workflow_with_other_devices_session(world, clock):
    session = login(world, clock)["operatorSessionId"]
    payload = {"ts": "t", "deviceId": "scanner_other000000",
               "operatorSessionId": session, "tagId": "TAG-OK-1"}
    _, result = world.handle_workflow("scanner_other000000", "tag_scan", payload)
    assert result["errorCode"] == "OPERATOR_SESSION_INVALID"


def test_expired_session_rejected(world, clock):
    session = login(world, clock)["operatorSessionId"]
    clock["now"] += 60 * 60 * 17  # past the session expiry
    _, result = wf(world, "tag_scan", session, tagId="TAG-OK-1")
    assert result["errorCode"] == "OPERATOR_SESSION_INVALID"


def test_tab_permission_enforced(world, clock):
    off_only = login(world, clock, "op.off", tag="perm")["operatorSessionId"]
    _, result = wf(world, "tag_scan", off_only, tagId="TAG-OK-1")
    assert result["errorCode"] == "ACTION_NOT_ALLOWED"

    tag_only = login(world, clock, "op.tag", tag="perm2")["operatorSessionId"]
    _, result = wf(world, "offload_scan", tag_only, tagId="TAG-PAL-001", barcode="BC-001")
    assert result["errorCode"] == "ACTION_NOT_ALLOWED"


# ================================================================ tag assignment
def test_tag_scan_success_echoes_tag(world, clock):
    session = login(world, clock)["operatorSessionId"]
    suffix, result = wf(world, "tag_scan", session, tagId="TAG-OK-42")
    assert suffix == "tag_scan_result"
    assert result["accepted"]
    assert result["tagId"] == "TAG-OK-42"
    assert result["deviceId"] == DEVICE


def test_rescan_same_tag_is_idempotent_success(world, clock):
    session = login(world, clock)["operatorSessionId"]
    wf(world, "tag_scan", session, tagId="TAG-OK-7")
    _, again = wf(world, "tag_scan", session, tagId="TAG-OK-7")
    assert again["accepted"]


def test_tag_held_elsewhere(world, clock):
    session = login(world, clock)["operatorSessionId"]
    _, result = wf(world, "tag_scan", session, tagId="TAG-USED-001")
    assert not result["accepted"]
    assert result["errorCode"] == "TAG_ALREADY_IN_USE"


def test_unknown_tag(world, clock):
    session = login(world, clock)["operatorSessionId"]
    _, result = wf(world, "tag_scan", session, tagId="JUNK-999")
    assert result["errorCode"] == "TAG_UNKNOWN"


def test_missing_tag(world, clock):
    session = login(world, clock)["operatorSessionId"]
    _, result = wf(world, "tag_scan", session, tagId="")
    assert result["errorCode"] == "TAG_REQUIRED"


# ================================================================ offload scan
def test_offload_scan_matched_carries_prefill_and_document(world, clock):
    session = login(world, clock)["operatorSessionId"]
    suffix, r = wf(world, "offload_scan", session, tagId="TAG-PAL-001", barcode="BC-001")
    assert suffix == "offload_scan_result"
    assert r["matched"]
    assert r["tagId"] == "TAG-PAL-001" and r["barcode"] == "BC-001"
    assert r["bagWeight"] == 25.0
    assert r["bagCount"] == 40
    assert r["batchReference"] == "BATCH-A"
    assert r["documentType"] == "purchase_order"
    assert r["documentNumber"] == "PO-000123"
    assert r["palletsScanned"] == 5
    assert r["palletsExpected"] == 12


def test_offload_scan_error_paths(world, clock):
    session = login(world, clock)["operatorSessionId"]
    cases = [
        (dict(tagId="TAG-PAL-001", barcode="BC-002"), "PAIR_MISMATCH"),
        (dict(tagId="TAG-PAL-001", barcode="BC-NOPE"), "BARCODE_NOT_FOUND"),
        (dict(tagId="TAG-PAL-OFF", barcode="BC-OFF"), "TAG_ALREADY_OFFLOADED"),
        (dict(tagId="TAG-PAL-NODOC", barcode="BC-NODOC"), "DOCUMENT_UNKNOWN"),
        (dict(tagId="JUNK", barcode="BC-001"), "TAG_UNKNOWN"),
        (dict(tagId="", barcode="BC-001"), "TAG_REQUIRED"),
        (dict(tagId="TAG-PAL-001", barcode=""), "BARCODE_REQUIRED"),
    ]
    for fields, code in cases:
        _, r = wf(world, "offload_scan", session, **fields)
        assert not r["matched"], fields
        assert r["errorCode"] == code, (fields, r)
        assert "bagWeight" not in r and "documentNumber" not in r


# ================================================================ offload confirm
def confirm_fields(**overrides):
    fields = dict(
        documentType="purchase_order", documentNumber="PO-000123",
        tagId="TAG-PAL-001", barcode="BC-001",
        bagWeight=24.5, bagCount=40, batchReference="BATCH-A",
    )
    fields.update(overrides)
    return fields


def test_confirm_accepted_with_post_commit_progress(world, clock):
    session = login(world, clock)["operatorSessionId"]
    wf(world, "offload_scan", session, tagId="TAG-PAL-001", barcode="BC-001")
    suffix, r = wf(world, "offload_confirm", session, **confirm_fields())
    assert suffix == "offload_confirm_result"
    assert r["accepted"]
    assert r["palletsScanned"] == 6  # includes the just-committed pallet
    assert r["palletsExpected"] == 12


def test_identical_confirm_replay_is_idempotent(world, clock):
    session = login(world, clock)["operatorSessionId"]
    _, first = wf(world, "offload_confirm", session, **confirm_fields())
    _, replay = wf(world, "offload_confirm", session, **confirm_fields())
    assert replay["accepted"]
    assert replay["palletsScanned"] == first["palletsScanned"]  # no second count


def test_conflicting_confirm_rejected(world, clock):
    session = login(world, clock)["operatorSessionId"]
    wf(world, "offload_confirm", session, **confirm_fields())
    _, r = wf(world, "offload_confirm", session, **confirm_fields(bagWeight=99.0))
    assert not r["accepted"]
    assert r["errorCode"] == "TAG_ALREADY_OFFLOADED"


def test_confirm_validation_codes(world, clock):
    session = login(world, clock)["operatorSessionId"]
    cases = [
        (confirm_fields(documentType=None, documentNumber=None), "DOCUMENT_REQUIRED"),
        (confirm_fields(documentNumber="PO-GHOST"), "DOCUMENT_UNKNOWN"),
        (confirm_fields(tagId="TAG-PAL-003", barcode="BC-003"), "DOCUMENT_MISMATCH"),
        (confirm_fields(bagWeight=0), "INVALID_BAG_WEIGHT"),
        (confirm_fields(bagWeight=-2), "INVALID_BAG_WEIGHT"),
        (confirm_fields(bagWeight="abc"), "INVALID_BAG_WEIGHT"),
        (confirm_fields(bagCount=0), "INVALID_BAG_COUNT"),
        (confirm_fields(bagCount=2.5), "INVALID_BAG_COUNT"),
        (confirm_fields(batchReference=""), "BATCH_REFERENCE_REQUIRED"),
    ]
    for fields, code in cases:
        fields = {k: v for k, v in fields.items() if v is not None}
        _, r = wf(world, "offload_confirm", session, **fields)
        assert not r["accepted"], fields
        assert r["errorCode"] == code, (fields, r)


# ================================================================ completion
def test_complete_closes_document(world, clock):
    session = login(world, clock)["operatorSessionId"]
    suffix, r = wf(world, "offload_complete", session,
                   documentType="purchase_order", documentNumber="PO-000123",
                   status="complete")
    assert suffix == "offload_complete_result"
    assert r["accepted"]
    assert r["status"] == "complete"
    # scanning a pallet of the closed document now fails
    _, scan = wf(world, "offload_scan", session, tagId="TAG-PAL-002", barcode="BC-002")
    assert scan["errorCode"] == "DOCUMENT_UNKNOWN"


@pytest.mark.parametrize("status", ["short", "complete", "over"])
def test_each_completion_status_accepted(world, clock, status):
    session = login(world, clock)["operatorSessionId"]
    _, r = wf(world, "offload_complete", session,
              documentType="stock_transfer", documentNumber="ST-000077", status=status)
    assert r["accepted"]
    assert r["status"] == status


def test_identical_completion_replay_is_idempotent(world, clock):
    session = login(world, clock)["operatorSessionId"]
    args = dict(documentType="purchase_order", documentNumber="PO-000123", status="short")
    _, first = wf(world, "offload_complete", session, **args)
    _, replay = wf(world, "offload_complete", session, **args)
    assert replay["accepted"]


def test_completion_validation(world, clock):
    session = login(world, clock)["operatorSessionId"]
    _, r = wf(world, "offload_complete", session,
              documentType="purchase_order", documentNumber="PO-000123", status="done")
    assert r["errorCode"] == "INVALID_PAYLOAD"
    _, r = wf(world, "offload_complete", session, status="complete")
    assert r["errorCode"] == "DOCUMENT_REQUIRED"
    _, r = wf(world, "offload_complete", session,
              documentType="purchase_order", documentNumber="PO-GHOST", status="complete")
    assert r["errorCode"] == "DOCUMENT_UNKNOWN"


# ================================================================ faults
def test_fail_next_forces_one_error(world, clock):
    session = login(world, clock)["operatorSessionId"]
    world.fail_next("tag_scan", "INTERNAL_ERROR")
    _, forced = wf(world, "tag_scan", session, tagId="TAG-OK-9")
    assert forced["errorCode"] == "INTERNAL_ERROR"
    _, after = wf(world, "tag_scan", session, tagId="TAG-OK-9")
    assert after["accepted"]


def test_swallow_next_produces_no_response(world, clock):
    session = login(world, clock)["operatorSessionId"]
    world.swallow_next("tag_scan")
    assert wf(world, "tag_scan", session, tagId="TAG-OK-9") is None
    _, after = wf(world, "tag_scan", session, tagId="TAG-OK-9")
    assert after["accepted"]


def test_swallow_next_covers_auth_requests_too(world, clock):
    world.swallow_next("scram_start_requested")
    payload = {
        "messageId": "start-sw", "schemaVersion": "4.1", "deviceId": DEVICE,
        "timestampUtc": _now_iso(clock),
        "username": "op.both", "clientNonce": "nonce-sw", "purpose": "login",
    }
    topic = f"PPNAM/station_1/{DEVICE}/req/scram_start_requested"
    now = datetime.fromtimestamp(clock["now"], tz=timezone.utc)
    parsed, err = validate_auth_request(topic, json.dumps(payload), now=now)
    assert err is None
    assert world.handle_auth(parsed) is None
    # A swallowed request must not enter the replay store: the retry executes fresh.
    suffix, retry = world.handle_auth(parsed)
    assert suffix == "scram_challenge"
    assert retry["accepted"]


def test_expire_sessions_command(world, clock):
    session = login(world, clock)["operatorSessionId"]
    world.expire_sessions()
    _, r = wf(world, "tag_scan", session, tagId="TAG-OK-1")
    assert r["errorCode"] == "OPERATOR_SESSION_INVALID"
