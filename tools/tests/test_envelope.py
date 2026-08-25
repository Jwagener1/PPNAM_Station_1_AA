"""Auth envelope validation per contract v3.1.0 §4.1."""
import json
from datetime import datetime, timedelta, timezone

import pytest

from simlib.envelope import validate_auth_request

NOW = datetime(2026, 8, 25, 12, 0, 0, tzinfo=timezone.utc)
DEVICE = "scanner_5c64df8d86a8"
TOPIC = f"PPNAM/station_1/{DEVICE}/req/scram_start_requested"


def ts(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%S.%f") + "Z"


def body(**overrides) -> str:
    payload = {
        "messageId": "auth-start-001",
        "schemaVersion": "4.1",
        "deviceId": DEVICE,
        "timestampUtc": ts(NOW),
    }
    payload.update(overrides)
    return json.dumps({k: v for k, v in payload.items() if v is not None})


def check(topic=TOPIC, raw=None, **overrides):
    return validate_auth_request(topic, raw if raw is not None else body(**overrides), now=NOW)


# ---------------------------------------------------------------- accepted
def test_valid_request_parses():
    req, err = check()
    assert err is None
    assert req.device_id == DEVICE
    assert req.request_type == "scram_start_requested"
    assert req.message_id == "auth-start-001"
    assert req.payload["schemaVersion"] == "4.1"


def test_correlation_key_is_optional_and_carried():
    req, err = check(correlationKey="trace-1")
    assert err is None
    assert req.correlation_key == "trace-1"


# ---------------------------------------------------------------- topic shape
@pytest.mark.parametrize("bad_topic", [
    f"PPNAM/station_1/{DEVICE}/req",                       # 4 segments
    f"PPNAM/station_1/{DEVICE}/req/a/b",                   # 6 segments
    f"ppnam/station_1/{DEVICE}/req/scram_start_requested", # case
    f"PPNAM/station_2/{DEVICE}/req/scram_start_requested", # wrong station
    f"PPNAM/station_1/{DEVICE}/res/scram_start_requested", # not req
])
def test_bad_topic_shape(bad_topic):
    req, err = check(topic=bad_topic)
    assert req is None
    assert err == "authentication_payload_invalid"


def test_request_type_must_be_lowercase_and_sane():
    req, err = check(topic=f"PPNAM/station_1/{DEVICE}/req/Scram_Start")
    assert err == "authentication_payload_invalid"
    req, err = check(topic=f"PPNAM/station_1/{DEVICE}/req/{'x' * 101}")
    assert err == "authentication_payload_invalid"


# ---------------------------------------------------------------- body rules
def test_non_json_body():
    req, err = check(raw="not json{")
    assert err == "authentication_payload_invalid"


def test_missing_message_id():
    req, err = check(messageId=None)
    assert err == "message_id_required"


def test_message_id_too_long():
    req, err = check(messageId="x" * 129)
    assert err == "authentication_payload_invalid"


def test_wrong_schema_version():
    req, err = check(schemaVersion="4.0")
    assert err == "schema_version_unsupported"
    req, err = check(schemaVersion=None)
    assert err == "schema_version_unsupported"


def test_device_id_topic_mismatch_is_rejected():
    req, err = check(deviceId="scanner_ffffffffffff")
    assert err == "device_id_mismatch"


def test_device_id_case_sensitive_match():
    req, err = check(deviceId=DEVICE.upper())
    assert err == "device_id_mismatch"


def test_missing_device_id():
    req, err = check(deviceId=None)
    assert err == "authentication_payload_invalid"


def test_correlation_key_limits():
    req, err = check(correlationKey="x" * 251)
    assert err == "correlation_key_invalid"
    req, err = check(correlationKey="bad\ncontrol")
    assert err == "correlation_key_invalid"


# ---------------------------------------------------------------- timestamps
def test_timestamp_wrong_format():
    for bad in ("2026-08-25T12:00:00Z",        # no fraction
                "2026-08-25T12:00:00.000Z",    # three digits
                "2026-08-25 12:00:00.000000Z", # space
                "garbage"):
        req, err = check(timestampUtc=bad)
        assert err == "timestamp_invalid", bad


def test_timestamp_stale():
    req, err = check(timestampUtc=ts(NOW - timedelta(minutes=16)))
    assert err == "timestamp_stale"


def test_timestamp_future():
    req, err = check(timestampUtc=ts(NOW + timedelta(minutes=3)))
    assert err == "timestamp_future"


def test_timestamp_within_windows_ok():
    assert check(timestampUtc=ts(NOW - timedelta(minutes=14)))[1] is None
    assert check(timestampUtc=ts(NOW + timedelta(minutes=1)))[1] is None


# ---------------------------------------------------------------- security
def test_plaintext_password_anywhere_is_rejected():
    req, err = check(password="secret")
    assert err == "plaintext_credentials_forbidden"
    nested = body().rstrip("}") + ', "extra": {"managerPassword": "x"}}'
    req, err = check(raw=nested)
    assert err == "plaintext_credentials_forbidden"


def test_duplicate_property_names_any_depth_case_insensitive():
    dup_top = '{"messageId": "a", "MESSAGEID": "b", "schemaVersion": "4.1", ' \
              f'"deviceId": "{DEVICE}", "timestampUtc": "{ts(NOW)}"}}'
    req, err = check(raw=dup_top)
    assert err == "duplicate_json_property"

    dup_nested = body().rstrip("}") + ', "extra": {"a": 1, "A": 2}}'
    req, err = check(raw=dup_nested)
    assert err == "duplicate_json_property"
