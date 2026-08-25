"""Business world for the Station 1 backend simulator — contract v3.1.0 §3-7.

Pure logic: dict in → (response_suffix, dict) out. The MQTT shell owns topics,
QoS, and presence. Deterministic seed data (documented in tools/tests/test_world.py)
so campaign scripts can rely on fixture ids.
"""
from __future__ import annotations

import hashlib
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone

from .envelope import ParsedRequest
from .scram import ScramServer

SESSION_TTL_SECONDS = 16 * 60 * 60
PROCESSING_MS = 5

AUTH_RESPONSE_SUFFIX = {
    "scram_start_requested": "scram_challenge",
    "scram_proof_requested": "scram_proof_result",
    "login_requested": "operator_context",
    "reader_logout_requested": "operator_context",
}

WORKFLOW_TAB = {
    "tag_scan": "tag_assignment",
    "offload_scan": "offload",
    "offload_confirm": "offload",
    "offload_complete": "offload",
}


# ---------------------------------------------------------------- seed data
@dataclass
class Operator:
    username: str
    password: str
    display_name: str
    role: str
    allowed_tabs: list[str]
    badge: str | None = None


OPERATORS = [
    Operator("op.both", "both123!", "Bongi Both", "Operator", ["tag_assignment", "offload"], badge="BADGE-001"),
    Operator("op.tag", "tag123!", "Thandi Tag", "Operator", ["tag_assignment"]),
    Operator("op.off", "off123!", "Owen Offload", "Operator", ["offload"]),
    Operator("op.none", "none123!", "Nomsa None", "Operator", []),
]


@dataclass
class Document:
    number: str
    doc_type: str
    expected: int
    scanned: int = 0
    open: bool = True


@dataclass
class Pallet:
    tag: str
    barcode: str
    doc_number: str
    bag_weight: float
    bag_count: int
    batch_reference: str
    offloaded: bool = False
    committed_values: tuple | None = None
    committed_response: dict | None = None


@dataclass
class Session:
    session_id: str
    username: str
    device_id: str
    expires_at: float
    closed: bool = False


def _seed_documents() -> dict[str, Document]:
    return {
        "PO-000123": Document("PO-000123", "purchase_order", expected=12, scanned=5),
        "ST-000077": Document("ST-000077", "stock_transfer", expected=3, scanned=0),
        "PO-CLOSED": Document("PO-CLOSED", "purchase_order", expected=2, scanned=2, open=False),
    }


def _seed_pallets() -> list[Pallet]:
    return [
        Pallet("TAG-PAL-001", "BC-001", "PO-000123", 25.0, 40, "BATCH-A"),
        Pallet("TAG-PAL-002", "BC-002", "PO-000123", 25.0, 40, "BATCH-A"),
        Pallet("TAG-PAL-003", "BC-003", "ST-000077", 50.0, 20, "BATCH-B"),
        Pallet("TAG-PAL-OFF", "BC-OFF", "PO-000123", 25.0, 40, "BATCH-A", offloaded=True),
        Pallet("TAG-PAL-NODOC", "BC-NODOC", "PO-CLOSED", 10.0, 8, "BATCH-X"),
    ]


class World:
    def __init__(self, clock=time.time, scram_iterations: int = 4096):
        self._clock = clock
        self.scram = ScramServer(clock=clock, iterations=scram_iterations)
        self.operators = {op.username: op for op in OPERATORS}
        for op in OPERATORS:
            self.scram.register(op.username, op.password)
        self.badges = {op.badge: op for op in OPERATORS if op.badge}

        self.sessions: dict[str, Session] = {}
        self.documents = _seed_documents()
        self.pallets_by_tag = {p.tag: p for p in _seed_pallets()}
        self.pallets_by_barcode = {p.barcode: p for p in self.pallets_by_tag.values()}
        self.assigned_tags: dict[str, str] = {"TAG-USED-001": "scanner_someoneelse"}
        self.completions: dict[tuple[str, str], dict] = {}
        # §4.6 replay: (deviceId, requestType, messageId) -> (body_sha256, suffix, payload)
        self._replay: dict[tuple[str, str, str], tuple[str, str, dict]] = {}
        self._fail_next: dict[str, str] = {}
        self._swallow_next: set[str] = set()

    # ---------------------------------------------------------------- clock
    def _now(self) -> float:
        return self._clock()

    def _iso6(self, epoch: float | None = None) -> str:
        dt = datetime.fromtimestamp(epoch if epoch is not None else self._now(), tz=timezone.utc)
        return dt.strftime("%Y-%m-%dT%H:%M:%S.%f") + "Z"

    def _iso3(self) -> str:
        dt = datetime.fromtimestamp(self._now(), tz=timezone.utc)
        return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"

    # ---------------------------------------------------------------- faults
    def fail_next(self, kind: str, error_code: str) -> None:
        self._fail_next[kind] = error_code

    def swallow_next(self, kind: str) -> None:
        self._swallow_next.add(kind)

    def expire_sessions(self) -> None:
        for session in self.sessions.values():
            session.expires_at = self._now() - 1

    def state(self) -> dict:
        return {
            "sessions": [
                {"id": s.session_id, "user": s.username, "device": s.device_id,
                 "closed": s.closed, "expired": s.expires_at < self._now()}
                for s in self.sessions.values()
            ],
            "documents": [
                {"number": d.number, "type": d.doc_type, "open": d.open,
                 "scanned": d.scanned, "expected": d.expected}
                for d in self.documents.values()
            ],
            "assignedTags": dict(self.assigned_tags),
            "offloaded": [p.tag for p in self.pallets_by_tag.values() if p.offloaded],
        }

    def reset(self) -> None:
        self.__init__(clock=self._clock, scram_iterations=self.scram._iterations)

    # ================================================================ auth
    def handle_auth(self, req: ParsedRequest):
        """Returns (response_suffix, payload), or None when the request is swallowed
        (forced timeout). Envelope is already validated. A swallowed request never
        enters the replay store — the client's retry executes fresh."""
        if req.request_type in self._swallow_next:
            self._swallow_next.discard(req.request_type)
            return None
        suffix = AUTH_RESPONSE_SUFFIX[req.request_type]
        key = (req.device_id, req.request_type, req.message_id)
        body_hash = hashlib.sha256(req.raw_body).hexdigest()
        stored = self._replay.get(key)
        if stored is not None:
            stored_hash, stored_suffix, stored_payload = stored
            if stored_hash == body_hash:
                return stored_suffix, dict(stored_payload)
            return suffix, self._auth_response(
                req, accepted=False, reason="Message id reused with a different body.",
                error_code="message_id_reused", next_action="retry",
            )

        handler = {
            "scram_start_requested": self._auth_scram_start,
            "scram_proof_requested": self._auth_scram_proof,
            "login_requested": self._auth_badge_login,
            "reader_logout_requested": self._auth_logout,
        }[req.request_type]
        payload = handler(req)
        self._replay[key] = (body_hash, suffix, dict(payload))
        return suffix, payload

    def _auth_response(self, req: ParsedRequest, accepted: bool, reason: str,
                       next_action: str | None = None, error_code: str | None = None,
                       session_id: str | None = None, extra: dict | None = None) -> dict:
        now = self._iso6()
        payload = {
            "messageId": f"response-{req.message_id}",
            "inResponseToMessageId": req.message_id,
            "schemaVersion": "4.1",
            "deviceId": req.device_id,
            "timestampUtc": now,
            "serverReceivedAtUtc": now,
            "serverSentAtUtc": now,
            "processingDurationMs": PROCESSING_MS,
            "accepted": accepted,
            "reason": reason,
        }
        if next_action is not None:
            payload["nextAction"] = next_action
        if error_code is not None:
            payload["errorCode"] = error_code
        if session_id is not None:
            payload["operatorSessionId"] = session_id
        if extra:
            payload.update(extra)
        return payload

    def _open_session(self, username: str, device_id: str) -> Session:
        session = Session(
            session_id=str(uuid.uuid4()),
            username=username,
            device_id=device_id,
            expires_at=self._now() + SESSION_TTL_SECONDS,
        )
        self.sessions[session.session_id] = session
        return session

    def _operator_extra(self, operator: Operator, session: Session) -> dict:
        return {
            "operatorId": f"uid-{operator.username}",
            "displayName": operator.display_name,
            "username": operator.username,
            "role": operator.role,
            "roleLabel": operator.role,
            "allowedActions": [],
            "allowedTabs": list(operator.allowed_tabs),
            "sessionState": "Active",
            "sessionExpiresAtUtc": self._iso6(session.expires_at),
        }

    def _auth_scram_start(self, req: ParsedRequest) -> dict:
        payload = req.payload
        username = payload.get("username") or ""
        client_nonce = payload.get("clientNonce") or ""
        purpose = payload.get("purpose") or ""
        challenge, err = self.scram.start(username, client_nonce, purpose)
        if err is not None:
            return self._auth_response(
                req, accepted=False, reason="SCRAM start rejected.",
                error_code=err, next_action="start_scram",
            )
        parts = dict(p.split("=", 1) for p in challenge.server_first_message.split(","))
        return self._auth_response(
            req, accepted=True, reason="SCRAM challenge issued.",
            next_action="submit_scram_proof",
            extra={
                "challengeId": challenge.challenge_id,
                "serverNonce": challenge.combined_nonce,
                "salt": parts["s"],
                "iterations": int(parts["i"]),
                "serverFirstMessage": challenge.server_first_message,
                "expiresAtUtc": self._iso6(challenge.expires_at),
            },
        )

    def _auth_scram_proof(self, req: ParsedRequest) -> dict:
        payload = req.payload
        result = self.scram.verify_proof(
            payload.get("challengeId") or "",
            payload.get("clientFinalWithoutProof") or "",
            payload.get("clientProof") or "",
            payload.get("purpose") or "",
        )
        if not result.ok:
            return self._auth_response(
                req, accepted=False, reason="SCRAM proof rejected.",
                error_code=result.error_code, next_action="restart_scram",
            )
        operator = self.operators[result.username]
        session = self._open_session(operator.username, req.device_id)
        extra = self._operator_extra(operator, session)
        extra["serverSignature"] = result.server_signature
        return self._auth_response(
            req, accepted=True, reason="SCRAM proof accepted.",
            next_action="workflow_selection", session_id=session.session_id, extra=extra,
        )

    def _auth_badge_login(self, req: ParsedRequest) -> dict:
        operator = self.badges.get(req.payload.get("badgeTag") or "")
        if operator is None:
            return self._auth_response(
                req, accepted=False, reason="Badge not recognized.",
                error_code="badge_rejected", next_action="login",
            )
        session = self._open_session(operator.username, req.device_id)
        return self._auth_response(
            req, accepted=True, reason="Badge accepted.",
            next_action="workflow_selection", session_id=session.session_id,
            extra=self._operator_extra(operator, session),
        )

    def _auth_logout(self, req: ParsedRequest) -> dict:
        session = self.sessions.get(req.payload.get("operatorSessionId") or "")
        if session is None or session.closed or session.device_id != req.device_id:
            return self._auth_response(
                req, accepted=False, reason="No active session for this device.",
                error_code="operator_session_invalid", next_action="login",
            )
        session.closed = True
        return self._auth_response(
            req, accepted=True, reason="Session closed.",
            next_action="login", session_id="",
            extra={"sessionState": "Closed"},
        )

    # ================================================================ workflow
    def handle_workflow(self, device_id: str, suffix: str, payload: dict):
        """Returns (response_suffix, payload) or None when the request is swallowed
        (forced-timeout fault)."""
        if suffix in self._swallow_next:
            self._swallow_next.discard(suffix)
            return None

        handler = {
            "tag_scan": self._wf_tag_scan,
            "offload_scan": self._wf_offload_scan,
            "offload_confirm": self._wf_offload_confirm,
            "offload_complete": self._wf_offload_complete,
        }.get(suffix)
        if handler is None:
            return None

        gate_error = self._session_gate(device_id, payload, WORKFLOW_TAB[suffix])
        if gate_error is not None:
            return handler(device_id, payload, forced_error=gate_error)

        forced = self._fail_next.pop(suffix, None)
        return handler(device_id, payload, forced_error=forced)

    def _session_gate(self, device_id: str, payload: dict, needed_tab: str) -> str | None:
        session_id = payload.get("operatorSessionId")
        if not session_id:
            return "AUTHENTICATION_REQUIRED"
        session = self.sessions.get(session_id)
        if session is None:
            return "AUTHENTICATION_REQUIRED"
        if session.closed or session.expires_at < self._now() or session.device_id != device_id:
            return "OPERATOR_SESSION_INVALID"
        operator = self.operators[session.username]
        if needed_tab not in operator.allowed_tabs:
            return "ACTION_NOT_ALLOWED"
        return None

    def _wf_base(self, device_id: str, **echo) -> dict:
        return {"ts": self._iso3(), "deviceId": device_id, **echo}

    GATE_REASONS = {
        "AUTHENTICATION_REQUIRED": "Sign in required.",
        "OPERATOR_SESSION_INVALID": "Operator session is no longer valid.",
        "ACTION_NOT_ALLOWED": "This workflow is not permitted for the signed-in operator.",
        "INTERNAL_ERROR": "Unexpected station error.",
        "DATABASE_FAILED": "Station transaction did not commit.",
    }

    def _reason_for(self, code: str) -> str:
        return self.GATE_REASONS.get(code, code.replace("_", " ").capitalize() + ".")

    # -- tag assignment ---------------------------------------------------
    def _wf_tag_scan(self, device_id: str, payload: dict, forced_error: str | None):
        tag = (payload.get("tagId") or "").strip()
        base = self._wf_base(device_id, tagId=tag)

        def fail(code):
            return "tag_scan_result", {**base, "accepted": False,
                                       "reason": self._reason_for(code), "errorCode": code}

        if forced_error:
            return fail(forced_error)
        if not tag:
            return fail("TAG_REQUIRED")
        holder = self.assigned_tags.get(tag)
        if holder is not None and holder != device_id:
            return fail("TAG_ALREADY_IN_USE")
        if holder == device_id:
            return "tag_scan_result", {**base, "accepted": True,
                                       "reason": "Tag already assigned by this scanner.",
                                       "errorCode": None}
        if not (tag.startswith("TAG-OK-") or tag.startswith("E280")):
            return fail("TAG_UNKNOWN")
        self.assigned_tags[tag] = device_id
        return "tag_scan_result", {**base, "accepted": True,
                                   "reason": "Tag assigned.", "errorCode": None}

    # -- offload scan -----------------------------------------------------
    def _wf_offload_scan(self, device_id: str, payload: dict, forced_error: str | None):
        tag = (payload.get("tagId") or "").strip()
        barcode = (payload.get("barcode") or "").strip()
        base = self._wf_base(device_id, tagId=tag, barcode=barcode)

        def fail(code):
            return "offload_scan_result", {**base, "matched": False,
                                           "reason": self._reason_for(code), "errorCode": code}

        if forced_error:
            return fail(forced_error)
        if not tag:
            return fail("TAG_REQUIRED")
        if not barcode:
            return fail("BARCODE_REQUIRED")
        pallet = self.pallets_by_tag.get(tag)
        if pallet is None:
            return fail("TAG_UNKNOWN")
        if self.pallets_by_barcode.get(barcode) is None:
            return fail("BARCODE_NOT_FOUND")
        if pallet.barcode != barcode:
            return fail("PAIR_MISMATCH")
        if pallet.offloaded:
            return fail("TAG_ALREADY_OFFLOADED")
        document = self.documents.get(pallet.doc_number)
        if document is None or not document.open:
            return fail("DOCUMENT_UNKNOWN")

        return "offload_scan_result", {
            **base, "matched": True, "reason": "Tag and barcode match.", "errorCode": None,
            "bagWeight": pallet.bag_weight,
            "bagCount": pallet.bag_count,
            "batchReference": pallet.batch_reference,
            "documentType": document.doc_type,
            "documentNumber": document.number,
            "palletsScanned": document.scanned,
            "palletsExpected": document.expected,
        }

    # -- offload confirm --------------------------------------------------
    def _wf_offload_confirm(self, device_id: str, payload: dict, forced_error: str | None):
        tag = (payload.get("tagId") or "").strip()
        barcode = (payload.get("barcode") or "").strip()
        base = self._wf_base(device_id, tagId=tag, barcode=barcode)

        def fail(code):
            return "offload_confirm_result", {**base, "accepted": False,
                                              "reason": self._reason_for(code), "errorCode": code}

        if forced_error:
            return fail(forced_error)

        doc_type = payload.get("documentType")
        doc_number = payload.get("documentNumber")
        if not doc_type or not doc_number:
            return fail("DOCUMENT_REQUIRED")
        document = self.documents.get(doc_number)
        if document is None or document.doc_type != doc_type or not document.open:
            # A closed document is treated below for the pallet's idempotent replay;
            # an unknown or closed reference on a fresh confirm does not resolve.
            if document is None or document.doc_type != doc_type:
                return fail("DOCUMENT_UNKNOWN")

        if not tag:
            return fail("TAG_REQUIRED")
        if not barcode:
            return fail("BARCODE_REQUIRED")
        pallet = self.pallets_by_tag.get(tag)
        if pallet is None:
            return fail("TAG_UNKNOWN")
        if self.pallets_by_barcode.get(barcode) is None:
            return fail("BARCODE_NOT_FOUND")
        if pallet.barcode != barcode:
            return fail("PAIR_MISMATCH")
        if pallet.doc_number != doc_number:
            return fail("DOCUMENT_MISMATCH")

        values = (payload.get("bagWeight"), payload.get("bagCount"),
                  payload.get("batchReference"))
        if pallet.offloaded:
            if pallet.committed_values == values and pallet.committed_response is not None:
                return "offload_confirm_result", dict(pallet.committed_response)
            return fail("TAG_ALREADY_OFFLOADED")

        if not document.open:
            return fail("DOCUMENT_UNKNOWN")

        bag_weight, bag_count, batch_reference = values
        if isinstance(bag_weight, bool) or not isinstance(bag_weight, (int, float)) or bag_weight <= 0:
            return fail("INVALID_BAG_WEIGHT")
        if (isinstance(bag_count, bool) or not isinstance(bag_count, (int, float))
                or bag_count <= 0 or float(bag_count) != int(bag_count)):
            return fail("INVALID_BAG_COUNT")
        if not isinstance(batch_reference, str) or not batch_reference.strip():
            return fail("BATCH_REFERENCE_REQUIRED")

        pallet.offloaded = True
        document.scanned += 1
        response = {**base, "accepted": True, "reason": "Offload recorded.",
                    "errorCode": None,
                    "palletsScanned": document.scanned,
                    "palletsExpected": document.expected}
        pallet.committed_values = values
        pallet.committed_response = dict(response)
        return "offload_confirm_result", response

    # -- offload complete -------------------------------------------------
    def _wf_offload_complete(self, device_id: str, payload: dict, forced_error: str | None):
        status = payload.get("status")
        base = self._wf_base(device_id, status=status)

        def fail(code):
            return "offload_complete_result", {**base, "accepted": False,
                                               "reason": self._reason_for(code), "errorCode": code}

        if forced_error:
            return fail(forced_error)

        doc_type = payload.get("documentType")
        doc_number = payload.get("documentNumber")
        if not doc_type or not doc_number:
            return fail("DOCUMENT_REQUIRED")
        if status not in ("short", "complete", "over"):
            return fail("INVALID_PAYLOAD")

        stored = self.completions.get((doc_number, status))
        if stored is not None:
            return "offload_complete_result", dict(stored)

        document = self.documents.get(doc_number)
        if document is None or document.doc_type != doc_type or not document.open:
            return fail("DOCUMENT_UNKNOWN")

        document.open = False
        response = {**base, "accepted": True, "reason": "Receipt closed.", "errorCode": None}
        self.completions[(doc_number, status)] = dict(response)
        return "offload_complete_result", response
