package com.mitas.ppnam.station1aa

import android.content.Context

/**
 * Broker configuration, mirroring Station 2's SettingsRepository but on this app's existing
 * synchronous SharedPreferences file rather than DataStore.
 *
 * ### The broker password is not stored here
 *
 * Everything else lives in the `settings` preferences file, which is app-private but plaintext on
 * disk and readable on a rooted or debuggable device. The Schema 4.1 handoff requires the
 * provisioned MQTT password to be encrypted at rest under an Android Keystore key, so it is
 * routed through [SecureCredentialStore] instead and never written here.
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val credentialStore = SecureCredentialStore(appContext)
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private object Keys {
        const val MQTT_HOST = "mqtt_host"
        const val MQTT_PORT = "mqtt_port"
        const val MQTT_USE_WEBSOCKET = "mqtt_use_websocket"
        const val MQTT_USE_TLS = "mqtt_use_tls"
        const val MQTT_USERNAME = "mqtt_username"
        /** Obsolete since the app became Station 1 only; removed on the next save. */
        const val LEGACY_STATION_INT = "station_int"
    }

    fun brokerSettings(): BrokerSettings {
        val defaults = BrokerSettings()
        return BrokerSettings(
            host = prefs.getString(Keys.MQTT_HOST, null) ?: defaults.host,
            port = prefs.getInt(Keys.MQTT_PORT, defaults.port),
            useWebSocket = prefs.getBoolean(Keys.MQTT_USE_WEBSOCKET, defaults.useWebSocket),
            useTls = prefs.getBoolean(Keys.MQTT_USE_TLS, defaults.useTls),
            // No `?: "admin"`. An unprovisioned handheld reports no credential rather than
            // silently presenting a shared one — see BrokerSettings.hasBrokerCredential.
            username = prefs.getString(Keys.MQTT_USERNAME, null).orEmpty(),
            password = credentialStore.retrieve().orEmpty(),
        )
    }

    /**
     * Persists [settings]. The password goes to the Keystore first: if that fails we must not
     * leave the app believing it saved a credential it cannot retrieve, so nothing else is
     * written and this returns false.
     */
    fun save(settings: BrokerSettings): Boolean {
        if (settings.password.isNotBlank() && !credentialStore.store(settings.password)) {
            return false
        }
        prefs.edit()
            .putString(Keys.MQTT_HOST, settings.host)
            .putInt(Keys.MQTT_PORT, settings.port)
            .putBoolean(Keys.MQTT_USE_WEBSOCKET, settings.useWebSocket)
            .putBoolean(Keys.MQTT_USE_TLS, settings.useTls)
            .putString(Keys.MQTT_USERNAME, settings.username)
            .remove(Keys.LEGACY_STATION_INT)
            .apply()
        return true
    }

    /** Whether this handheld has been provisioned with its own broker credential. */
    fun isProvisioned(): Boolean = brokerSettings().hasBrokerCredential

    /** Wipes the broker credential. For decommissioning a handheld. */
    fun clearCredential() {
        credentialStore.clear()
        prefs.edit().remove(Keys.MQTT_USERNAME).apply()
    }
}
