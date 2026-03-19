package com.sysone.scanner

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.sysone.scanner.databinding.ActivityMainBinding
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random
import android.text.Editable
import android.text.TextWatcher
import android.view.View

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ===== MQTT CONFIG =====
    private val brokerHost = "mqtt.sysone.co.za"
    private val brokerPort = 443
    private val mqttUsername = "admin"
    private val mqttPassword = "admin"

    private var deviceId = "C72-001"
    private var baseTopic = "PPNAM/$deviceId"
    private var statusTopic = "$baseTopic/status"

    private val scanIdleAdvanceMs = 120L
    private val moveDebounceMs = 350L

    private var mqtt: Mqtt3AsyncClient? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private var lastMoveAtMs = 0L
    private var currentSubTopic = "bag_weight"

    enum class ConnectionStatus(@get:StringRes val stringResId: Int, val dotDrawableResId: Int) {
        ONLINE(R.string.status_online, R.drawable.status_dot_green),
        OFFLINE(R.string.status_offline, R.drawable.status_dot_red),
        CONNECTING(R.string.status_connecting, R.drawable.status_dot_amber)
    }

    companion object {
        private const val TAG = "SysOneScanner"
        private const val MAX_HISTORY_ITEMS = 5
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

        setupTabs()
        setupWeightsSpinner()
        
        disableSoftKeyboard(binding.etBagTagId)
        disableSoftKeyboard(binding.etAssignmentTagId)

        setupAutoSend(binding.etAssignmentTagId)

        binding.btnSubmitWeight.setOnClickListener { onSubmitBagWeight() }

        binding.imgLogo.setOnClickListener { showPasswordDialog() }

        updateStatusUI(ConnectionStatus.CONNECTING)
        initMqttAndConnect()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", "C72-001") ?: "C72-001"
        baseTopic = "PPNAM/$deviceId"
        statusTopic = "$baseTopic/status"
        
        val mode = prefs.getString("mode", "BAG_WEIGHT")
        currentSubTopic = if (mode == "TAG_ASSIGNMENT") "assignment" else "bag_weight"
    }

    private fun showPasswordDialog() {
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        
        AlertDialog.Builder(this)
            .setTitle("Admin Access")
            .setMessage("Enter password to enter settings")
            .setView(input)
            .setPositiveButton("Enter") { _, _ ->
                if (input.text.toString() == "sysone123") {
                    startActivity(Intent(this, SettingsActivity::class.java))
                } else {
                    showToast("Incorrect password")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupTabs() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", "BAG_WEIGHT")

        binding.tabLayout.clearOnTabSelectedListeners()
        
        if (mode == "BAG_WEIGHT") {
            binding.tabLayout.getTabAt(0)?.select()
            binding.tabLayout.removeTabAt(1)
            binding.layoutBagWeight.visibility = View.VISIBLE
            binding.layoutTagAssignment.visibility = View.GONE
            currentSubTopic = "bag_weight"
        } else {
            binding.tabLayout.getTabAt(1)?.select()
            binding.tabLayout.removeTabAt(0)
            binding.layoutBagWeight.visibility = View.GONE
            binding.layoutTagAssignment.visibility = View.VISIBLE
            currentSubTopic = "assignment"
        }
    }

    private fun setupWeightsSpinner() {
        val weights = listOf("15 kg", "20 kg", "25 kg", "30 kg")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, weights)
        binding.spinnerWeights.setAdapter(adapter)
        binding.spinnerWeights.setText(weights[0], false)
    }

    private fun disableSoftKeyboard(editText: TextInputEditText) {
        editText.showSoftInputOnFocus = false
        editText.isFocusable = true
        editText.isFocusableInTouchMode = true
        editText.isCursorVisible = true
    }

    private fun setupAutoSend(editText: TextInputEditText) {
        editText.setOnKeyListener { _, keyCode, event ->
            val isSuffixKey = keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_TAB ||
                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER

            if (isSuffixKey && event.action == KeyEvent.ACTION_UP) {
                finalizeAndSendAssignment()
                true
            } else {
                false
            }
        }

        val idle = Runnable {
            if (editText.hasFocus() && editText.text?.trim()?.isNotEmpty() == true) {
                finalizeAndSendAssignment()
            }
        }

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!editText.hasFocus()) return
                val text = s?.toString().orEmpty()
                if (text.contains("\n") || text.contains("\r")) {
                    finalizeAndSendAssignment()
                    return
                }
                uiHandler.removeCallbacks(idle)
                if (text.trim().isNotEmpty()) {
                    uiHandler.postDelayed(idle, scanIdleAdvanceMs)
                }
            }
        })
    }

    private fun finalizeAndSendAssignment() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastMoveAtMs < moveDebounceMs) return
        lastMoveAtMs = now

        val tagId = binding.etAssignmentTagId.text?.toString()?.trim().orEmpty()
        if (tagId.isEmpty()) return

        val payload = JSONObject().apply {
            put("ts", OffsetDateTime.now().toString())
            put("deviceId", deviceId)
            put("tagId", tagId)
        }.toString()

        publishMqtt(payload, tagId)
        binding.etAssignmentTagId.setText("")
    }

    private fun onSubmitBagWeight() {
        val tagId = binding.etBagTagId.text?.toString()?.trim().orEmpty()
        val weight = binding.spinnerWeights.text.toString()

        if (tagId.isBlank()) {
            showToast(getString(R.string.error_fill_all_fields))
            return
        }

        val payload = JSONObject().apply {
            put("ts", OffsetDateTime.now().toString())
            put("deviceId", deviceId)
            put("tagId", tagId)
            put("weight", weight)
        }.toString()

        publishMqtt(payload, "$tagId ($weight)")
        binding.etBagTagId.setText("")
        binding.etBagTagId.requestFocus()
    }

    private fun initMqttAndConnect() {
        val clientId = "${deviceId}-" + UUID.randomUUID().toString().take(8)
        mqtt = MqttClient.builder()
            .useMqttVersion3()
            .identifier(clientId)
            .serverHost(brokerHost)
            .serverPort(brokerPort)
            .webSocketConfig().applyWebSocketConfig()
            .useSslWithDefaultConfig()
            .willPublish()
                .topic(statusTopic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(true)
                .payload("offline".toByteArray(StandardCharsets.UTF_8))
                .applyWillPublish()
            .addConnectedListener {
                runOnUiThread {
                    reconnectAttempt = 0
                    isConnecting.set(false)
                    updateStatusUI(ConnectionStatus.ONLINE)
                    mqtt?.publishWith()
                        ?.topic(statusTopic)
                        ?.qos(MqttQos.AT_LEAST_ONCE)
                        ?.retain(true)
                        ?.payload("online".toByteArray(StandardCharsets.UTF_8))
                        ?.send()
                }
            }
            .addDisconnectedListener { 
                runOnUiThread {
                    isConnecting.set(false)
                    updateStatusUI(ConnectionStatus.OFFLINE)
                    scheduleReconnect()
                }
            }
            .buildAsync()

        connectMqtt()
    }

    private fun connectMqtt() {
        val client = mqtt ?: return
        if (client.state == MqttClientState.CONNECTED || client.state == MqttClientState.CONNECTING) return
        if (!isConnecting.compareAndSet(false, true)) return

        updateStatusUI(ConnectionStatus.CONNECTING)
        client.connectWith()
            .keepAlive(0) // Set Keep Alive to 0 to disable the client-side timeout mechanism
            .simpleAuth()
            .username(mqttUsername)
            .password(mqttPassword.toByteArray(StandardCharsets.UTF_8))
            .applySimpleAuth()
            .send()
            .whenComplete { _, err ->
                if (err != null) {
                    runOnUiThread {
                        reconnectAttempt++
                        isConnecting.set(false)
                        updateStatusUI(ConnectionStatus.OFFLINE)
                        scheduleReconnect()
                    }
                }
            }
    }

    private fun scheduleReconnect() {
        reconnectRunnable?.let { uiHandler.removeCallbacks(it) }
        val base = 1000L * (1L shl min(reconnectAttempt, 5))
        val delay = min(base, 30_000L) + Random.nextLong(0, 400)
        
        reconnectRunnable = Runnable { connectMqtt() }.also { uiHandler.postDelayed(it, delay) }
    }

    private fun publishMqtt(json: String, label: String) {
        val client = mqtt
        if (client == null || client.state != MqttClientState.CONNECTED) {
            showToast(getString(R.string.mqtt_not_connected))
            if (currentSubTopic == "assignment") {
                addTransactionToHistory(label, false)
            }
            return
        }

        val fullTopic = "$baseTopic/$currentSubTopic"

        client.publishWith()
            .topic(fullTopic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(json.toByteArray(StandardCharsets.UTF_8))
            .send()
            .whenComplete { _, err ->
                runOnUiThread {
                    if (err != null) {
                        showToast(getString(R.string.mqtt_publish_failed, err.message))
                        if (currentSubTopic == "assignment") {
                            addTransactionToHistory(label, false)
                        }
                    } else {
                        showToast(getString(R.string.message_sent))
                        if (currentSubTopic == "assignment") {
                            addTransactionToHistory(label, true)
                        }
                    }
                }
            }
    }

    private fun addTransactionToHistory(label: String, success: Boolean) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        
        val view = LayoutInflater.from(this).inflate(R.layout.item_transaction, binding.layoutAssignmentHistory, false)
        view.findViewById<TextView>(R.id.tvTxTime).text = time
        view.findViewById<TextView>(R.id.tvTxDesc).text = label
        
        val tvStatus = view.findViewById<TextView>(R.id.tvTxStatus)
        if (success) {
            tvStatus.text = getString(R.string.tx_status_sent)
            tvStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        } else {
            tvStatus.text = getString(R.string.tx_status_fail)
            tvStatus.setTextColor(android.graphics.Color.parseColor("#F44336"))
        }

        binding.layoutAssignmentHistory.addView(view, 0)

        if (binding.layoutAssignmentHistory.childCount > MAX_HISTORY_ITEMS) {
            binding.layoutAssignmentHistory.removeViewAt(MAX_HISTORY_ITEMS)
        }
    }

    private fun updateStatusUI(status: ConnectionStatus) {
        binding.tvStatus.setText(status.stringResId)
        binding.viewStatusDot.setBackgroundResource(status.dotDrawableResId)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
