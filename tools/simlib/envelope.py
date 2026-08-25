"""Auth envelope validation per contract v3.1.0 §4.1.

`validate_auth_request(topic, raw_body, now)` returns `(ParsedRequest, None)` or
`(None, error_code)` with the schema 4.1 lowercase error codes.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

STATION_SEGMENT = "station_1"
TIMESTAMP_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$")
REQUEST_TYPE_RE = re.compile(r"^[a-z0-9_]{1,100}$")
SENSITIVE_NAMES = {"password", "managerpassword"}

DEFAULT_MAX_AGE = timedelta(minutes=15)
DEFAULT_MAX_FUTURE = timedelta(minutes=2)


class _DuplicateProperty(Exception):
    pass


class _SensitiveProperty(Exception):
    pass


def _checking_pairs_hook(pairs):
    """Rejects duplicate property names (case-insensitive) and sensitive names,
    at any depth — json.loads applies the hook to every object in the tree."""
    seen = set()
    for key, _ in pairs:
        lowered = key.lower()
        if lowered in seen:
            raise _DuplicateProperty(key)
        seen.add(lowered)
        if lowered in SENSITIVE_NAMES:
            raise _SensitiveProperty(key)
    return dict(pairs)


def _has_control_chars(value: str) -> bool:
    return any(ord(c) < 0x20 or ord(c) == 0x7F for c in value)


@dataclass
class ParsedRequest:
    device_id: str
    request_type: str
    message_id: str
    correlation_key: str | None
    timestamp: datetime
    payload: dict
    raw_body: bytes


def validate_auth_request(
    topic: str,
    raw_body,
    now: datetime,
    max_age: timedelta = DEFAULT_MAX_AGE,
    max_future: timedelta = DEFAULT_MAX_FUTURE,
):
    """Returns (ParsedRequest, None) or (None, error_code)."""
    if isinstance(raw_body, str):
        raw_body = raw_body.encode("utf-8")

    parts = topic.split("/")
    if (
        len(parts) != 5
        or parts[0] != "PPNAM"
        or parts[1] != STATION_SEGMENT
        or parts[3] != "req"
    ):
        return None, "authentication_payload_invalid"
    topic_device, request_type = parts[2], parts[4]
    if not REQUEST_TYPE_RE.match(request_type):
        return None, "authentication_payload_invalid"

    try:
        payload = json.loads(raw_body.decode("utf-8"), object_pairs_hook=_checking_pairs_hook)
    except _DuplicateProperty:
        return None, "duplicate_json_property"
    except _SensitiveProperty:
        return None, "plaintext_credentials_forbidden"
    except Exception:
        return None, "authentication_payload_invalid"
    if not isinstance(payload, dict):
        return None, "authentication_payload_invalid"

    message_id = payload.get("messageId")
    if not message_id or not isinstance(message_id, str):
        return None, "message_id_required"
    if len(message_id) > 128 or _has_control_chars(message_id):
        return None, "authentication_payload_invalid"

    if payload.get("schemaVersion") != "4.1":
        return None, "schema_version_unsupported"

    device_id = payload.get("deviceId")
    if not device_id or not isinstance(device_id, str):
        return None, "authentication_payload_invalid"
    if (
        len(device_id) > 100
        or _has_control_chars(device_id)
        or any(c in device_id for c in " /+#\t")
    ):
        return None, "authentication_payload_invalid"
    if device_id != topic_device:
        return None, "device_id_mismatch"

    correlation_key = payload.get("correlationKey")
    if correlation_key is not None:
        if (
            not isinstance(correlation_key, str)
            or len(correlation_key) > 250
            or _has_control_chars(correlation_key)
        ):
            return None, "correlation_key_invalid"

    raw_ts = payload.get("timestampUtc")
    if not isinstance(raw_ts, str) or not TIMESTAMP_RE.match(raw_ts):
        return None, "timestamp_invalid"
    timestamp = datetime.strptime(raw_ts, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
    if timestamp < now - max_age:
        return None, "timestamp_stale"
    if timestamp > now + max_future:
        return None, "timestamp_future"

    return (
        ParsedRequest(
            device_id=device_id,
            request_type=request_type,
            message_id=message_id,
            correlation_key=correlation_key,
            timestamp=timestamp,
            payload=payload,
            raw_body=raw_body,
        ),
        None,
    )
