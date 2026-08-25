package com.mitas.ppnam.station1

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Locale

/**
 * The scanner's device id, derived by hashing the Wi-Fi chip's MAC address — no more manually
 * assigned scanner numbers, so two handhelds can never collide by misconfiguration.
 *
 * Format: `scanner_<first 12 hex chars of SHA-256(source)>`, e.g. `scanner_a1b2c3d4e5f6`.
 * Keeping the `scanner_` prefix means topic shapes and the station's payload filtering
 * conventions are unchanged.
 *
 * Source, in order:
 *  1. The wlan0 hardware (MAC) address via [NetworkInterface]. Android 11+ hides this from
 *     non-privileged apps (returns null), but Chainway devices running older Android — and any
 *     build where the app is granted privileged status — still expose it.
 *  2. `Settings.Secure.ANDROID_ID`, which on Android 8+ is a stable 64-bit id unique per
 *     device + signing key — the platform's sanctioned stand-in exactly because the MAC was
 *     locked away.
 *
 * Whichever source produced the id, the result is persisted on first derivation and reused
 * forever after — so an OS update that changes MAC visibility (or a factory-reset-survivor
 * quirk in either source) cannot silently re-identify the scanner mid-life. Clearing app data
 * re-derives it.
 */
object DeviceIdentity {

    private const val TAG = "DeviceIdentity"
    private const val PREFS = "settings"
    private const val KEY_DEVICE_ID = "device_id"
    private const val ID_HEX_LENGTH = 12

    @Volatile
    private var cached: String? = null

    fun deviceId(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }

            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_DEVICE_ID, null)?.let {
                cached = it
                return it
            }

            val mac = wifiMacOrNull()
            val (source, label) = if (mac != null) {
                mac to "wlan0 MAC"
            } else {
                androidId(context) to "ANDROID_ID (MAC unavailable on this Android version)"
            }

            val id = "scanner_" + sha256Hex(source).take(ID_HEX_LENGTH)
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            Log.i(TAG, "Derived device id $id from $label")
            cached = id
            return id
        }
    }

    /**
     * The wlan0 MAC, normalized to lowercase colon-separated hex, or null when the platform
     * withholds it (Android 11+ for normal apps) or hands back the anonymized 02:00:00:00:00:00.
     */
    private fun wifiMacOrNull(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.firstOrNull { it.name.equals("wlan0", ignoreCase = true) }
            ?.hardwareAddress
            ?.joinToString(":") { String.format(Locale.ROOT, "%02x", it) }
            ?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read wlan0 MAC", e)
        null
    }

    // The lint warning exists because ANDROID_ID is unsuitable for tracking *users*; here it
    // identifies a shared, company-owned scanner, which is exactly its sanctioned use.
    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
}
