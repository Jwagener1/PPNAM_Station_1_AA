# MQTT Contract v3.0.0 Implementation Plan (Android)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the Station 1 Android scanner app up to `Station1_MQTT_Contract_Rev_2.md` v3.0.0 — schema 4.1 auth envelope, `scram_proof_result`, fail-closed `allowedTabs`, a consumed `tag_scan_result`, and the two-step Offload workflow replacing Bag Pairing.

**Architecture:** Pure protocol logic goes into two new testable objects (`Schema41` for the auth envelope/correlation, `WorkflowMessages`/`OffloadPrefill`/`OffloadInput` for workflow payloads). A shared `WorkflowClient` does workflow round trips with the contract's 10-second timeout. `AuthClient` is rewired onto `Schema41`; `BagPairingActivity` is replaced by `OffloadActivity`.

**Tech Stack:** Kotlin, HiveMQ MQTT3 client, org.json, JUnit4 JVM unit tests, viewBinding, Material Components. Windows dev box — run gradle as `.\gradlew.bat`.

**Spec:** `C:\Dev\Clients\PPNAM\Windows\PPNAM-Station-1-App\docs rev 2\Station1_MQTT_Contract_Rev_2.md` (contract v3.0.0). Task 8 copies it into this repo.

## Global Constraints

- Topics: `PPNAM/station_1/{deviceId}/req/{type}` and `.../res/{type}` — already implemented by `MqttTopics.kt`; do not change topic shapes.
- Workflow QoS 1 retain false; presence QoS 2 retained on base nodes — already implemented in `MqttManager.kt`; do not change.
- Auth requests carry `messageId`, `schemaVersion: "4.1"`, `deviceId`, `timestampUtc` in exactly `yyyy-MM-dd'T'HH:mm:ss.ffffff'Z'` (six fractional digits). Omit unused optional fields — never send `null` or empty strings.
- Auth responses: correlate on `inResponseToMessageId == request messageId`, branch on `accepted` (boolean) / `errorCode`, tolerate absent optional properties, never parse free-text `reason` for control flow.
- Workflow requests carry `ts`, `deviceId`, `operatorSessionId` — **no** `messageId`/`schemaVersion`. Responses correlate on echoed `tagId` (+ `barcode` for offload).
- `allowedTabs` values are `"tag_assignment"` and `"offload"`; missing or empty list = **no workflows enabled** (fail closed).
- 10-second response timeout on every round trip; a PUBACK is never shown as business success.
- `bagWeight` goes on the wire as a JSON number, `bagCount` as a JSON integer, `batchReference` as a string.
- Package: `com.mitas.ppnam.station1`. Source root: `app\src\main\java\com\mitas\ppnam\station1\`. Test root: `app\src\test\java\com\mitas\ppnam\station1\`.
- Existing JVM unit tests must stay green: `.\gradlew.bat :app:testDebugUnitTest`.

---

### Task 1: `Schema41` auth-envelope helpers

**Files:**
- Create: `app\src\main\java\com\mitas\ppnam\station1\Schema41.kt`
- Modify: `app\build.gradle.kts` (dependencies block, line ~97)
- Test: `app\src\test\java\com\mitas\ppnam\station1\Schema41Test.kt`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces (used by Task 3):
  - `Schema41.newMessageId(prefix: String): String`
  - `Schema41.timestampUtc(instant: Instant = Instant.now()): String`
  - `Schema41.envelope(messageId: String, deviceId: String): JSONObject` — puts `messageId`, `schemaVersion`, `deviceId`, `timestampUtc`
  - `Schema41.isResponseTo(response: JSONObject, requestMessageId: String): Boolean`
  - `Schema41.isAccepted(response: JSONObject): Boolean`
  - `Schema41.rejectionMessage(response: JSONObject): String`

- [ ] **Step 1: Add org.json to the JVM test classpath**

Unit tests run against the mockable android.jar whose `org.json` classes are stubs. In `app\build.gradle.kts`, immediately after `testImplementation(libs.junit)` add:

```kotlin
    // Real org.json for JVM unit tests — the mockable android.jar only has stubs.
    testImplementation("org.json:json:20240303")
```

- [ ] **Step 2: Write the failing test**

Create `app\src\test\java\com\mitas\ppnam\station1\Schema41Test.kt`:

```kotlin
package com.mitas.ppnam.station1

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Schema 4.1 authentication envelope per Station1_MQTT_Contract v3.0.0 §4.1-§4.2: exact
 * six-fractional-digit timestamps, response correlation on inResponseToMessageId, and
 * branching on `accepted`/`errorCode` — never on free-text reason.
 */
class Schema41Test {

    @Test
    fun `timestampUtc truncates to exactly six fractional digits`() {
        assertEquals(
            "2026-08-25T06:00:00.000123Z",
            Schema41.timestampUtc(Instant.parse("2026-08-25T06:00:00.000123456Z"))
        )
    }

    @Test
    fun `timestampUtc pads a whole second to six zeros`() {
        assertEquals(
            "2026-08-25T06:00:00.000000Z",
            Schema41.timestampUtc(Instant.parse("2026-08-25T06:00:00Z"))
        )
    }

    @Test
    fun `envelope carries the four schema 41 fields`() {
        val env = Schema41.envelope("auth-start-001", "scanner_5c64df8d86a8")
        assertEquals("auth-start-001", env.getString("messageId"))
        assertEquals("4.1", env.getString("schemaVersion"))
        assertEquals("scanner_5c64df8d86a8", env.getString("deviceId"))
        // format check: 27 chars, six fractional digits, Z suffix
        val ts = env.getString("timestampUtc")
        assertTrue(ts, Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z""").matches(ts))
    }

    @Test
    fun `newMessageId is prefixed and unique per call`() {
        val a = Schema41.newMessageId("auth-start")
        val b = Schema41.newMessageId("auth-start")
        assertTrue(a.startsWith("auth-start-"))
        assertNotEquals(a, b)
        assertTrue("must fit the contract's 128-char messageId cap", a.length <= 128)
    }

    @Test
    fun `isResponseTo matches only the request message id`() {
        val response = JSONObject().put("inResponseToMessageId", "auth-start-001")
        assertTrue(Schema41.isResponseTo(response, "auth-start-001"))
        assertFalse(Schema41.isResponseTo(response, "auth-start-002"))
        assertFalse(Schema41.isResponseTo(JSONObject(), "auth-start-001"))
    }

    @Test
    fun `isAccepted defaults to false when absent`() {
        assertTrue(Schema41.isAccepted(JSONObject().put("accepted", true)))
        assertFalse(Schema41.isAccepted(JSONObject().put("accepted", false)))
        assertFalse(Schema41.isAccepted(JSONObject()))
    }

    @Test
    fun `rejectionMessage prefers reason then errorCode then a generic fallback`() {
        assertEquals(
            "Badge not recognised",
            Schema41.rejectionMessage(
                JSONObject().put("reason", "Badge not recognised").put("errorCode", "badge_rejected")
            )
        )
        assertEquals(
            "badge_rejected",
            Schema41.rejectionMessage(JSONObject().put("errorCode", "badge_rejected"))
        )
        assertEquals("Authentication failed", Schema41.rejectionMessage(JSONObject()))
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station1.Schema41Test"`
Expected: compilation FAILS with unresolved reference `Schema41`.

- [ ] **Step 4: Write the implementation**

Create `app\src\main\java\com\mitas\ppnam\station1\Schema41.kt`:

```kotlin
package com.mitas.ppnam.station1

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.UUID

/**
 * The shared Station 2 schema 4.1 authentication envelope, as adopted by Station 1
 * (Station1_MQTT_Contract v3.0.0 §4.1-§4.2):
 *
 *  - every auth request carries messageId, schemaVersion "4.1", deviceId and a timestampUtc
 *    with exactly six fractional digits;
 *  - responses correlate on inResponseToMessageId and are branched on `accepted` and
 *    `errorCode` — free-text `reason` is display-only.
 */
object Schema41 {

    const val SCHEMA_VERSION = "4.1"

    // The contract fixes the auth timestamp to yyyy-MM-dd'T'HH:mm:ss.ffffff'Z' — exactly six
    // fractional digits, so a plain Instant.toString() (variable precision) is not acceptable.
    private val TIMESTAMP_FORMAT = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
        .appendFraction(ChronoField.MICRO_OF_SECOND, 6, 6, true)
        .appendLiteral('Z')
        .toFormatter()
        .withZone(ZoneOffset.UTC)

    fun timestampUtc(instant: Instant = Instant.now()): String = TIMESTAMP_FORMAT.format(instant)

    /** One id per logical operation — a retry must reuse it, so callers mint it once up front. */
    fun newMessageId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    fun envelope(messageId: String, deviceId: String): JSONObject = JSONObject().apply {
        put("messageId", messageId)
        put("schemaVersion", SCHEMA_VERSION)
        put("deviceId", deviceId)
        put("timestampUtc", timestampUtc())
    }

    fun isResponseTo(response: JSONObject, requestMessageId: String): Boolean =
        response.optString("inResponseToMessageId", "") == requestMessageId

    fun isAccepted(response: JSONObject): Boolean = response.optBoolean("accepted", false)

    /** Operator-facing text for a rejection: the station's sanitized reason, else the code. */
    fun rejectionMessage(response: JSONObject): String =
        response.optString("reason", "").ifBlank {
            response.optString("errorCode", "").ifBlank { "Authentication failed" }
        }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station1.Schema41Test"`
Expected: BUILD SUCCESSFUL, all 7 tests pass.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station1/Schema41.kt app/src/test/java/com/mitas/ppnam/station1/Schema41Test.kt app/build.gradle.kts
git commit -m @'
Add schema 4.1 auth envelope helpers

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 2: Fail-closed `allowedTabs` and the `offload` tab value

**Files:**
- Modify: `app\src\main\java\com\mitas\ppnam\station1\OperatorSession.kt:9-37`
- Modify: `app\src\main\java\com\mitas\ppnam\station1\MainActivity.kt:91-95`
- Test: `app\src\test\java\com\mitas\ppnam\station1\OperatorSessionTest.kt`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces (used by Tasks 6-7): `StationTab.TAG_ASSIGNMENT == "tag_assignment"`, `StationTab.OFFLOAD == "offload"` (constant `BAG_PAIRING` is deleted); `OperatorSession.canShow(tab: String): Boolean` now fail-closed.

- [ ] **Step 1: Write the failing test**

Create `app\src\test\java\com\mitas\ppnam\station1\OperatorSessionTest.kt`:

```kotlin
package com.mitas.ppnam.station1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract v3.0.0 §3: allowedTabs values are tag_assignment/offload; a missing or empty list
 * means NO workflows enabled (fail closed), replacing the old fail-open behavior.
 */
class OperatorSessionTest {

    private fun session(tabs: List<String>) = OperatorSession(
        operatorSessionId = "s1",
        operatorId = "op1",
        operatorName = "Operator One",
        role = "Operator",
        allowedTabs = tabs,
    )

    @Test
    fun `wire values match the contract`() {
        assertEquals("tag_assignment", StationTab.TAG_ASSIGNMENT)
        assertEquals("offload", StationTab.OFFLOAD)
    }

    @Test
    fun `an empty allowedTabs list enables nothing`() {
        val s = session(emptyList())
        assertFalse(s.canShow(StationTab.TAG_ASSIGNMENT))
        assertFalse(s.canShow(StationTab.OFFLOAD))
    }

    @Test
    fun `only the listed workflows are enabled`() {
        val s = session(listOf(StationTab.OFFLOAD))
        assertFalse(s.canShow(StationTab.TAG_ASSIGNMENT))
        assertTrue(s.canShow(StationTab.OFFLOAD))
    }

    @Test
    fun `both workflows enabled when both are listed`() {
        val s = session(listOf(StationTab.TAG_ASSIGNMENT, StationTab.OFFLOAD))
        assertTrue(s.canShow(StationTab.TAG_ASSIGNMENT))
        assertTrue(s.canShow(StationTab.OFFLOAD))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station1.OperatorSessionTest"`
Expected: compilation FAILS (`StationTab.OFFLOAD` unresolved); after any stub, the empty-list test would fail against the current fail-open `canShow`.

- [ ] **Step 3: Implement**

In `OperatorSession.kt` replace the `StationTab` object (lines 5-12) with:

```kotlin
/**
 * The two workflows this handheld offers (contract v3.0.0 §3). Wire values arrive in the
 * login response's `allowedTabs`.
 */
object StationTab {
    const val TAG_ASSIGNMENT = "tag_assignment"
    const val OFFLOAD = "offload"
}
```

and replace `canShow` and its kdoc (lines 29-36) with:

```kotlin
    /**
     * Whether to OFFER [tab] in the UI. Presentation only — the station re-checks every
     * request server-side (ACTION_NOT_ALLOWED).
     *
     * Fails CLOSED (contract v3.0.0 §3): a login that arrived without allowedTabs, or with an
     * empty list, enables no workflows at all.
     */
    fun canShow(tab: String): Boolean = tab in allowedTabs
```

In `MainActivity.kt`, update the tile-gating block (lines 91-95): change the comment to say fail-closed and the constant to `StationTab.OFFLOAD`:

```kotlin
        // The login response decides which sub-apps this operator gets (allowedTabs, fail-closed
        // on a missing/empty list — display gating only, the station re-checks server-side).
        val session = OperatorSessionHolder.session
        setTileEnabled(binding.tileTagAssignment, session?.canShow(StationTab.TAG_ASSIGNMENT) ?: false)
        setTileEnabled(binding.tileBagPairing, session?.canShow(StationTab.OFFLOAD) ?: false)
```

(The `tileBagPairing` view id is renamed in Task 7 together with the layout.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station1.OperatorSessionTest"`
Expected: BUILD SUCCESSFUL, 4 tests pass.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station1/OperatorSession.kt app/src/main/java/com/mitas/ppnam/station1/MainActivity.kt app/src/test/java/com/mitas/ppnam/station1/OperatorSessionTest.kt
git commit -m @'
Fail closed on missing allowedTabs and rename bag_pairing tab to offload

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 3: AuthClient on the schema 4.1 envelope and `scram_proof_result`

**Files:**
- Modify: `app\src\main\java\com\mitas\ppnam\station1\AuthClient.kt` (full-file replacement below)

**Interfaces:**
- Consumes: `Schema41` from Task 1 (all six members).
- Produces: `AuthClient.login`, `loginWithBadge`, `logout` — signatures unchanged, so `LoginActivity`/`MainActivity` need no edits.

Behavior changes required by the contract:
1. Every auth request uses `Schema41.envelope(...)` (messageId / schemaVersion / timestampUtc) instead of the old `ts`+`deviceId` envelope.
2. The SCRAM proof awaits `res/scram_proof_result`, not `res/operator_context` (§10 Android delta 3).
3. Responses are accepted only when `inResponseToMessageId` matches the request's messageId; branching is on `accepted`/`errorCode` — the old `"status": "rejected"` idiom is gone.
4. Every round trip also listens on `res/request_rejected` (envelope/routing failures land there, correlated the same way).
5. A malformed or uncorrelated message on the response topic is logged and ignored (the timeout covers us) instead of failing the request — with correlation we can no longer assume any arriving payload is ours.

- [ ] **Step 1: Replace `AuthClient.kt`**

Full new content:

```kotlin
package com.mitas.ppnam.station1

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Operator authentication over MQTT — the shared Station 2 schema 4.1 contract on Station 1's
 * namespaced topics (Station1_MQTT_Contract v3.0.0 §4):
 *
 *   req/scram_start_requested   -> res/scram_challenge
 *   req/scram_proof_requested   -> res/scram_proof_result
 *   req/login_requested         -> res/operator_context
 *   req/reader_logout_requested -> res/operator_context (fire-and-forget here)
 *
 * Every request carries the schema 4.1 envelope (Schema41). Responses are correlated on
 * inResponseToMessageId and branched on `accepted`/`errorCode` — free-text `reason` is shown
 * to the operator, never parsed. Envelope and routing failures arrive on res/request_rejected,
 * so every round trip listens there too.
 *
 * Logout clears the local session regardless of the outcome: stranding an operator logged-in
 * because the network blipped would be worse than a server-side session that expires on its own.
 */
class AuthClient(context: Context) {

    private val appContext = context.applicationContext
    private val mqtt = MqttManager.getInstance(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private companion object {
        const val TAG = "AuthClient"
        const val REQUEST_TIMEOUT_MS = 10_000L
        const val PURPOSE_LOGIN = "login"
        const val REJECTED_SUFFIX = "request_rejected"
    }

    private fun stationInt(): Int =
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("station_int", 1)

    private fun deviceId(): String = DeviceIdentity.deviceId(appContext)

    fun login(username: String, password: String, onResult: (Result<OperatorSession>) -> Unit) {
        val clientNonce = ScramCrypto.generateClientNonce()

        val startPayload = Schema41.envelope(Schema41.newMessageId("auth-start"), deviceId()).apply {
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

            val proofPayload = Schema41.envelope(Schema41.newMessageId("auth-proof"), deviceId()).apply {
                put("challengeId", challengeId)
                put("clientFinalWithoutProof", clientFinalWithoutProof)
                put("clientProof", proof.clientProofBase64)
                put("purpose", PURPOSE_LOGIN)
            }

            request("scram_proof_requested", "scram_proof_result", proofPayload) { proofResult ->
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
        val payload = Schema41.envelope(Schema41.newMessageId("badge-login"), deviceId()).apply {
            put("badgeTag", badgeTag)
        }
        request("login_requested", "operator_context", payload) { result ->
            onResult(result.fold({ buildSession(it) }, { Result.failure(it) }))
        }
    }

    fun logout(onComplete: () -> Unit = {}) {
        val payload = Schema41.envelope(Schema41.newMessageId("logout"), deviceId()).apply {
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

    /**
     * One schema 4.1 request/response round trip, with a timeout. The response is accepted only
     * when its inResponseToMessageId matches this request; rejections — on the response topic or
     * on res/request_rejected — surface as failures carrying the station's sanitized reason.
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
        val requestMessageId = payload.getString("messageId")
        val responseTopic = MqttTopics.deviceResponse(station, device, responseType)
        val rejectedTopic = MqttTopics.deviceResponse(station, device, REJECTED_SUFFIX)
        val done = AtomicBoolean(false)

        lateinit var timeoutRunnable: Runnable
        lateinit var onResponse: (Mqtt3Publish) -> Unit
        lateinit var onRejected: (Mqtt3Publish) -> Unit

        fun finish(result: Result<JSONObject>) {
            if (!done.compareAndSet(false, true)) return
            mainHandler.removeCallbacks(timeoutRunnable)
            mqtt.unsubscribe(responseTopic, onResponse)
            mqtt.unsubscribe(rejectedTopic, onRejected)
            mainHandler.post { onResult(result) }
        }

        timeoutRunnable = Runnable { finish(failure("Station did not respond")) }

        // With correlation, a message that isn't ours (wrong device, wrong messageId, or
        // unparseable) is ignored rather than failing the request — the timeout covers silence.
        fun parseCorrelated(publish: Mqtt3Publish): JSONObject? = try {
            val json = JSONObject(String(publish.payloadAsBytes, StandardCharsets.UTF_8))
            json.takeIf { mqtt.isRelevantToThisScanner(it) && Schema41.isResponseTo(it, requestMessageId) }
        } catch (e: Exception) {
            Log.e(TAG, "Malformed payload on ${publish.topic}", e)
            null
        }

        onResponse = { publish ->
            parseCorrelated(publish)?.let { json ->
                if (Schema41.isAccepted(json)) finish(Result.success(json))
                else finish(failure(Schema41.rejectionMessage(json)))
            }
        }

        onRejected = { publish ->
            parseCorrelated(publish)?.let { json ->
                finish(failure(Schema41.rejectionMessage(json)))
            }
        }

        mqtt.subscribe(responseTopic, onResponse)
        mqtt.subscribe(rejectedTopic, onRejected)
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
```

- [ ] **Step 2: Verify it compiles and existing tests stay green**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (Schema41Test, OperatorSessionTest, MqttTopicsTest, ExampleUnitTest all pass).

- [ ] **Step 3: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station1/AuthClient.kt
git commit -m @'
Move auth to the schema 4.1 envelope and scram_proof_result

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 4: Workflow payload builders and offload value parsing

**Files:**
- Create: `app\src\main\java\com\mitas\ppnam\station1\WorkflowMessages.kt`
- Test: `app\src\test\java\com\mitas\ppnam\station1\WorkflowMessagesTest.kt`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces (used by Tasks 6-7):
  - `WorkflowMessages.tagScan(deviceId: String, operatorSessionId: String, tagId: String): JSONObject`
  - `WorkflowMessages.offloadScan(deviceId: String, operatorSessionId: String, tagId: String, barcode: String): JSONObject`
  - `WorkflowMessages.offloadConfirm(deviceId: String, operatorSessionId: String, tagId: String, barcode: String, bagWeight: Double, bagCount: Int, batchReference: String): JSONObject`
  - `WorkflowMessages.formatWeight(weight: Double): String`
  - `data class OffloadPrefill(val bagWeight: Double, val bagCount: Int, val batchReference: String)` with `OffloadPrefill.fromScanResult(json: JSONObject): OffloadPrefill?`
  - `object OffloadInput` with `parseWeight(text: String): Double?`, `parseCount(text: String): Int?`, `parseBatch(text: String): String?`

- [ ] **Step 1: Write the failing test**

Create `app\src\test\java\com\mitas\ppnam\station1\WorkflowMessagesTest.kt`:

```kotlin
package com.mitas.ppnam.station1

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract v3.0.0 §5-§7: workflow requests use the lightweight envelope (ts, deviceId,
 * operatorSessionId) — no messageId/schemaVersion — and offload_confirm carries bagWeight as a
 * JSON number, bagCount as a JSON integer, batchReference as a string.
 */
class WorkflowMessagesTest {

    @Test
    fun `tagScan carries the lightweight envelope and tagId`() {
        val p = WorkflowMessages.tagScan("scanner_abc", "sess-1", "E280TAG")
        assertEquals("scanner_abc", p.getString("deviceId"))
        assertEquals("sess-1", p.getString("operatorSessionId"))
        assertEquals("E280TAG", p.getString("tagId"))
        assertTrue(p.getString("ts").endsWith("Z"))
        assertTrue("workflow messages carry no schema 4.1 fields", !p.has("messageId") && !p.has("schemaVersion"))
    }

    @Test
    fun `offloadScan adds the barcode`() {
        val p = WorkflowMessages.offloadScan("scanner_abc", "sess-1", "E280TAG", "BC-000123")
        assertEquals("E280TAG", p.getString("tagId"))
        assertEquals("BC-000123", p.getString("barcode"))
    }

    @Test
    fun `offloadConfirm sends typed values`() {
        val p = WorkflowMessages.offloadConfirm(
            "scanner_abc", "sess-1", "E280TAG", "BC-000123",
            bagWeight = 24.5, bagCount = 40, batchReference = "BATCH-2026-0815",
        )
        assertEquals(24.5, p.getDouble("bagWeight"), 0.0)
        assertTrue("bagCount must be a JSON integer", p.get("bagCount") is Int)
        assertEquals(40, p.getInt("bagCount"))
        assertEquals("BATCH-2026-0815", p.getString("batchReference"))
    }

    @Test
    fun `prefill parses a matched scan result`() {
        val json = JSONObject()
            .put("matched", true)
            .put("bagWeight", 25.0)
            .put("bagCount", 40)
            .put("batchReference", "BATCH-2026-0815")
        val prefill = OffloadPrefill.fromScanResult(json)
        assertNotNull(prefill)
        assertEquals(25.0, prefill!!.bagWeight, 0.0)
        assertEquals(40, prefill.bagCount)
        assertEquals("BATCH-2026-0815", prefill.batchReference)
    }

    @Test
    fun `prefill rejects missing or non-positive values`() {
        assertNull(OffloadPrefill.fromScanResult(JSONObject().put("matched", true)))
        assertNull(
            OffloadPrefill.fromScanResult(
                JSONObject().put("bagWeight", 0.0).put("bagCount", 40).put("batchReference", "B")
            )
        )
        assertNull(
            OffloadPrefill.fromScanResult(
                JSONObject().put("bagWeight", 25.0).put("bagCount", 0).put("batchReference", "B")
            )
        )
        assertNull(
            OffloadPrefill.fromScanResult(
                JSONObject().put("bagWeight", 25.0).put("bagCount", 40).put("batchReference", " ")
            )
        )
    }

    @Test
    fun `operator input parsing enforces positive typed values`() {
        assertEquals(24.5, OffloadInput.parseWeight(" 24.5 ")!!, 0.0)
        assertNull(OffloadInput.parseWeight("0"))
        assertNull(OffloadInput.parseWeight("-1"))
        assertNull(OffloadInput.parseWeight("abc"))
        assertNull(OffloadInput.parseWeight(""))

        assertEquals(40, OffloadInput.parseCount(" 40 "))
        assertNull(OffloadInput.parseCount("0"))
        assertNull(OffloadInput.parseCount("2.5"))

        assertEquals("BATCH-1", OffloadInput.parseBatch(" BATCH-1 "))
        assertNull(OffloadInput.parseBatch("   "))
    }

    @Test
    fun `formatWeight shows whole kilograms without a decimal tail`() {
        assertEquals("25", WorkflowMessages.formatWeight(25.0))
        assertEquals("24.5", WorkflowMessages.formatWeight(24.5))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station1.WorkflowMessagesTest"`
Expected: compilation FAILS with unresolved references.

- [ ] **Step 3: Write the implementation**

Create `app\src\main\java\com\mitas\ppnam\station1\WorkflowMessages.kt`:

```kotlin
package com.mitas.ppnam.station1

import org.json.JSONObject
import java.time.Instant

/**
 * Workflow request payloads (contract v3.0.0 §5-§7): the lightweight envelope — `ts`,
 * `deviceId`, `operatorSessionId` — plus the business fields. Workflow messages carry no
 * messageId/schemaVersion; those belong to the schema 4.1 authentication envelope.
 */
object WorkflowMessages {

    fun tagScan(deviceId: String, operatorSessionId: String, tagId: String): JSONObject =
        base(deviceId, operatorSessionId).put("tagId", tagId)

    fun offloadScan(
        deviceId: String,
        operatorSessionId: String,
        tagId: String,
        barcode: String,
    ): JSONObject = base(deviceId, operatorSessionId)
        .put("tagId", tagId)
        .put("barcode", barcode)

    /** §6.2: bagWeight is a JSON number, bagCount a positive JSON integer. */
    fun offloadConfirm(
        deviceId: String,
        operatorSessionId: String,
        tagId: String,
        barcode: String,
        bagWeight: Double,
        bagCount: Int,
        batchReference: String,
    ): JSONObject = base(deviceId, operatorSessionId)
        .put("tagId", tagId)
        .put("barcode", barcode)
        .put("bagWeight", bagWeight)
        .put("bagCount", bagCount)
        .put("batchReference", batchReference)

    /** Prefill display: whole kilograms without the ".0" tail an operator would have to erase. */
    fun formatWeight(weight: Double): String =
        if (weight == Math.floor(weight) && !weight.isInfinite()) weight.toLong().toString()
        else weight.toString()

    private fun base(deviceId: String, operatorSessionId: String): JSONObject = JSONObject().apply {
        put("ts", Instant.now().toString())
        put("deviceId", deviceId)
        put("operatorSessionId", operatorSessionId)
    }
}

/**
 * The three packaging values a matched offload_scan_result must carry (§6.1). Parsing returns
 * null when any is missing or out of range — a "matched" result without usable prefill is
 * treated as unusable rather than showing the operator empty fields.
 */
data class OffloadPrefill(
    val bagWeight: Double,
    val bagCount: Int,
    val batchReference: String,
) {
    companion object {
        fun fromScanResult(json: JSONObject): OffloadPrefill? {
            val weight = json.optDouble("bagWeight", Double.NaN)
            val count = json.optInt("bagCount", 0)
            val batch = json.optString("batchReference", "")
            if (weight.isNaN() || weight <= 0.0 || count <= 0 || batch.isBlank()) return null
            return OffloadPrefill(weight, count, batch)
        }
    }
}

/** Operator-edited values, validated before offload_confirm goes on the wire. */
object OffloadInput {
    fun parseWeight(text: String): Double? =
        text.trim().toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }

    fun parseCount(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it > 0 }

    fun parseBatch(text: String): String? =
        text.trim().takeIf { it.isNotEmpty() }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.mitas.ppnam.station1.WorkflowMessagesTest"`
Expected: BUILD SUCCESSFUL, 7 tests pass.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station1/WorkflowMessages.kt app/src/test/java/com/mitas/ppnam/station1/WorkflowMessagesTest.kt
git commit -m @'
Add workflow payload builders and offload prefill parsing

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 5: `WorkflowClient` round-trip helper

**Files:**
- Create: `app\src\main\java\com\mitas\ppnam\station1\WorkflowClient.kt`

**Interfaces:**
- Consumes: `MqttManager`, `MqttTopics`, `DeviceIdentity` (existing).
- Produces (used by Tasks 6-7):
  - `class WorkflowTimeout : Exception` — lets activities distinguish "no answer" from "publish failed".
  - `WorkflowClient(context).request(requestType: String, responseType: String, payload: JSONObject, matches: (JSONObject) -> Boolean, onResult: (Result<JSONObject>) -> Unit)` — callback fires exactly once, on the main thread; success delivers the raw response JSON (caller branches on `accepted`/`matched`/`errorCode`).
  - `WorkflowClient.isSessionRejection(json: JSONObject): Boolean` — true for `AUTHENTICATION_REQUIRED`/`OPERATOR_SESSION_INVALID` (§7/§8: the operator session is gone; the scanner must re-authenticate).

No JVM unit test — the class is a thin MQTT/Handler adapter with no pure logic (that logic was extracted into Task 4); it's exercised end-to-end in Task 8's verification.

- [ ] **Step 1: Write the implementation**

Create `app\src\main\java\com\mitas\ppnam\station1\WorkflowClient.kt`:

```kotlin
package com.mitas.ppnam.station1

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

    private fun stationInt(): Int =
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("station_int", 1)

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

        val station = stationInt()
        val device = DeviceIdentity.deviceId(appContext)
        val responseTopic = MqttTopics.deviceResponse(station, device, responseType)
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

        mqtt.publish(MqttTopics.deviceRequest(station, device, requestType), payload.toString()) { throwable ->
            if (throwable != null) finish(Result.failure(Exception("Could not reach the station")))
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station1/WorkflowClient.kt
git commit -m @'
Add workflow round-trip client with contract timeout

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 6: Tag Assignment consumes `tag_scan_result`

**Files:**
- Modify: `app\src\main\java\com\mitas\ppnam\station1\TagAssignmentActivity.kt` (replace `sendTag` and supporting fields — full file below)
- Modify: `app\src\main\res\values\strings.xml` (Tag Assignment + shared send-state sections)

**Interfaces:**
- Consumes: `WorkflowClient`/`WorkflowTimeout` (Task 5), `WorkflowMessages.tagScan` (Task 4).
- Produces: string resources `status_tag_assigned`, `status_no_response` (Task 7 reuses `status_no_response`). String `tx_status_sent` is deleted.

Contract rules implemented (§5): every scan sends automatically; UI shows pending until `tag_scan_result` echoing the same `tagId` arrives; `accepted:false` shows the station's sanitized reason; 10 s of silence shows a timeout; PUBACK alone is never success.

- [ ] **Step 1: Update strings**

In `app\src\main\res\values\strings.xml`:
- Delete the line `<string name="tx_status_sent">SENT</string>` (its only user was the PUBACK-as-success display this task removes).
- Add to the Tag Assignment section: `<string name="status_tag_assigned">Tag assigned</string>`
- Add to the "Shared send states" section: `<string name="status_no_response">No response from station — try again</string>`

- [ ] **Step 2: Replace `TagAssignmentActivity.kt`**

Full new content:

```kotlin
package com.mitas.ppnam.station1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mitas.ppnam.station1.databinding.ActivityTagAssignmentBinding

/**
 * Tag Assignment (contract v3.0.0 §5): every scanned RFID tag is sent automatically as
 * `tag_scan`; the station decides what the tag means and answers `tag_scan_result` echoing the
 * tagId. The UI stays pending until that result (or the 10-second timeout) — a PUBACK is
 * transport-only and never shown as success.
 */
class TagAssignmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTagAssignmentBinding
    private lateinit var workflow: WorkflowClient
    private var lastScannedTag: String? = null

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread { binding.connectionPill.setStatus(status) }
    }

    private val rfidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.rscja.scanner.action.scanner.RFID") {
                val data = intent.getStringExtra("data")
                if (!data.isNullOrEmpty()) onTagScanned(data)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTagAssignmentBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        forceLightStatusBarIcons()

        setupToolbar()
        workflow = WorkflowClient(this)
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this) { finishBackward() }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.tab_tag_assignment)
    }

    private fun onTagScanned(tagId: String) {
        runOnUiThread {
            lastScannedTag = tagId
            binding.tvLastTag.text = tagId
            showStatus(getString(R.string.status_sending), R.color.text_muted)
        }
        sendTag(tagId)
    }

    private fun sendTag(tagId: String) {
        val payload = WorkflowMessages.tagScan(
            deviceId = DeviceIdentity.deviceId(this),
            operatorSessionId = OperatorSessionHolder.currentSessionIdOrEmpty(),
            tagId = tagId,
        )
        workflow.request(
            requestType = "tag_scan",
            responseType = "tag_scan_result",
            payload = payload,
            matches = { it.optString("tagId") == tagId },
        ) { result ->
            // A newer scan owns the status line by now — its own result will drive the UI.
            if (tagId != lastScannedTag) return@request
            result
                .onSuccess { json ->
                    if (json.optBoolean("accepted", false)) {
                        showStatus(
                            json.optString("reason", "").ifBlank { getString(R.string.status_tag_assigned) },
                            R.color.success,
                        )
                    } else {
                        if (handleSessionRejection(json)) return@request
                        showStatus(stationReason(json), R.color.danger)
                    }
                }
                .onFailure { e ->
                    val message = if (e is WorkflowTimeout) getString(R.string.status_no_response)
                    else getString(R.string.status_send_failed)
                    showStatus(message, R.color.danger)
                }
        }
    }

    private fun stationReason(json: org.json.JSONObject): String =
        json.optString("reason", "").ifBlank {
            json.optString("errorCode", "").ifBlank { getString(R.string.status_send_failed) }
        }

    /** §8: a closed/expired session sends the operator back to login, not into a dead end. */
    private fun handleSessionRejection(json: org.json.JSONObject): Boolean {
        if (!WorkflowClient.isSessionRejection(json)) return false
        OperatorSessionHolder.clear()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
        return true
    }

    private fun showStatus(message: String, colorRes: Int) {
        binding.tvSendStatus.visibility = android.view.View.VISIBLE
        binding.tvSendStatus.text = message
        binding.tvSendStatus.setTextColor(getColor(colorRes))
    }

    override fun onResume() {
        super.onResume()
        val rfidFilter = IntentFilter("com.rscja.scanner.action.scanner.RFID")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rfidReceiver, rfidFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(rfidReceiver, rfidFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(rfidReceiver)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishBackward()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
    }
}
```

(The old `stationInt`/`loadSettings` members disappear — `WorkflowClient` reads the station number itself. `WorkflowClient`'s callback already arrives on the main thread, so the result branch needs no `runOnUiThread`.)

- [ ] **Step 3: Verify it compiles and tests stay green**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. (`tx_status_sent` removal will surface any lingering reference as a compile error — there must be none.)

- [ ] **Step 4: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station1/TagAssignmentActivity.kt app/src/main/res/values/strings.xml
git commit -m @'
Consume tag_scan_result instead of treating PUBACK as success

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 7: Two-step Offload workflow replaces Bag Pairing

**Files:**
- Create: `app\src\main\java\com\mitas\ppnam\station1\OffloadActivity.kt`
- Create: `app\src\main\res\layout\activity_offload.xml`
- Modify: `app\src\main\res\layout\activity_main.xml:163,192` (tile id + text)
- Modify: `app\src\main\java\com\mitas\ppnam\station1\MainActivity.kt:74-76,95,98` (tile id + target activity)
- Modify: `app\src\main\AndroidManifest.xml:59-63` (activity entry)
- Modify: `app\src\main\res\values\strings.xml` (Bag Pairing → Offload strings)
- Delete: `app\src\main\java\com\mitas\ppnam\station1\BagPairingActivity.kt`, `app\src\main\res\layout\activity_bag_pairing.xml`

**Interfaces:**
- Consumes: `WorkflowClient`/`WorkflowTimeout` (Task 5); `WorkflowMessages.offloadScan`, `WorkflowMessages.offloadConfirm`, `WorkflowMessages.formatWeight`, `OffloadPrefill.fromScanResult`, `OffloadInput.parseWeight/parseCount/parseBatch` (Task 4); `StationTab.OFFLOAD` (Task 2); string `status_no_response` (Task 6).
- Produces: `OffloadActivity` launched from the dashboard tile `tileOffload`.

Flow (contract §6): scan tag + barcode → operator taps Match Pallet → `offload_scan`; on `matched:true` the three prefill values populate editable fields → operator reviews/edits → Confirm Offload → `offload_confirm` (typed values; unchanged values go back verbatim because they are re-read from the same fields) → on `accepted:true` reset for the next pallet. `matched:false` returns to scanning with the station's reason; confirm rejections with `INVALID_BAG_WEIGHT`/`INVALID_BAG_COUNT`/`BATCH_REFERENCE_REQUIRED` keep the operator on the edit screen; any other confirm rejection (e.g. `TAG_ALREADY_OFFLOADED`) returns to scanning.

- [ ] **Step 1: Update strings**

In `app\src\main\res\values\strings.xml`, replace the `tab_bag_pairing` line with:

```xml
    <string name="tab_offload">Offload</string>
```

and replace the whole `<!-- Bag Pairing -->` block with:

```xml
    <!-- Offload -->
    <string name="section_scan">Scan</string>
    <string name="section_pallet_values">Pallet Values</string>
    <string name="hint_tag_id">RFID Tag ID</string>
    <string name="hint_barcode">Barcode</string>
    <string name="hint_bag_weight">Bag Weight (kg)</string>
    <string name="hint_bag_count">Number of Bags</string>
    <string name="hint_batch_ref">Batch Reference</string>
    <string name="btn_match_pallet">Match Pallet</string>
    <string name="btn_confirm_offload">Confirm Offload</string>
    <string name="btn_back_to_scan">Back to Scan</string>
    <string name="msg_offload_recorded">Offload recorded</string>
    <string name="error_incomplete_match">Station sent an incomplete match — try again</string>
    <string name="error_invalid_weight">Enter a bag weight greater than 0</string>
    <string name="error_invalid_count">Enter a whole number of bags greater than 0</string>
    <string name="error_batch_required">Enter a batch reference</string>
```

(Removed: `section_bag_details`, `btn_confirm`, `btn_edit`, `btn_submit_pairing`, `msg_pairing_sent`, `error_value_required` — their only users are deleted with `BagPairingActivity`.)

- [ ] **Step 2: Create `app\src\main\res\layout\activity_offload.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/main"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/window_background">

    <com.google.android.material.appbar.AppBarLayout
        android:id="@+id/appBarLayout"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        app:layout_constraintTop_toTopOf="parent">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            android:background="@color/card_background"
            app:title="@string/tab_offload"
            app:titleTextAppearance="@style/TextAppearance.SysOneScanner.ToolbarTitle">

            <com.mitas.ppnam.station1.ConnectionPillView
                android:id="@+id/connectionPill"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="end"
                android:layout_marginEnd="12dp" />
        </com.google.android.material.appbar.MaterialToolbar>
    </com.google.android.material.appbar.AppBarLayout>

    <androidx.core.widget.NestedScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:clipToPadding="false"
        android:overScrollMode="never"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/appBarLayout">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="16dp">

            <!-- Step 1: scan the pallet's tag and barcode, then ask the station to match them -->
            <com.google.android.material.card.MaterialCardView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                app:cardBackgroundColor="@color/card_background"
                app:cardCornerRadius="16dp"
                app:cardElevation="0dp"
                app:strokeColor="@color/border_primary"
                app:strokeWidth="1dp">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="16dp">

                    <TextView
                        style="@style/SettingsSectionLabel"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/section_scan"
                        android:textColor="@color/primary_action" />

                    <com.google.android.material.textfield.TextInputLayout
                        android:id="@+id/tilTag"
                        style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:hint="@string/hint_tag_id"
                        app:boxStrokeColor="@color/outline_dark"
                        app:hintTextColor="@color/text_secondary_dark">

                        <com.google.android.material.textfield.TextInputEditText
                            android:id="@+id/etTag"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:inputType="textNoSuggestions"
                            android:maxLines="1"
                            android:textColor="@color/text_primary_dark" />
                    </com.google.android.material.textfield.TextInputLayout>

                    <com.google.android.material.textfield.TextInputLayout
                        android:id="@+id/tilBarcode"
                        style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:hint="@string/hint_barcode"
                        app:boxStrokeColor="@color/outline_dark"
                        app:hintTextColor="@color/text_secondary_dark">

                        <com.google.android.material.textfield.TextInputEditText
                            android:id="@+id/etBarcode"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:inputType="textNoSuggestions"
                            android:maxLines="1"
                            android:textColor="@color/text_primary_dark" />
                    </com.google.android.material.textfield.TextInputLayout>

                    <TextView
                        android:id="@+id/tvScanStatus"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:textColor="@color/text_muted"
                        android:textSize="15sp"
                        android:visibility="gone"
                        tools:text="Tag and barcode match"
                        tools:visibility="visible" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/btnMatchPallet"
                        android:layout_width="match_parent"
                        android:layout_height="56dp"
                        android:layout_marginTop="12dp"
                        android:enabled="false"
                        android:text="@string/btn_match_pallet"
                        app:backgroundTint="@color/primary_action"
                        app:cornerRadius="14dp" />
                </LinearLayout>
            </com.google.android.material.card.MaterialCardView>

            <!-- Step 2: station prefill, editable, confirmed as one unit -->
            <com.google.android.material.card.MaterialCardView
                android:id="@+id/cardValues"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="16dp"
                android:visibility="gone"
                app:cardBackgroundColor="@color/card_background"
                app:cardCornerRadius="16dp"
                app:cardElevation="0dp"
                app:strokeColor="@color/border_primary"
                app:strokeWidth="1dp"
                tools:visibility="visible">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="16dp">

                    <TextView
                        style="@style/SettingsSectionLabel"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/section_pallet_values"
                        android:textColor="@color/primary_action" />

                    <com.google.android.material.textfield.TextInputLayout
                        android:id="@+id/tilBagWeight"
                        style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:hint="@string/hint_bag_weight"
                        app:boxStrokeColor="@color/outline_dark"
                        app:hintTextColor="@color/text_secondary_dark">

                        <com.google.android.material.textfield.TextInputEditText
                            android:id="@+id/etBagWeight"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:inputType="numberDecimal"
                            android:maxLines="1"
                            android:textColor="@color/text_primary_dark" />
                    </com.google.android.material.textfield.TextInputLayout>

                    <com.google.android.material.textfield.TextInputLayout
                        android:id="@+id/tilBagCount"
                        style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:hint="@string/hint_bag_count"
                        app:boxStrokeColor="@color/outline_dark"
                        app:hintTextColor="@color/text_secondary_dark">

                        <com.google.android.material.textfield.TextInputEditText
                            android:id="@+id/etBagCount"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:inputType="number"
                            android:maxLines="1"
                            android:textColor="@color/text_primary_dark" />
                    </com.google.android.material.textfield.TextInputLayout>

                    <com.google.android.material.textfield.TextInputLayout
                        android:id="@+id/tilBatchRef"
                        style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:hint="@string/hint_batch_ref"
                        app:boxStrokeColor="@color/outline_dark"
                        app:hintTextColor="@color/text_secondary_dark">

                        <com.google.android.material.textfield.TextInputEditText
                            android:id="@+id/etBatchRef"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:inputType="textCapCharacters|textNoSuggestions"
                            android:maxLines="1"
                            android:textColor="@color/text_primary_dark" />
                    </com.google.android.material.textfield.TextInputLayout>

                    <TextView
                        android:id="@+id/tvConfirmStatus"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:textColor="@color/text_muted"
                        android:textSize="15sp"
                        android:visibility="gone"
                        tools:text="Offload recorded"
                        tools:visibility="visible" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/btnConfirmOffload"
                        android:layout_width="match_parent"
                        android:layout_height="56dp"
                        android:layout_marginTop="12dp"
                        android:text="@string/btn_confirm_offload"
                        app:backgroundTint="@color/primary_action"
                        app:cornerRadius="14dp" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/btnBackToScan"
                        style="@style/Widget.MaterialComponents.Button.TextButton"
                        android:layout_width="match_parent"
                        android:layout_height="48dp"
                        android:layout_marginTop="4dp"
                        android:text="@string/btn_back_to_scan"
                        android:textColor="@color/text_secondary_dark" />
                </LinearLayout>
            </com.google.android.material.card.MaterialCardView>
        </LinearLayout>
    </androidx.core.widget.NestedScrollView>

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 3: Create `app\src\main\java\com\mitas\ppnam\station1\OffloadActivity.kt`**

```kotlin
package com.mitas.ppnam.station1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mitas.ppnam.station1.databinding.ActivityOffloadBinding
import org.json.JSONObject

/**
 * Offload (contract v3.0.0 §6), in two steps:
 *
 *  1. Scan the pallet's RFID tag and barcode, then Match Pallet -> `offload_scan`. A matched
 *     result carries the pallet's expected bagWeight/bagCount/batchReference as prefill.
 *  2. Review/edit the prefilled values, then Confirm Offload -> `offload_confirm` with the
 *     final typed values (unchanged values go back verbatim — they're re-read from the same
 *     fields). The station re-validates the pair at confirm time, so no client-side pairing
 *     state must survive between the two steps.
 *
 * Value-validation rejections (INVALID_BAG_WEIGHT / INVALID_BAG_COUNT /
 * BATCH_REFERENCE_REQUIRED) keep the operator on the edit step; any other rejection returns
 * to scanning.
 */
class OffloadActivity : AppCompatActivity() {

    private enum class Step { SCAN, MATCHING, EDIT, CONFIRMING }

    private lateinit var binding: ActivityOffloadBinding
    private lateinit var workflow: WorkflowClient
    private var step = Step.SCAN
    private var matchedTag = ""
    private var matchedBarcode = ""

    private val editStepErrors = setOf("INVALID_BAG_WEIGHT", "INVALID_BAG_COUNT", "BATCH_REFERENCE_REQUIRED")

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread { binding.connectionPill.setStatus(status) }
    }

    private val rfidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.rscja.scanner.action.scanner.RFID") {
                val data = intent.getStringExtra("data")
                if (!data.isNullOrEmpty() && step == Step.SCAN) {
                    binding.etTag.setText(data)
                    updateMatchEnabled()
                }
            }
        }
    }

    private val barcodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.scanner.broadcast") {
                val data = intent.getStringExtra("data")
                if (!data.isNullOrEmpty() && step == Step.SCAN) {
                    binding.etBarcode.setText(data)
                    updateMatchEnabled()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOffloadBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        forceLightStatusBarIcons()

        setupToolbar()
        workflow = WorkflowClient(this)
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.etTag.addTextChangedListener(SimpleTextWatcher { updateMatchEnabled() })
        binding.etBarcode.addTextChangedListener(SimpleTextWatcher { updateMatchEnabled() })

        binding.btnMatchPallet.setOnClickListener { matchPallet() }
        binding.btnMatchPallet.applyPressScaleFeedback()
        binding.btnConfirmOffload.setOnClickListener { confirmOffload() }
        binding.btnConfirmOffload.applyPressScaleFeedback()
        binding.btnBackToScan.setOnClickListener { enterScanStep(clearScan = false) }

        onBackPressedDispatcher.addCallback(this) { finishBackward() }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.tab_offload)
    }

    // ---- step transitions --------------------------------------------------------------------

    private fun enterScanStep(clearScan: Boolean) {
        step = Step.SCAN
        binding.cardValues.visibility = View.GONE
        binding.tvConfirmStatus.visibility = View.GONE
        binding.tvScanStatus.visibility = View.GONE
        binding.etTag.isEnabled = true
        binding.etBarcode.isEnabled = true
        if (clearScan) {
            binding.etTag.setText("")
            binding.etBarcode.setText("")
        }
        updateMatchEnabled()
    }

    private fun enterEditStep(tagId: String, barcode: String, prefill: OffloadPrefill) {
        step = Step.EDIT
        matchedTag = tagId
        matchedBarcode = barcode
        binding.tvScanStatus.visibility = View.GONE
        binding.cardValues.visibility = View.VISIBLE
        binding.tvConfirmStatus.visibility = View.GONE
        binding.etBagWeight.setText(WorkflowMessages.formatWeight(prefill.bagWeight))
        binding.etBagCount.setText(prefill.bagCount.toString())
        binding.etBatchRef.setText(prefill.batchReference)
        binding.btnConfirmOffload.isEnabled = true
    }

    private fun updateMatchEnabled() {
        binding.btnMatchPallet.isEnabled = step == Step.SCAN &&
            binding.etTag.text.toString().isNotBlank() &&
            binding.etBarcode.text.toString().isNotBlank()
    }

    // ---- step 1: offload_scan ----------------------------------------------------------------

    private fun matchPallet() {
        if (step != Step.SCAN) return
        val tagId = binding.etTag.text.toString().trim()
        val barcode = binding.etBarcode.text.toString().trim()

        step = Step.MATCHING
        binding.btnMatchPallet.isEnabled = false
        binding.etTag.isEnabled = false
        binding.etBarcode.isEnabled = false
        showScanStatus(getString(R.string.status_sending), R.color.text_muted)

        val payload = WorkflowMessages.offloadScan(
            deviceId = DeviceIdentity.deviceId(this),
            operatorSessionId = OperatorSessionHolder.currentSessionIdOrEmpty(),
            tagId = tagId,
            barcode = barcode,
        )
        workflow.request(
            requestType = "offload_scan",
            responseType = "offload_scan_result",
            payload = payload,
            matches = { it.optString("tagId") == tagId && it.optString("barcode") == barcode },
        ) { result ->
            if (step != Step.MATCHING) return@request
            result
                .onSuccess { json ->
                    if (json.optBoolean("matched", false)) {
                        val prefill = OffloadPrefill.fromScanResult(json)
                        if (prefill != null) {
                            enterEditStep(tagId, barcode, prefill)
                        } else {
                            // "matched" without usable prefill breaks §6.1 — treat as no match.
                            backToScanWithError(getString(R.string.error_incomplete_match))
                        }
                    } else {
                        if (handleSessionRejection(json)) return@request
                        backToScanWithError(stationReason(json))
                    }
                }
                .onFailure { e -> backToScanWithError(failureText(e)) }
        }
    }

    private fun backToScanWithError(message: String) {
        enterScanStep(clearScan = false)
        showScanStatus(message, R.color.danger)
    }

    // ---- step 2: offload_confirm -------------------------------------------------------------

    private fun confirmOffload() {
        if (step != Step.EDIT) return
        val weight = OffloadInput.parseWeight(binding.etBagWeight.text.toString())
            ?: return showConfirmStatus(getString(R.string.error_invalid_weight), R.color.danger)
        val count = OffloadInput.parseCount(binding.etBagCount.text.toString())
            ?: return showConfirmStatus(getString(R.string.error_invalid_count), R.color.danger)
        val batch = OffloadInput.parseBatch(binding.etBatchRef.text.toString())
            ?: return showConfirmStatus(getString(R.string.error_batch_required), R.color.danger)

        step = Step.CONFIRMING
        binding.btnConfirmOffload.isEnabled = false
        showConfirmStatus(getString(R.string.status_sending), R.color.text_muted)

        val payload = WorkflowMessages.offloadConfirm(
            deviceId = DeviceIdentity.deviceId(this),
            operatorSessionId = OperatorSessionHolder.currentSessionIdOrEmpty(),
            tagId = matchedTag,
            barcode = matchedBarcode,
            bagWeight = weight,
            bagCount = count,
            batchReference = batch,
        )
        workflow.request(
            requestType = "offload_confirm",
            responseType = "offload_confirm_result",
            payload = payload,
            matches = { it.optString("tagId") == matchedTag && it.optString("barcode") == matchedBarcode },
        ) { result ->
            if (step != Step.CONFIRMING) return@request
            result
                .onSuccess { json ->
                    when {
                        json.optBoolean("accepted", false) -> {
                            enterScanStep(clearScan = true)
                            showScanStatus(getString(R.string.msg_offload_recorded), R.color.success)
                        }
                        json.optString("errorCode", "") in editStepErrors -> {
                            step = Step.EDIT
                            binding.btnConfirmOffload.isEnabled = true
                            showConfirmStatus(stationReason(json), R.color.danger)
                        }
                        else -> {
                            if (handleSessionRejection(json)) return@request
                            backToScanWithError(stationReason(json))
                        }
                    }
                }
                .onFailure { e ->
                    step = Step.EDIT
                    binding.btnConfirmOffload.isEnabled = true
                    showConfirmStatus(failureText(e), R.color.danger)
                }
        }
    }

    // ---- shared ------------------------------------------------------------------------------

    private fun stationReason(json: JSONObject): String =
        json.optString("reason", "").ifBlank {
            json.optString("errorCode", "").ifBlank { getString(R.string.status_send_failed) }
        }

    /** §8: a closed/expired session sends the operator back to login, not into a dead end. */
    private fun handleSessionRejection(json: JSONObject): Boolean {
        if (!WorkflowClient.isSessionRejection(json)) return false
        OperatorSessionHolder.clear()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
        return true
    }

    private fun failureText(e: Throwable): String =
        if (e is WorkflowTimeout) getString(R.string.status_no_response)
        else getString(R.string.status_send_failed)

    private fun showScanStatus(message: String, colorRes: Int) {
        binding.tvScanStatus.visibility = View.VISIBLE
        binding.tvScanStatus.text = message
        binding.tvScanStatus.setTextColor(getColor(colorRes))
    }

    private fun showConfirmStatus(message: String, colorRes: Int) {
        binding.tvConfirmStatus.visibility = View.VISIBLE
        binding.tvConfirmStatus.text = message
        binding.tvConfirmStatus.setTextColor(getColor(colorRes))
    }

    override fun onResume() {
        super.onResume()
        val barcodeFilter = IntentFilter("com.scanner.broadcast")
        val rfidFilter = IntentFilter("com.rscja.scanner.action.scanner.RFID")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(barcodeReceiver, barcodeFilter, Context.RECEIVER_EXPORTED)
            registerReceiver(rfidReceiver, rfidFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(barcodeReceiver, barcodeFilter)
            registerReceiver(rfidReceiver, rfidFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(barcodeReceiver)
        unregisterReceiver(rfidReceiver)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishBackward()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
    }
}

/** Minimal TextWatcher wrapper so field listeners read as one line at the call site. */
private class SimpleTextWatcher(private val onChanged: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) = onChanged()
}
```

- [ ] **Step 4: Rewire the dashboard, manifest, and deletions**

1. `app\src\main\res\layout\activity_main.xml`: line 163 `android:id="@+id/tileBagPairing"` → `android:id="@+id/tileOffload"`; line 192 `android:text="@string/tab_bag_pairing"` → `android:text="@string/tab_offload"`.
2. `MainActivity.kt`: replace the `tileBagPairing` click block (lines 74-76) with

```kotlin
        binding.tileOffload.setOnClickListener {
            startActivityForward(Intent(this, OffloadActivity::class.java))
        }
```

   and update the two remaining `binding.tileBagPairing` references (gating line from Task 2, and `applyPressScaleFeedback()`) to `binding.tileOffload`.
3. `AndroidManifest.xml`: replace the `.BagPairingActivity` entry (lines 59-63) with

```xml
        <activity
            android:name=".OffloadActivity"
            android:exported="false"
            android:label="Offload"
            android:theme="@style/Theme.SysOneScanner" />
```

4. Delete `app\src\main\java\com\mitas\ppnam\station1\BagPairingActivity.kt` and `app\src\main\res\layout\activity_bag_pairing.xml`.
5. Confirm nothing else references the old names: `git grep -i "bag_pairing" -- app/src` and `git grep "BagPairing" -- app/src` must both return nothing.

- [ ] **Step 5: Verify build and tests**

Run: `.\gradlew.bat :app:testDebugUnitTest` then `.\gradlew.bat :app:assembleDebug`
Expected: both BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```powershell
git add -A app/src app/build.gradle.kts
git commit -m @'
Replace Bag Pairing with the two-step Offload workflow

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 8: Contract docs in-repo and final verification

**Files:**
- Create: `docs\Station1_MQTT_Contract_v3.md` (copy of the spec)
- Modify: `LOGIN_MQTT_CONTRACT.md` (superseded pointer)

- [ ] **Step 1: Copy the contract into the repo**

```powershell
New-Item -ItemType Directory -Force docs; Copy-Item "C:\Dev\Clients\PPNAM\Windows\PPNAM-Station-1-App\docs rev 2\Station1_MQTT_Contract_Rev_2.md" "docs\Station1_MQTT_Contract_v3.md"
```

- [ ] **Step 2: Replace `LOGIN_MQTT_CONTRACT.md` content**

Replace the entire file with:

```markdown
# Station 1 Login MQTT Contract — SUPERSEDED

This document is superseded by **contract v3.0.0**: see
[`docs/Station1_MQTT_Contract_v3.md`](docs/Station1_MQTT_Contract_v3.md)
(authoritative source: `PPNAM-Station-1-App/docs rev 2/Station1_MQTT_Contract_Rev_2.md`
in the Windows repo; shared login/session authority: Station 2 `RFID_MQTT_CONTRACT.md`,
schema 4.1).

What changed for this app in 3.0.0:

- Authentication requests use the schema 4.1 envelope (`messageId`, `schemaVersion: "4.1"`,
  `deviceId`, six-fractional-digit `timestampUtc`); responses are correlated on
  `inResponseToMessageId` and branched on `accepted`/`errorCode`. Envelope/routing failures
  arrive on `res/request_rejected`.
- The SCRAM proof response is `res/scram_proof_result` (badge login still answers
  `res/operator_context`).
- `allowedTabs` values are `tag_assignment` and `offload`; a missing or empty list enables
  no workflows (fail closed).
- Tag Assignment consumes `res/tag_scan_result` (echoing `tagId`) instead of treating the
  PUBACK as success.
- Bag Pairing is replaced by the two-step Offload workflow: `offload_scan` →
  `offload_scan_result` (bagWeight/bagCount/batchReference prefill) → operator edit/confirm →
  `offload_confirm` → `offload_confirm_result`.
```

- [ ] **Step 3: Full verification**

Run, in order:
1. `.\gradlew.bat :app:testDebugUnitTest` — all unit tests pass.
2. `.\gradlew.bat :app:assembleDebug` — APK builds.
3. Optional, if the C72 is connected (`adb devices` shows it): `adb install -r app\build\outputs\apk\debug\app-debug.apk`, then exercise login / Tag Assignment / Offload against the station simulator per the documented C72 testing procedures (badge scan simulation via `adb shell am broadcast -a com.rscja.scanner.action.scanner.RFID --es data <TAG>`; station responses faked by publishing to `PPNAM/station_1/{deviceId}/res/...`). Station-side handlers for the new messages may not exist yet — timeouts there are expected until the Windows backend lands its §10 deltas.

- [ ] **Step 4: Commit**

```powershell
git add docs/Station1_MQTT_Contract_v3.md LOGIN_MQTT_CONTRACT.md docs/superpowers/plans/2026-08-25-mqtt-contract-3.0.0.md
git commit -m @'
Adopt MQTT contract v3.0.0 and supersede the login contract doc

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

## Out of scope (deliberately)

- **Station 1 Windows backend** deltas (§10 backend items 1-4) — separate repo/plan.
- **Manager/Admin scoped authorization (§4.5)** — reserved; the contract forbids exposing a Manager/Admin scanner flow until a workflow consumes it. The app has none; nothing to do.
- **Byte-identical retry of auth requests (§4.1)** — the app never auto-retries auth requests; each tap mints a new operation. `Schema41.newMessageId` documents the reuse rule for when a retry path is added.
- **Broker credentials in code** (`MqttManager` hardcodes admin/admin) — pre-existing transport concern outside this contract change.
