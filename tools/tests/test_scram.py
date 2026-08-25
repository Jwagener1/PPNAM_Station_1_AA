"""Server-side SCRAM-SHA-256 per contract v3.1.0 §4.3.

The client half in these tests is implemented independently, straight from the
contract's 15 numbered steps, so the server code is cross-checked against the
same derivation the Android app performs.
"""
import base64
import hashlib
import hmac
import unicodedata

import pytest

from simlib.scram import ScramServer

ITERATIONS = 4096  # low-ish for test speed; production default set by the sim


# ---------------------------------------------------------------- client side
def _client(password: str, username: str, client_nonce: str, server_first_message: str):
    """Contract §4.3 steps 1-14: returns (clientFinalWithoutProof, clientProof_b64,
    expectedServerSignature_b64)."""
    parts = dict(p.split("=", 1) for p in server_first_message.split(","))
    combined_nonce, salt_b64, iterations = parts["r"], parts["s"], int(parts["i"])

    password_bytes = unicodedata.normalize("NFKC", password).encode("utf-8")  # 1
    salt = base64.b64decode(salt_b64)  # 2
    salted = hashlib.pbkdf2_hmac("sha256", password_bytes, salt, iterations, 32)  # 3
    client_key = hmac.new(salted, b"Client Key", hashlib.sha256).digest()  # 4
    stored_key = hashlib.sha256(client_key).digest()  # 5
    escaped = username.replace("=", "=3D").replace(",", "=2C")  # 6
    client_first_bare = f"n={escaped},r={client_nonce}"  # 7
    client_final_without_proof = f"c=biws,r={combined_nonce}"  # 9
    auth_message = f"{client_first_bare},{server_first_message},{client_final_without_proof}"  # 10
    client_signature = hmac.new(stored_key, auth_message.encode(), hashlib.sha256).digest()  # 11
    proof = bytes(a ^ b for a, b in zip(client_key, client_signature))  # 12
    server_key = hmac.new(salted, b"Server Key", hashlib.sha256).digest()  # 13
    expected_server_sig = base64.b64encode(
        hmac.new(server_key, auth_message.encode(), hashlib.sha256).digest()
    ).decode()  # 14
    return client_final_without_proof, base64.b64encode(proof).decode(), expected_server_sig


# ---------------------------------------------------------------- fixtures
@pytest.fixture
def server():
    clock = {"now": 1000.0}
    srv = ScramServer(clock=lambda: clock["now"], iterations=ITERATIONS)
    srv.register("operator1", "correct horse battery staple")
    srv._test_clock = clock
    return srv


def start_ok(server, username="operator1", nonce="client-nonce-001", purpose="login"):
    challenge, err = server.start(username, nonce, purpose)
    assert err is None, f"unexpected start error {err}"
    return challenge


# ---------------------------------------------------------------- happy path
def test_valid_proof_is_accepted_and_returns_server_signature(server):
    ch = start_ok(server)
    cfwp, proof, expected_sig = _client(
        "correct horse battery staple", "operator1", "client-nonce-001", ch.server_first_message
    )
    result = server.verify_proof(ch.challenge_id, cfwp, proof, "login")
    assert result.ok
    assert result.server_signature == expected_sig


def test_challenge_carries_contract_fields(server):
    ch = start_ok(server)
    assert ch.combined_nonce.startswith("client-nonce-001")
    assert len(ch.combined_nonce) > len("client-nonce-001")
    assert "," not in ch.combined_nonce
    parts = dict(p.split("=", 1) for p in ch.server_first_message.split(","))
    assert parts["r"] == ch.combined_nonce
    assert base64.b64decode(parts["s"])  # decodable, non-empty salt
    assert int(parts["i"]) == ITERATIONS


def test_password_is_nfkc_normalized_on_both_sides(server):
    # U+FB01 LATIN SMALL LIGATURE FI normalizes to "fi": a verifier registered with
    # the ligature must accept a proof derived from the normalized form.
    server.register("op2", "ﬁxture")
    ch = start_ok(server, username="op2", nonce="n2")
    cfwp, proof, _ = _client("fixture", "op2", "n2", ch.server_first_message)
    assert server.verify_proof(ch.challenge_id, cfwp, proof, "login").ok


def test_username_with_scram_special_chars_is_escaped(server):
    server.register("we=ird,user", "pw")
    ch = start_ok(server, username="we=ird,user", nonce="n3")
    cfwp, proof, _ = _client("pw", "we=ird,user", "n3", ch.server_first_message)
    assert server.verify_proof(ch.challenge_id, cfwp, proof, "login").ok


# ---------------------------------------------------------------- rejections
def test_wrong_password_proof_is_rejected(server):
    ch = start_ok(server)
    cfwp, proof, _ = _client("wrong password", "operator1", "client-nonce-001", ch.server_first_message)
    result = server.verify_proof(ch.challenge_id, cfwp, proof, "login")
    assert not result.ok
    assert result.error_code == "scram_proof_invalid"


def test_unknown_username_fails_at_start(server):
    challenge, err = server.start("nobody", "n", "login")
    assert challenge is None
    assert err == "authentication_failed"


def test_unknown_challenge_id(server):
    result = server.verify_proof("bogus-id", "c=biws,r=x", "AAAA", "login")
    assert not result.ok
    assert result.error_code == "scram_challenge_not_found"


def test_challenge_is_one_use(server):
    ch = start_ok(server)
    cfwp, proof, _ = _client("correct horse battery staple", "operator1", "client-nonce-001", ch.server_first_message)
    assert server.verify_proof(ch.challenge_id, cfwp, proof, "login").ok
    replay = server.verify_proof(ch.challenge_id, cfwp, proof, "login")
    assert not replay.ok
    assert replay.error_code == "scram_challenge_reused"


def test_failed_proof_also_consumes_the_challenge(server):
    ch = start_ok(server)
    cfwp, bad_proof, _ = _client("wrong", "operator1", "client-nonce-001", ch.server_first_message)
    assert server.verify_proof(ch.challenge_id, cfwp, bad_proof, "login").error_code == "scram_proof_invalid"
    cfwp, good_proof, _ = _client("correct horse battery staple", "operator1", "client-nonce-001", ch.server_first_message)
    assert server.verify_proof(ch.challenge_id, cfwp, good_proof, "login").error_code == "scram_challenge_reused"


def test_challenge_expires_after_60_seconds(server):
    ch = start_ok(server)
    server._test_clock["now"] += 61
    cfwp, proof, _ = _client("correct horse battery staple", "operator1", "client-nonce-001", ch.server_first_message)
    result = server.verify_proof(ch.challenge_id, cfwp, proof, "login")
    assert not result.ok
    assert result.error_code == "scram_challenge_expired"


def test_client_final_must_repeat_the_combined_nonce(server):
    ch = start_ok(server)
    _, proof, _ = _client("correct horse battery staple", "operator1", "client-nonce-001", ch.server_first_message)
    result = server.verify_proof(ch.challenge_id, "c=biws,r=some-other-nonce", proof, "login")
    assert not result.ok
    assert result.error_code == "scram_client_final_invalid"


def test_purpose_must_match_the_challenge(server):
    ch = start_ok(server)
    cfwp, proof, _ = _client("correct horse battery staple", "operator1", "client-nonce-001", ch.server_first_message)
    result = server.verify_proof(ch.challenge_id, cfwp, proof, "manager_action")
    assert not result.ok
    assert result.error_code == "scram_purpose_invalid"


def test_start_rejects_bad_nonce(server):
    for bad in ("", "  ", "has,comma", "x" * 201):
        challenge, err = server.start("operator1", bad, "login")
        assert challenge is None
        assert err == "scram_start_invalid", f"nonce {bad!r}"


def test_start_rejects_unknown_purpose(server):
    challenge, err = server.start("operator1", "nonce", "world_domination")
    assert challenge is None
    assert err == "scram_purpose_invalid"


def test_malformed_proof_base64_is_rejected_not_crashing(server):
    ch = start_ok(server)
    cfwp = f"c=biws,r={ch.combined_nonce}"
    result = server.verify_proof(ch.challenge_id, cfwp, "!!!not-base64!!!", "login")
    assert not result.ok
    assert result.error_code == "scram_proof_invalid"
