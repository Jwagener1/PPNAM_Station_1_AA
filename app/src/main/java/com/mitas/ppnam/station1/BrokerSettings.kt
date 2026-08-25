package com.mitas.ppnam.station1

/**
 * Broker configuration, mirroring Station 2's AppSettings.
 *
 * ### Broker credentials have NO defaults
 *
 * [username] and [password] previously existed as `admin`/`admin` constants inside MqttManager.
 * The Schema 4.1 handoff blocks production on the absence of exactly that: shared handheld
 * credentials, source-code credentials, and APK constants must all be gone, and each handheld
 * must have its own broker credential. A default here is an APK constant — it ships inside the
 * app to every device — so the only correct default is empty.
 *
 * The password is never persisted in this form. [SecureCredentialStore] holds it encrypted under
 * an Android Keystore key; this field carries it in memory only, between being read out of that
 * store and being handed to the MQTT client.
 */
data class BrokerSettings(
    val host: String = "mqtt.sysone.co.za",
    val port: Int = 443,
    val useWebSocket: Boolean = true,
    val useTls: Boolean = true,
    val username: String = "",
    val password: String = "",
) {
    /** True once this handheld has been provisioned with its own broker credential. */
    val hasBrokerCredential: Boolean
        get() = username.isNotBlank() && password.isNotBlank()

    companion object {
        /** Parses a port field, or null when it is not a valid TCP port (1–65535). */
        fun parsePort(text: String): Int? =
            text.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    }
}
