package com.mitas.ppnam.station1

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 7677 SCRAM-SHA-256, ported verbatim from Station 2 AA (data/auth/ScramCrypto.kt) so both
 * apps authenticate identically. The password never leaves the device: what travels is a proof
 * that we know it.
 *
 * The exchange this supports:
 *
 *  1. client -> `scram_start_requested`  { username, clientNonce, purpose }
 *  2. server -> `scram_challenge`        { challengeId, serverNonce, salt, iterations, serverFirstMessage }
 *  3. client -> `scram_proof_requested`  { challengeId, clientFinalWithoutProof, clientProof }
 *  4. server -> `operator_context`       (the session, plus serverSignature to verify)
 *
 * ### Why PBKDF2 is implemented here rather than via `SecretKeyFactory`
 *
 * SCRAM is defined over the **UTF-8 bytes** of the normalized password. `PBEKeySpec` takes a
 * `char[]`, and how a JCE provider turns those chars into bytes is exactly the historical
 * PKCS#5-vs-PKCS#12 ambiguity — some providers use only the low byte of each char, which silently
 * produces a different key for any non-ASCII password while working perfectly for ASCII ones. That
 * failure mode would pass every test written with an ASCII password and then lock a real operator
 * out. HMAC-SHA-256 is the only primitive PBKDF2 needs, so deriving it here over explicit bytes
 * removes the ambiguity entirely.
 */
object ScramCrypto {

    private const val HMAC_SHA256 = "HmacSHA256"
    private const val SHA256 = "SHA-256"

    private val CLIENT_KEY = "Client Key".toByteArray(Charsets.US_ASCII)
    private val SERVER_KEY = "Server Key".toByteArray(Charsets.US_ASCII)

    /** GS2 header "n,," base64-encoded — the fixed `c=` value when channel binding is unused. */
    const val GS2_HEADER_BASE64 = "biws"

    private val secureRandom = SecureRandom()

    /**
     * A fresh client nonce. RFC 7677 requires it be unpredictable; 24 random bytes is comfortably
     * above the 128-bit floor and keeps the base64 free of padding.
     */
    fun generateClientNonce(): String = ByteArray(24)
        .also { secureRandom.nextBytes(it) }
        .let { encodeBase64(it) }

    /**
     * SCRAM username escaping (RFC 5802 §5.1): `=` and `,` are the field and attribute separators,
     * so they must be escaped before the username is placed in `n=`. Order matters — escaping `,`
     * first would then re-escape the `=` it just introduced.
     */
    fun escapeUsername(username: String): String =
        username.replace("=", "=3D").replace(",", "=2C")

    /**
     * SASLprep-lite: Unicode Normalization Form KC, matching what the station computes on its
     * side of the proof.
     */
    fun normalizePassword(password: String): ByteArray =
        Normalizer.normalize(password, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)

    /** `clientFirstBare` = `n=<escaped username>,r=<client nonce>`. */
    fun clientFirstBare(username: String, clientNonce: String): String =
        "n=${escapeUsername(username)},r=$clientNonce"

    /** `clientFinalWithoutProof` = `c=biws,r=<server nonce>`. */
    fun clientFinalWithoutProof(serverNonce: String): String =
        "c=$GS2_HEADER_BASE64,r=$serverNonce"

    /** `AuthMessage` = clientFirstBare + "," + serverFirstMessage + "," + clientFinalWithoutProof. */
    fun authMessage(
        clientFirstBare: String,
        serverFirstMessage: String,
        clientFinalWithoutProof: String,
    ): String = "$clientFirstBare,$serverFirstMessage,$clientFinalWithoutProof"

    /**
     * Computes the client proof and the server signature we expect back, in one pass.
     *
     * Both come from the same SaltedPassword, and the caller needs the expected server signature
     * to satisfy "validate the returned `serverSignature` before trusting the session" — so
     * returning them together keeps a caller from being able to skip that check by simply not
     * asking for it.
     */
    fun computeProof(
        password: String,
        saltBase64: String,
        iterations: Int,
        authMessage: String,
    ): ScramProof {
        require(iterations > 0) { "SCRAM iterations must be positive, was $iterations" }
        val salt = decodeBase64(saltBase64)
        val saltedPassword = pbkdf2(normalizePassword(password), salt, iterations)

        val clientKey = hmac(saltedPassword, CLIENT_KEY)
        val storedKey = sha256(clientKey)
        val authBytes = authMessage.toByteArray(Charsets.UTF_8)
        val clientSignature = hmac(storedKey, authBytes)
        val clientProof = xor(clientKey, clientSignature)

        val serverKey = hmac(saltedPassword, SERVER_KEY)
        val serverSignature = hmac(serverKey, authBytes)

        return ScramProof(
            clientProofBase64 = encodeBase64(clientProof),
            expectedServerSignatureBase64 = encodeBase64(serverSignature),
        )
    }

    /**
     * Constant-time comparison of the server signature. Not because a timing oracle on a signature
     * we already know is worth much, but because a short-circuiting `==` on secrets is the kind of
     * thing that gets copied to somewhere it does matter.
     */
    fun verifyServerSignature(expectedBase64: String, actualBase64: String): Boolean {
        val expected = try { decodeBase64(expectedBase64) } catch (e: Exception) { return false }
        val actual = try { decodeBase64(actualBase64) } catch (e: Exception) { return false }
        if (expected.size != actual.size || expected.isEmpty()) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].toInt() xor actual[i].toInt())
        return diff == 0
    }

    /**
     * Parses the `r=` (nonce) attribute out of a `serverFirstMessage`
     * (`r=<combined nonce>,s=<salt>,i=<iterations>`).
     *
     * RFC 5802 requires the combined nonce to start with the nonce the client sent; a server that
     * returns something else is either confused or attacking, and either way the exchange must not
     * continue. Returns null when the attribute is absent or fails that check.
     */
    fun parseServerNonce(serverFirstMessage: String, clientNonce: String): String? =
        serverFirstMessage.split(',')
            .firstOrNull { it.startsWith("r=") }
            ?.removePrefix("r=")
            ?.takeIf { it.startsWith(clientNonce) && it.length > clientNonce.length }

    // ---- primitives -------------------------------------------------------------------------

    /** PBKDF2-HMAC-SHA-256 producing one 32-byte block (dkLen == hLen, so i is always 1). */
    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256).apply { init(SecretKeySpec(password, HMAC_SHA256)) }
        // U1 = HMAC(password, salt || INT_BE(1))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val result = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in result.indices) result[j] = (result[j].toInt() xor u[j].toInt()).toByte()
        }
        return result
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC_SHA256)
            .apply { init(SecretKeySpec(key, HMAC_SHA256)) }
            .doFinal(data)

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance(SHA256).digest(data)

    private fun xor(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == b.size) { "XOR operands differ in length: ${a.size} vs ${b.size}" }
        return ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
    }

    // java.util.Base64, not android.util.Base64: android.util.Base64.DEFAULT line-wraps, which
    // would corrupt every value on the wire. java.util.Base64 is API 26 and minSdk is 26.
    private fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decodeBase64(value: String): ByteArray = Base64.getDecoder().decode(value)
}

/** The client proof to send, plus the server signature that must come back for it to be trusted. */
data class ScramProof(
    val clientProofBase64: String,
    val expectedServerSignatureBase64: String,
)
