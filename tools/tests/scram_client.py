"""Independent client-side SCRAM derivation per contract §4.3 — shared by tests."""
import base64
import hashlib
import hmac
import unicodedata


def client_proof(password: str, username: str, client_nonce: str, server_first_message: str):
    """Returns (clientFinalWithoutProof, clientProof_b64, expectedServerSignature_b64)."""
    parts = dict(p.split("=", 1) for p in server_first_message.split(","))
    combined_nonce, salt_b64, iterations = parts["r"], parts["s"], int(parts["i"])

    password_bytes = unicodedata.normalize("NFKC", password).encode("utf-8")
    salt = base64.b64decode(salt_b64)
    salted = hashlib.pbkdf2_hmac("sha256", password_bytes, salt, iterations, 32)
    client_key = hmac.new(salted, b"Client Key", hashlib.sha256).digest()
    stored_key = hashlib.sha256(client_key).digest()
    escaped = username.replace("=", "=3D").replace(",", "=2C")
    client_first_bare = f"n={escaped},r={client_nonce}"
    client_final_without_proof = f"c=biws,r={combined_nonce}"
    auth_message = f"{client_first_bare},{server_first_message},{client_final_without_proof}"
    client_signature = hmac.new(stored_key, auth_message.encode(), hashlib.sha256).digest()
    proof = bytes(a ^ b for a, b in zip(client_key, client_signature))
    server_key = hmac.new(salted, b"Server Key", hashlib.sha256).digest()
    expected_sig = base64.b64encode(
        hmac.new(server_key, auth_message.encode(), hashlib.sha256).digest()
    ).decode()
    return client_final_without_proof, base64.b64encode(proof).decode(), expected_sig
