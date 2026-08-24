package com.mitas.ppnam.station1

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Operator authentication over MQTT, mirroring Station 2 AA's AuthUseCase + ScramExchange:
 *
 *  - Credentials login runs an RFC 7677 SCRAM-SHA-256 challenge/proof — the password never goes
 *    on the wire.
 *  - Badge login carries no secret and is a single `login_requested` round trip.
 *  - Logout fires `reader_logout_requested` and clears the local session regardless of the
 *    outcome: stranding an operator logged-in because the network blipped would be worse than a
 *    server-side session that expires on its own.
 *
 * Topics follow Station 1's per-device namespace (MqttTopics), and payloads follow Station 1's
 * envelope idiom (`ts` + `deviceId` on every request). The station side must answer on the
 * device's matching `res/` topic:
 *
 *   req/scram_start_requested  -> res/scram_challenge
 *   req/scram_proof_requested  -> res/operator_context
 *   req/login_requested        -> res/operator_context
 *   req/reader_logout_requested (no response required)
 *
 * A rejection is a response with `"status": "rejected"` and a human-readable `reason`.
 */
class AuthClient(context: Context) {

    private val appContext = context.applicationContext
    private val mqtt = MqttManager.getInstance(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private companion object {
        const val TAG = "AuthClient"
        const val REQUEST_TIMEOUT_MS = 10_000L
        const val PURPOSE_LOGIN = "login"
    }

    private fun scannerInt(): Int =
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("scanner_int", 1)

    private fun stationInt(): Int =
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("station_int", 1)

    private fun deviceId(): String = "scanner_${scannerInt()}"

    fun login(username: String, password: String, onResult: (Result<OperatorSession>) -> Unit) {
        val clientNonce = ScramCrypto.generateClientNonce()

        val startPayload = envelope().apply {
            put("username", username)
            put("clientNonce", clientNonce)
            put("purpose", PURPOSE_LOGIN)
        }

        request("scram_start_requested", "scram_challenge", startPayload) { startResult ->
            val challenge = startResult.getOrElse { return@request onResult(Result.failure(it)) }

            val challengeId = challenge.optString("challengeId", "")
            val serverFirstMessage = challenge.optString("serverFirstMessage", "")
            val iterations = challenge.optInt("iterations", 0)
            if (challengeId.isBlank() || serverFirstMessage.isBlank()) {
                return@request onResult(failure("Station sent an incomplete authentication challenge"))
            }
            if (iterations <= 0) {
                return@request onResult(failure("Station sent an invalid authentication challenge"))
            }

            // RFC 5802: the combined nonce must extend the one we sent. Anything else means this
            // challenge is not an answer to our start — refuse rather than proving against it.
            val serverNonce = ScramCrypto.parseServerNonce(serverFirstMessage, clientNonce)
                ?: challenge.optString("serverNonce", "").takeIf {
                    it.startsWith(clientNonce) && it.length > clientNonce.length
                }
                ?: return@request onResult(
                    failure("Station's authentication challenge did not match this device's request")
                )

            val clientFinalWithoutProof = ScramCrypto.clientFinalWithoutProof(serverNonce)
            val proof = try {
                ScramCrypto.computeProof(
                    password = password,
                    saltBase64 = challenge.optString("salt", ""),
                    iterations = iterations,
                    authMessage = ScramCrypto.authMessage(
                        clientFirstBare = ScramCrypto.clientFirstBare(username, clientNonce),
                        serverFirstMessage = serverFirstMessage,
                        clientFinalWithoutProof = clientFinalWithoutProof,
                    ),
                )
            } catch (e: Exception) {
                // A malformed salt lands here. Deliberately not echoing the exception text, which
                // would put challenge material into a user-facing string.
                return@request onResult(failure("Station sent an unusable authentication challenge"))
            }

            val proofPayload = envelope().apply {
                put("challengeId", challengeId)
                put("clientFinalWithoutProof", clientFinalWithoutProof)
                put("clientProof", proof.clientProofBase64)
                put("purpose", PURPOSE_LOGIN)
            }

            request("scram_proof_requested", "operator_context", proofPayload) { proofResult ->
                val response = proofResult.getOrElse { return@request onResult(Result.failure(it)) }

                // Mutual authentication. Without this check anything that can answer on the
                // response topic could hand us a session we never actually proved for — which is
                // precisely what SCRAM's server signature exists to prevent.
                val serverSignature = response.optString("serverSignature", "")
                if (!ScramCrypto.verifyServerSignature(proof.expectedServerSignatureBase64, serverSignature)) {
                    return@request onResult(
                        failure("Station failed authentication verification — this response is not trusted")
                    )
                }

                onResult(buildSession(response))
            }
        }
    }

    fun loginWithBadge(badgeTag: String, onResult: (Result<OperatorSession>) -> Unit) {
        val payload = envelope().apply {
            put("badgeTag", badgeTag)
        }
        request("login_requested", "operator_context", payload) { result ->
            onResult(result.fold({ buildSession(it) }, { Result.failure(it) }))
        }
    }

    fun logout(onComplete: () -> Unit = {}) {
        val payload = envelope().apply {
            put("operatorSessionId", OperatorSessionHolder.currentSessionIdOrEmpty())
        }
        val topic = MqttTopics.deviceRequest(stationInt(), deviceId(), "reader_logout_requested")
        mqtt.publish(topic, payload.toString()) { throwable ->
            if (throwable != null) Log.w(TAG, "Logout publish failed (session cleared anyway)", throwable)
        }
        OperatorSessionHolder.clear()
        mainHandler.post { onComplete() }
    }

    private fun buildSession(response: JSONObject): Result<OperatorSession> {
        val operatorSessionId = response.optString("operatorSessionId", "")
        val sessionState = response.optString("sessionState", "")
        return when {
            operatorSessionId.isBlank() ->
                failure("Station accepted the login but issued no session")
            // Accepting an already-closed session would strand the operator in a UI that
            // rejects every action.
            sessionState.equals("closed", ignoreCase = true) ->
                failure("Station closed this session immediately")
            else -> {
                val session = OperatorSession(
                    operatorSessionId = operatorSessionId,
                    operatorId = response.optString("operatorId", ""),
                    operatorName = response.optString("displayName", ""),
                    role = response.optString("role", ""),
                    allowedActions = response.optJSONArray("allowedActions").toStringList(),
                    allowedTabs = response.optJSONArray("allowedTabs").toStringList(),
                )
                OperatorSessionHolder.set(session)
                Result.success(session)
            }
        }
    }

    /** Station 1's request envelope: every request carries `ts` and `deviceId`. */
    private fun envelope(): JSONObject = JSONObject().apply {
        put("ts", Instant.now().toString())
        put("deviceId", deviceId())
    }

    /**
     * One request/response round trip on this device's req/res topic pair, with a timeout.
     * The callback fires exactly once, on the main thread.
     */
    private fun request(
        requestType: String,
        responseType: String,
        payload: JSONObject,
        onResult: (Result<JSONObject>) -> Unit,
    ) {
        if (!mqtt.isConnected()) {
            mainHandler.post { onResult(failure("Not connected to the station")) }
            return
        }

        val station = stationInt()
        val device = deviceId()
        val responseTopic = MqttTopics.deviceResponse(station, device, responseType)
        val done = AtomicBoolean(false)

        lateinit var timeoutRunnable: Runnable
        lateinit var callback: (com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish) -> Unit

        fun finish(result: Result<JSONObject>) {
            if (!done.compareAndSet(false, true)) return
            mainHandler.removeCallbacks(timeoutRunnable)
            mqtt.unsubscribe(responseTopic, callback)
            mainHandler.post { onResult(result) }
        }

        timeoutRunnable = Runnable { finish(failure("Station did not respond")) }

        callback = { publish ->
            try {
                val json = JSONObject(String(publish.payloadAsBytes, StandardCharsets.UTF_8))
                if (mqtt.isRelevantToThisScanner(json)) {
                    val status = json.optString("status", "")
                    if (status.equals("rejected", ignoreCase = true) || status.equals("error", ignoreCase = true)) {
                        val reason = json.optString("reason", json.optString("message", ""))
                        finish(failure(reason.ifBlank { "Authentication failed" }))
                    } else {
                        finish(Result.success(json))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Malformed $responseType payload", e)
                finish(failure("Station sent an unreadable response"))
            }
        }

        mqtt.subscribe(responseTopic, callback)
        mainHandler.postDelayed(timeoutRunnable, REQUEST_TIMEOUT_MS)

        mqtt.publish(MqttTopics.deviceRequest(station, device, requestType), payload.toString()) { throwable ->
            if (throwable != null) finish(failure("Could not reach the station"))
        }
    }

    private fun failure(message: String): Result<Nothing> = Result.failure(Exception(message))
}

private fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it, "").takeIf { s -> s.isNotBlank() } }
}
