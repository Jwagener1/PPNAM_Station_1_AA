package com.mitas.ppnam.station1

/**
 * Decides when the scanner must republish its retained `online` presence.
 *
 * A restart faster than the broker's dead-connection detection (~the 15s keepalive) lets the
 * previous connection's Last Will — retained `offline` — land AFTER the new connection's
 * retained `online`, leaving presence stuck at `offline` while the scanner is connected.
 * The app's station-wildcard subscription sees its own presence topic, so it can heal by
 * republishing `online` whenever its own node reads `offline` mid-connection.
 *
 * `wantsConnection` guards the graceful-shutdown race: disconnect() publishes retained
 * `offline` while still connected, and the broker echoes it back before the DISCONNECT
 * completes — that echo must not resurrect `online`.
 */
object PresenceSelfHeal {

    fun shouldRestoreOnline(
        topic: String,
        payload: String,
        ownPresenceTopic: String,
        isConnected: Boolean,
        wantsConnection: Boolean,
    ): Boolean {
        return isConnected &&
            wantsConnection &&
            topic == ownPresenceTopic &&
            payload.trim().lowercase() == "offline"
    }
}
