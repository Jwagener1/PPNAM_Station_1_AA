package com.sysone.scanner

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.sysone.scanner.databinding.ActivityMainBinding
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ===== MQTT CONFIG =====
    private val brokerHost = "mqtt.sysone.co.za"
    private val brokerPort = 443
    private val mqttUsername = "admin"
    private val mqttPassword = "admin"

    private var deviceId = "C72-001"
    private var statusTopic = "PPNAM/C72-001/status"

    private var mqtt: Mqtt3AsyncClient? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    enum class ConnectionStatus(@get:StringRes val stringResId: Int, val dotDrawableResId: Int) {
        ONLINE(R.string.status_online, R.drawable.status_dot_green),
        OFFLINE(R.string.status_offline, R.drawable.status_dot_red),
        CONNECTING(R.string.status_connecting, R.drawable.status_dot_amber)
    }

    private val isConnecting = AtomicBoolean(false)
    private var reconnectAttempt = 0
    private var reconnectRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        loadSettings()

        binding = ActivityMainBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupDashboard()

        updateStatusUI(ConnectionStatus.CONNECTING)
        initMqttAndConnect()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", "C72-001") ?: "C72-001"
        statusTopic = "PPNAM/$deviceId/status"
    }

    private fun setupDashboard() {
        binding.tileManualSap.setOnClickListener {
            startActivity(Intent(this, ManualSapEntryActivity::class.java))
        }
        
        binding.tileBagOffload.setOnClickListener {
            // Placeholder or Navigate to Bag Offload Activity if it exists
            showToast("Bag Offload Clicked")
        }

        binding.tileAssignments.setOnClickListener {
            showToast("Assignments Clicked")
        }

        binding.tileThingsToDo.setOnClickListener {
            showToast("Warehouse Tasks Clicked")
        }
    }

    private fun initMqttAndConnect() {
        if (isConnecting.get()) return
        isConnecting.set(true)

        mqtt = MqttClient.builder()
            .useMqttVersion3()
            .identifier(UUID.randomUUID().toString())
            .serverHost(brokerHost)
            .serverPort(brokerPort)
            .sslWithDefaultConfig()
            .webSocketWithDefaultConfig()
            .buildAsync()

        connectMqtt()
    }

    private fun connectMqtt() {
        mqtt?.connectWith()
            ?.simpleAuth()
                ?.username(mqttUsername)
                ?.password(mqttPassword.toByteArray())
                ?.applySimpleAuth()
            ?.willPublish()
                ?.topic(statusTopic)
                ?.payload("offline".toByteArray())
                ?.qos(MqttQos.AT_LEAST_ONCE)
                ?.retain(true)
                ?.applyWillPublish()
            ?.send()
            ?.whenComplete { _, throwable: Throwable? ->
                isConnecting.set(false)
                runOnUiThread {
                    if (throwable != null) {
                        Log.e("MainActivity", "MQTT Connection Failed", throwable)
                        updateStatusUI(ConnectionStatus.OFFLINE)
                        scheduleReconnect()
                    } else {
                        Log.i("MainActivity", "MQTT Connected")
                        reconnectAttempt = 0
                        updateStatusUI(ConnectionStatus.ONLINE)
                        publishStatus("online")
                    }
                }
            }
    }

    private fun scheduleReconnect() {
        reconnectAttempt++
        val delay = min(30000L, 2000L * reconnectAttempt)
        reconnectRunnable = Runnable { connectMqtt() }
        uiHandler.postDelayed(reconnectRunnable!!, delay)
    }

    private fun publishStatus(status: String) {
        mqtt?.publishWith()
            ?.topic(statusTopic)
            ?.payload(status.toByteArray())
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.retain(true)
            ?.send()
    }

    private fun updateStatusUI(status: ConnectionStatus) {
        binding.tvStatus.setText(status.stringResId)
        binding.imgStatusDot.setImageResource(status.dotDrawableResId)
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectRunnable?.let { uiHandler.removeCallbacks(it) }
        publishStatus("offline")
        mqtt?.disconnect()
    }
}
