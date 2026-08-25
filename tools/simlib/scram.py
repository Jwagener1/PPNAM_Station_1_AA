"""Server side of SCRAM-SHA-256 per contract v3.1.0 §4.3 (schema 4.1 authority).

Pure logic: no MQTT, no wall clock (the caller injects one), so the whole
exchange is unit-testable against an independent client derivation.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
import time
import unicodedata
import uuid
from dataclasses import dataclass, field

CHALLENGE_TTL_SECONDS = 60
VALID_PURPOSES = ("login", "manager_action")


def _escape_username(username: str) -> str:
    """SCRAM attribute escaping per §4.3 step 6."""
    return username.replace("=", "=3D").replace(",", "=2C")


@dataclass
class Verifier:
    salt: bytes
    iterations: int
    stored_key: bytes
    server_key: bytes


def create_verifier(password: str, iterations: int, salt: bytes | None = None) -> Verifier:
    password_bytes = unicodedata.normalize("NFKC", password).encode("utf-8")
    salt = salt or secrets.token_bytes(16)
    salted = hashlib.pbkdf2_hmac("sha256", password_bytes, salt, iterations, 32)
    client_key = hmac.new(salted, b"Client Key", hashlib.sha256).digest()
    return Verifier(
        salt=salt,
        iterations=iterations,
        stored_key=hashlib.sha256(client_key).digest(),
        server_key=hmac.new(salted, b"Server Key", hashlib.sha256).digest(),
    )


@dataclass
class Challenge:
    challenge_id: str
    username: str
    client_nonce: str
    combined_nonce: str
    server_first_message: str
    purpose: str
    expires_at: float
    used: bool = False


@dataclass
class ProofResult:
    ok: bool
    error_code: str | None = None
    server_signature: str | None = None
    username: str | None = None


class ScramServer:
    def __init__(self, clock=time.time, iterations: int = 600_000):
        self._clock = clock
        self._iterations = iterations
        self._verifiers: dict[str, Verifier] = {}
        self._challenges: dict[str, Challenge] = {}

    def register(self, username: str, password: str) -> None:
        self._verifiers[username] = create_verifier(password, self._iterations)

    def knows(self, username: str) -> bool:
        return username in self._verifiers

    # -- start -----------------------------------------------------------
    def start(self, username: str, client_nonce: str, purpose: str):
        """Returns (Challenge, None) or (None, error_code)."""
        nonce = (client_nonce or "").strip()
        if not nonce or "," in nonce or len(nonce) > 200:
            return None, "scram_start_invalid"
        if purpose not in VALID_PURPOSES:
            return None, "scram_purpose_invalid"
        verifier = self._verifiers.get(username)
        if verifier is None:
            return None, "authentication_failed"

        combined = nonce + secrets.token_urlsafe(18)
        salt_b64 = base64.b64encode(verifier.salt).decode()
        challenge = Challenge(
            challenge_id=str(uuid.uuid4()),
            username=username,
            client_nonce=nonce,
            combined_nonce=combined,
            server_first_message=f"r={combined},s={salt_b64},i={verifier.iterations}",
            purpose=purpose,
            expires_at=self._clock() + CHALLENGE_TTL_SECONDS,
        )
        self._challenges[challenge.challenge_id] = challenge
        return challenge, None

    # -- proof -----------------------------------------------------------
    def verify_proof(
        self,
        challenge_id: str,
        client_final_without_proof: str,
        client_proof_b64: str,
        purpose: str,
    ) -> ProofResult:
        challenge = self._challenges.get(challenge_id)
        if challenge is None:
            return ProofResult(False, "scram_challenge_not_found")
        if challenge.used:
            return ProofResult(False, "scram_challenge_reused")
        # One-use regardless of outcome: a failed proof burns the challenge too.
        challenge.used = True
        if self._clock() > challenge.expires_at:
            return ProofResult(False, "scram_challenge_expired")
        if purpose != challenge.purpose:
            return ProofResult(False, "scram_purpose_invalid")
        if client_final_without_proof != f"c=biws,r={challenge.combined_nonce}":
            return ProofResult(False, "scram_client_final_invalid")

        verifier = self._verifiers[challenge.username]
        try:
            proof = base64.b64decode(client_proof_b64, validate=True)
        except Exception:
            return ProofResult(False, "scram_proof_invalid")
        if len(proof) != 32:
            return ProofResult(False, "scram_proof_invalid")

        client_first_bare = f"n={_escape_username(challenge.username)},r={challenge.client_nonce}"
        auth_message = (
            f"{client_first_bare},{challenge.server_first_message},{client_final_without_proof}"
        ).encode()
        client_signature = hmac.new(verifier.stored_key, auth_message, hashlib.sha256).digest()
        recovered_client_key = bytes(a ^ b for a, b in zip(proof, client_signature))
        if not hmac.compare_digest(hashlib.sha256(recovered_client_key).digest(), verifier.stored_key):
            return ProofResult(False, "scram_proof_invalid")

        server_signature = base64.b64encode(
            hmac.new(verifier.server_key, auth_message, hashlib.sha256).digest()
        ).decode()
        return ProofResult(True, server_signature=server_signature, username=challenge.username)
