package com.mitas.ppnam.station1aa

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** The station answered nothing within the contract's 10-second guidance window (§5-§6). */
class WorkflowTimeout : Exception("Station did not respond")

/**
 * One workflow round trip on this device's req/res topic pair (contract v3.0.0 §5-§7).
 * Workflow responses correlate on echoed business fields (tagId, and barcode for offload) —
 * the caller supplies the match. A PUBACK is transport-only: callers keep their UI pending
 * until the station's result arrives or the timeout fires.
 */
class WorkflowClient(context: Context) {

    private val appContext = context.applicationContext
    private val mqtt = MqttManager.getInstance(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "WorkflowClient"
        private const val RESPONSE_TIMEOUT_MS = 10_000L

        private val SESSION_REJECTIONS = setOf("AUTHENTICATION_REQUIRED", "OPERATOR_SESSION_INVALID")

        /**
         * §8: a scanner re-authenticates when its session is closed or expired. These workflow
         * error codes are that signal — callers send the operator back to login instead of
         * showing a dead-end error.
         */
        fun isSessionRejection(json: JSONObject): Boolean =
            json.optString("errorCode", "") in SESSION_REJECTIONS
    }

    fun request(
        requestType: String,
        responseType: String,
        payload: JSONObject,
        matches: (JSONObject) -> Boolean,
        onResult: (Result<JSONObject>) -> Unit,
    ) {
        if (!mqtt.isConnected()) {
            mainHandler.post { onResult(Result.failure(Exception("Not connected to the station"))) }
            return
        }

        val device = DeviceIdentity.deviceId(appContext)
        val responseTopic = MqttTopics.deviceResponse(device, responseType)
        val done = AtomicBoolean(false)

        lateinit var timeoutRunnable: Runnable
        lateinit var callback: (Mqtt3Publish) -> Unit

        fun finish(result: Result<JSONObject>) {
            if (!done.compareAndSet(false, true)) return
            mainHandler.removeCallbacks(timeoutRunnable)
            mqtt.unsubscribe(responseTopic, callback)
            mainHandler.post { onResult(result) }
        }

        timeoutRunnable = Runnable { finish(Result.failure(WorkflowTimeout())) }

        callback = { publish ->
            val json = try {
                JSONObject(String(publish.payloadAsBytes, StandardCharsets.UTF_8))
            } catch (e: Exception) {
                Log.e(TAG, "Malformed $responseType payload", e)
                null
            }
            // A result for another tag/barcode (or another scanner) is not ours — keep waiting.
            if (json != null && mqtt.isRelevantToThisScanner(json) && matches(json)) {
                finish(Result.success(json))
            }
        }

        mqtt.subscribe(responseTopic, callback)
        mainHandler.postDelayed(timeoutRunnable, RESPONSE_TIMEOUT_MS)

        mqtt.publish(MqttTopics.deviceRequest(device, requestType), payload.toString()) { throwable ->
            if (throwable != null) finish(Result.failure(Exception("Could not reach the station")))
        }
    }
}
