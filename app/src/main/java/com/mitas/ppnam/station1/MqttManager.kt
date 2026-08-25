package com.mitas.ppnam.station1

import android.content.Context
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedListener
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.text.Charsets

class MqttManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private var client: Mqtt3AsyncClient? = null
    private val isConnecting = AtomicBoolean(false)
    
    private val connectionListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val stationStatusListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val connectionStatusListeners = CopyOnWriteArrayList<(ConnectionStatus) -> Unit>()
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusRunnable = Runnable {
        val status = resolveStatus()
        connectionStatusListeners.forEach { it(status) }
    }
    /** What the caller wants right now — distinguishes "mid auto-retry" from "user disconnected". */
    private val wantsConnection = AtomicBoolean(true)

    private val subscriptions = ConcurrentHashMap<String, CopyOnWriteArrayList<(Mqtt3Publish) -> Unit>>()

    var isStationOnline = true
        private set

    private val settingsRepository = SettingsRepository(appContext)

    private val disconnectedListener = MqttClientDisconnectedListener { context: MqttClientDisconnectedContext ->
        Log.w("MqttManager", "Disconnected from broker: ${context.cause.message}")
        notifyListeners(false)
        
        // Auto-reconnect logic
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isConnected()) {
                connect()
            }
        }, 5000)
    }

    companion object {
        @Volatile
        private var INSTANCE: MqttManager? = null

        fun getInstance(context: Context): MqttManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MqttManager(context).also { INSTANCE = it }
            }
        }
    }

    fun isConnected(): Boolean = client?.state == MqttClientState.CONNECTED

    fun addConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners.add(listener)
        listener(isConnected())
    }

    fun removeConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners.remove(listener)
    }

    fun addStationStatusListener(listener: (Boolean) -> Unit) {
        stationStatusListeners.add(listener)
        listener(isStationOnline)
    }

    fun removeStationStatusListener(listener: (Boolean) -> Unit) {
        stationStatusListeners.remove(listener)
    }

    /**
     * Resolves what to tell the operator about connectivity, in precedence order,
     * mirroring Station 2's resolveConnectionStatus (minus clock skew, which Station 1
     * has no way to measure).
     */
    private fun resolveStatus(): ConnectionStatus = when {
        !wantsConnection.get() -> ConnectionStatus.OFFLINE
        isConnected() && !isStationOnline -> ConnectionStatus.STATION_OFFLINE
        isConnected() && isStationOnline -> ConnectionStatus.CONNECTED
        else -> ConnectionStatus.RECONNECTING
    }

    /** Debounced like Station 2's connectionStatusFlow, so one stale sample doesn't flash the pill. */
    private fun notifyConnectionStatus() {
        statusHandler.removeCallbacks(statusRunnable)
        statusHandler.postDelayed(statusRunnable, 1_500)
    }

    fun addConnectionStatusListener(listener: (ConnectionStatus) -> Unit) {
        connectionStatusListeners.add(listener)
        listener(resolveStatus())
    }

    fun removeConnectionStatusListener(listener: (ConnectionStatus) -> Unit) {
        connectionStatusListeners.remove(listener)
    }

    fun connect(force: Boolean = false) {
        if (!force && (isConnected() || isConnecting.get())) return

        if (force) {
            disconnect { connect(force = false) }
            return
        }

        val settings = settingsRepository.brokerSettings()
        if (!settings.hasBrokerCredential) {
            // No shared default credential exists to fall back on (Schema 4.1). Until this
            // handheld is provisioned in Settings, stay deliberately offline rather than
            // hammering the broker with a credential we know is wrong.
            Log.w("MqttManager", "No broker credential provisioned; not connecting")
            wantsConnection.set(false)
            notifyConnectionStatus()
            return
        }

        wantsConnection.set(true)
        notifyConnectionStatus()
        isConnecting.set(true)

        // Presence lives on this scanner's base node (retained, also the Last Will) — the
        // contract's presence QoS is 2. The device id is derived from this handheld's hardware
        // (DeviceIdentity), not configured.
        val presenceTopic = MqttTopics.devicePresence(DeviceIdentity.deviceId(appContext))

        var builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier("ScannerApp_" + UUID.randomUUID().toString().take(8))
            .serverHost(settings.host)
            .serverPort(settings.port)
            .addDisconnectedListener(disconnectedListener)
        if (settings.useTls) builder = builder.sslWithDefaultConfig()
        if (settings.useWebSocket) builder = builder.webSocketWithDefaultConfig()
        client = builder.buildAsync()

        client?.connectWith()
            ?.simpleAuth()
                ?.username(settings.username)
                ?.password(settings.password.toByteArray())
                ?.applySimpleAuth()
            ?.keepAlive(15)
            ?.cleanSession(true)
            ?.willPublish()
                ?.topic(presenceTopic)
                ?.payload("offline".toByteArray())
                ?.qos(MqttQos.EXACTLY_ONCE)
                ?.retain(true)
                ?.applyWillPublish()
            ?.send()
            ?.whenComplete { _, throwable ->
                isConnecting.set(false)
                if (throwable == null) {
                    Log.i("MqttManager", "Connected")

                    // Explicitly publish online status to clear any stale LWT
                    publish(presenceTopic, "online", true, MqttQos.EXACTLY_ONCE)

                    // One subscription captures all of this station's traffic, presence included
                    subscribeInternal(MqttTopics.stationWildcard())
                    
                    notifyListeners(true)
                } else {
                    Log.e("MqttManager", "Connection failed", throwable)
                    notifyListeners(false)
                    // Retry after delay
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        connect()
                    }, 5000)
                }
            }
            
        client?.toAsync()?.publishes(MqttGlobalPublishFilter.ALL) { publish ->
            // Routing is handled in subscribeInternal callback
        }
    }

    private fun subscribeInternal(topicFilter: String) {
        client?.subscribeWith()
            ?.topicFilter(topicFilter)
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.callback { publish ->
                val topic = publish.topic.toString()
                
                // Internal filtering for Station Status — retained presence on the station base node
                if (topic == MqttTopics.stationPresence()) {
                    val payload = String(publish.payloadAsBytes, Charsets.UTF_8).lowercase()
                    val online = payload == "online"
                    if (online != isStationOnline) {
                        isStationOnline = online
                        notifyStationListeners(online)
                    }
                }

                // Global dispatch to other subscribers
                subscriptions.forEach { (topicFilter, callbacks) ->
                    if (matches(topic, topicFilter)) {
                        callbacks.forEach { it(publish) }
                    }
                }
            }
            ?.send()
            ?.whenComplete { _, throwable ->
                if (throwable != null) {
                    Log.e("MqttManager", "Internal subscribe failed: $topicFilter", throwable)
                } else {
                    Log.i("MqttManager", "Internally subscribed to: $topicFilter")
                }
            }
    }

    private fun notifyListeners(connected: Boolean) {
        connectionListeners.forEach { it(connected) }
        notifyConnectionStatus()
    }

    private fun notifyStationListeners(online: Boolean) {
        stationStatusListeners.forEach { it(online) }
        notifyConnectionStatus()
    }

    private fun matches(topic: String, filter: String): Boolean {
        if (topic == filter) return true
        if (filter.contains("+")) {
            val topicParts = topic.split("/")
            val filterParts = filter.split("/")
            if (topicParts.size != filterParts.size) return false
            for (i in topicParts.indices) {
                if (filterParts[i] != "+" && filterParts[i] != topicParts[i]) return false
            }
            return true
        }
        if (filter.endsWith("/#")) {
            val prefix = filter.substring(0, filter.length - 2)
            return topic.startsWith(prefix)
        }
        return false
    }

    fun publish(topic: String, payload: String, retain: Boolean = false, qos: MqttQos = MqttQos.AT_LEAST_ONCE, onComplete: (Throwable?) -> Unit = {}) {
        if (!isConnected()) {
            Log.w("MqttManager", "Cannot publish, not connected: $topic")
            onComplete(Exception("Not connected"))
            return
        }
        client?.publishWith()
            ?.topic(topic)
            ?.payload(payload.toByteArray())
            ?.qos(qos)
            ?.retain(retain)
            ?.send()
            ?.whenComplete { _, throwable ->
                onComplete(throwable)
            }
    }

    /**
     * Subscribe to a specific topic. The MqttManager will filter messages from the global PPNAM/# subscription.
     */
    fun subscribe(topic: String, callback: (Mqtt3Publish) -> Unit) {
        val callbacks = subscriptions.getOrPut(topic) { CopyOnWriteArrayList() }
        callbacks.add(callback)
        Log.d("MqttManager", "Added subscriber for topic: $topic")
    }
    
    fun unsubscribe(topic: String, callback: ((Mqtt3Publish) -> Unit)? = null) {
        if (callback == null) {
            subscriptions.remove(topic)
            Log.d("MqttManager", "Removed all subscribers for topic: $topic")
        } else {
            subscriptions[topic]?.remove(callback)
            if (subscriptions[topic]?.isEmpty() == true) {
                subscriptions.remove(topic)
            }
            Log.d("MqttManager", "Removed specific subscriber for topic: $topic")
        }
    }

    /**
     * Per HANDSCANNER_MQTT_FLOW.md #9: station _result/_response topics are shared
     * across all scanners, so isolation is done via the payload's deviceId field.
     * Accept when deviceId is missing/not a "scanner_*" id/matches this scanner;
     * drop when it names a different scanner.
     */
    fun isRelevantToThisScanner(payload: JSONObject): Boolean {
        val deviceId = payload.optString("deviceId", "")
        if (deviceId.isEmpty() || !deviceId.startsWith("scanner_")) return true
        return deviceId == DeviceIdentity.deviceId(appContext)
    }

    fun disconnect(onComplete: () -> Unit = {}) {
        wantsConnection.set(false)
        notifyConnectionStatus()
        if (!isConnected()) {
            onComplete()
            return
        }
        notifyListeners(false)

        // Publish offline status and wait for it to complete before disconnecting
        publish(MqttTopics.devicePresence(DeviceIdentity.deviceId(appContext)), "offline", true, MqttQos.EXACTLY_ONCE) { throwable ->
            if (throwable != null) {
                Log.e("MqttManager", "Failed to publish offline status during disconnect", throwable)
            }
            client?.disconnect()?.whenComplete { _, _ ->
                client = null
                onComplete()
            }
        }
    }
}
