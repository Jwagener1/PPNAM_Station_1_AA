package com.sysone.scanner

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
    
    private val subscriptions = ConcurrentHashMap<String, CopyOnWriteArrayList<(Mqtt3Publish) -> Unit>>()

    var isStationOnline = true
        private set

    private val brokerHost = "mqtt.sysone.co.za"
    private val brokerPort = 443
    private val mqttUsername = "admin"
    private val mqttPassword = "admin"

    private val disconnectedListener = MqttClientDisconnectedListener { context: MqttClientDisconnectedContext ->
        Log.w("MqttManager", "Disconnected from broker: ${context.cause.message}")
        notifyListeners(false)
        
        // Auto-reconnect logic
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isConnected()) {
                connect(force = true)
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

    fun connect(force: Boolean = false) {
        if (!force && (isConnected() || isConnecting.get())) return
        
        if (force) {
            client?.disconnect()
        }

        isConnecting.set(true)

        val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val scannerInt = prefs.getInt("scanner_int", 1)
        val statusTopic = "PPNAM/scanner_$scannerInt/status"

        client = MqttClient.builder()
            .useMqttVersion3()
            .identifier("ScannerApp_" + UUID.randomUUID().toString().take(8))
            .serverHost(brokerHost)
            .serverPort(brokerPort)
            .sslWithDefaultConfig()
            .webSocketWithDefaultConfig()
            .addDisconnectedListener(disconnectedListener)
            .buildAsync()

        client?.connectWith()
            ?.simpleAuth()
                ?.username(mqttUsername)
                ?.password(mqttPassword.toByteArray())
                ?.applySimpleAuth()
            ?.keepAlive(15)
            ?.cleanSession(true)
            ?.willPublish()
                ?.topic(statusTopic)
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
                    publish(statusTopic, "online", true)
                    
                    // Subscribe to the global PPNAM topic
                    subscribeInternal("PPNAM/#")
                    
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
            // Removed internal dispatching here as it's now handled in subscribeInternal callback
        }
    }

    private fun subscribeInternal(topicFilter: String) {
        client?.subscribeWith()
            ?.topicFilter(topicFilter)
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.callback { publish ->
                val topic = publish.topic.toString()
                
                // Internal filtering for Station Status
                if (topic == "PPNAM/station_1/status") {
                    val payload = String(publish.payloadAsBytes, Charsets.UTF_8).lowercase()
                    val online = payload == "online"
                    if (online != isStationOnline) {
                        isStationOnline = online
                        if (!online) {
                            resetWorkflow()
                        }
                        notifyStationListeners(online)
                    }
                }

                // Global dispatch to other subscribers
                subscriptions[topic]?.forEach { callback ->
                    callback(publish)
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
    }

    private fun notifyStationListeners(online: Boolean) {
        stationStatusListeners.forEach { it(online) }
    }

    private fun resetWorkflow() {
        appContext.getSharedPreferences("sap_data", Context.MODE_PRIVATE).edit()
            .remove("session_id")
            .putInt("current_step", 0)
            .apply()
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

    fun disconnect() {
        if (!isConnected()) return
        val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val scannerInt = prefs.getInt("scanner_int", 1)
        
        // 1. Notify local listeners immediately
        notifyListeners(false)
        
        // 2. Publish offline status manually before disconnecting
        publish("PPNAM/scanner_$scannerInt/status", "offline", true)
        
        // 3. Close the client
        client?.disconnect()
        client = null
    }
}
