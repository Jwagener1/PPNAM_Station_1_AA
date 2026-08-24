package com.mitas.ppnam.station1

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The two sub-apps this handheld offers. Wire values follow Station 2's snake_case vocabulary —
 * the login's `operator_context` lists what this operator may open in `allowedTabs`.
 */
object StationTab {
    const val TAG_ASSIGNMENT = "tag_assignment"
    const val BAG_PAIRING = "bag_pairing"
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
     * Whether to OFFER [tab] in the UI. Presentation only — never authorisation.
     *
     * Fails OPEN on an empty list, like Station 2: a session that arrived without the hint must
     * render the full UI and let the station reject, rather than silently hiding sub-apps the
     * operator is entitled to.
     */
    fun canShow(tab: String): Boolean = allowedTabs.isEmpty() || tab in allowedTabs
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
