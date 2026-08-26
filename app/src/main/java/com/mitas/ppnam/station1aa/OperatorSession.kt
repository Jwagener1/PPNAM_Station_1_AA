package com.mitas.ppnam.station1aa

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The two workflows this handheld offers (contract v3.0.0 §3). Wire values arrive in the
 * login response's `allowedTabs`.
 */
object StationTab {
    const val TAG_ASSIGNMENT = "tag_assignment"
    const val OFFLOAD = "offload"
}

/**
 * The logged-in operator, mirroring Station 2 AA's OperatorSession (data/session). Held in memory
 * only — on process death the operator logs in again, exactly as on Station 2's handheld.
 */
data class OperatorSession(
    val operatorSessionId: String,
    val operatorId: String,
    val operatorName: String,
    /** Display and audit only — never branch authorisation on this. */
    val role: String,
    /** A UI display hint only — the station re-checks every request server-side. */
    val allowedActions: List<String> = emptyList(),
    /** A UI display hint only. */
    val allowedTabs: List<String> = emptyList(),
) {
    /**
     * Whether to OFFER [tab] in the UI. Presentation only — the station re-checks every
     * request server-side (ACTION_NOT_ALLOWED).
     *
     * Fails CLOSED (contract v3.0.0 §3): a login that arrived without allowedTabs, or with an
     * empty list, enables no workflows at all.
     */
    fun canShow(tab: String): Boolean = tab in allowedTabs
}

/**
 * In-memory holder with the same role as Station 2's OperatorSessionHolder, in Station 1's
 * listener idiom (matching MqttManager) rather than Flows.
 */
object OperatorSessionHolder {

    @Volatile
    var session: OperatorSession? = null
        private set

    private val listeners = CopyOnWriteArrayList<(OperatorSession?) -> Unit>()

    fun set(session: OperatorSession) {
        this.session = session
        listeners.forEach { it(session) }
    }

    fun clear() {
        session = null
        listeners.forEach { it(null) }
    }

    fun addListener(listener: (OperatorSession?) -> Unit) {
        listeners.add(listener)
        listener(session)
    }

    fun removeListener(listener: (OperatorSession?) -> Unit) {
        listeners.remove(listener)
    }

    fun currentSessionIdOrEmpty(): String = session?.operatorSessionId ?: ""
}
