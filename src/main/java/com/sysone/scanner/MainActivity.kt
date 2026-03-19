package com.sysone.scanner

import android.content.Context
import android.content.Intent
import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import android.text.Editable
import android.text.TextWatcher
import android.view.View

// Chainway SDK Imports
import com.rscja.barcode.BarcodeDecoder
import com.rscja.barcode.BarcodeFactory
import com.rscja.deviceapi.RFIDWithUHFUART
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback
import com.rscja.deviceapi.entity.UHFTAGInfo

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
    private var rfidPower = 30

    private val scanIdleAdvanceMs = 120L

    private var mqtt: Mqtt3AsyncClient? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private var currentSubTopic = "offload"

    // Hardware Instances
    private var mReader: RFIDWithUHFUART? = null
    private var mBarcodeDecoder: BarcodeDecoder? = null
    private var isBarcodeOpen = false
    private var isRfidInventoryRunning = false

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
        disableSoftKeyboard(binding.etBagBarcode)
        disableSoftKeyboard(binding.etAssignmentTagId)

        setupAutoSend(binding.etAssignmentTagId)

        binding.btnSubmitWeight.setOnClickListener { onSubmitBagWeight() }
        binding.imgLogo.setOnClickListener { showPasswordDialog() }

        updateStatusUI(ConnectionStatus.CONNECTING)
        initMqttAndConnect()
        
        initHardware()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", "C72-001") ?: "C72-001"
        rfidPower = prefs.getInt("rfid_power", 30)
        baseTopic = "PPNAM/$deviceId"
        statusTopic = "$baseTopic/status"
        
        val mode = prefs.getString("mode", "BAG_WEIGHT")
        currentSubTopic = if (mode == "TAG_ASSIGNMENT") "assignment" else "offload"
    }

    private fun initHardware() {
        AsyncTask.execute {
            try {
                // Initialize RFID
                mReader = RFIDWithUHFUART.getInstance()
                val rfidInit = mReader?.init(this@MainActivity) ?: false

                if (rfidInit) {
                    mReader?.setInventoryCallback(object : IUHFInventoryCallback {
                        override fun callback(tag: UHFTAGInfo) {
                            this@MainActivity.runOnUiThread { onTagRead(tag.epc) }
                        }
                    })
                }

                // Get barcode decoder instance but do NOT open it yet.
                // The C72 trigger key is intercepted by the barcode service when open,
                // preventing RFID from receiving the key event.
                // We open/close the barcode decoder based on which field has focus.
                mBarcodeDecoder = BarcodeFactory.getInstance().barcodeDecoder

                this@MainActivity.runOnUiThread {
                    if (rfidInit) {
                        applyRfidPower()
                    } else {
                        showToast("RFID Init Failed")
                    }
                    // Set up focus-based hardware switching now that decoder is ready
                    setupHardwareSwitching()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Hardware Init Error", e)
            }
        }
    }

    private fun openBarcodeScanner() {
        if (isBarcodeOpen) return
        val result = mBarcodeDecoder?.open(this) ?: false
        if (result) {
            isBarcodeOpen = true
            mBarcodeDecoder?.setDecodeCallback { barcodeEntity ->
                if (barcodeEntity.resultCode == BarcodeDecoder.DECODE_SUCCESS) {
                    this@MainActivity.runOnUiThread {
                        binding.etBagBarcode.setText(barcodeEntity.barcodeData)
                    }
                }
            }
            Log.d(TAG, "Barcode scanner opened")
        } else {
            Log.e(TAG, "Barcode scanner failed to open")
        }
    }

    private fun closeBarcodeScanner() {
        if (!isBarcodeOpen) return
        mBarcodeDecoder?.stopScan()
        mBarcodeDecoder?.close()
        isBarcodeOpen = false
        Log.d(TAG, "Barcode scanner closed")
    }

    private fun applyRfidPower() {
        AsyncTask.execute {
            val result = mReader?.setPower(rfidPower) ?: false
            Log.d(TAG, "Setting RFID Power to $rfidPower: $result")
        }
    }

    private fun startRfidInventory() {
        if (isRfidInventoryRunning) return
        AsyncTask.execute {
            if (mReader?.startInventoryTag() == true) {
                isRfidInventoryRunning = true
                Log.d(TAG, "RFID Inventory Started")
            }
        }
    }

    private fun stopRfidInventory() {
        if (!isRfidInventoryRunning) return
        AsyncTask.execute {
            if (mReader?.stopInventory() == true) {
                isRfidInventoryRunning = false
                Log.d(TAG, "RFID Inventory Stopped")
            }
        }
    }

    private fun onTagRead(epc: String) {
        if (binding.etBagTagId.hasFocus()) {
            binding.etBagTagId.setText(epc)
        } else if (binding.etAssignmentTagId.hasFocus()) {
            binding.etAssignmentTagId.setText(epc)
        }
    }

    private fun isRfidField(): Boolean {
        return binding.etBagTagId.hasFocus() || binding.etAssignmentTagId.hasFocus()
    }

    private fun isBarcodeField(): Boolean {
        return binding.etBagBarcode.hasFocus()
    }

    private fun setupHardwareSwitching() {
        // When barcode field gains focus, open barcode scanner (so trigger activates it)
        // When it loses focus, close it (so trigger can be used for RFID)
        binding.etBagBarcode.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                openBarcodeScanner()
            } else {
                closeBarcodeScanner()
            }
        }

        // Capture keyboard-emulated barcode input (scanner types chars + Enter/Tab)
        binding.etBagBarcode.setOnKeyListener { _, keyCode, event ->
            if ((keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_TAB) 
                && event.action == KeyEvent.ACTION_UP) {
                // Barcode scan complete — strip any newlines
                val text = binding.etBagBarcode.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    binding.etBagBarcode.setText(text)
                    binding.etBagBarcode.setSelection(text.length)
                }
                true
            } else {
                false
            }
        }

        // Ensure RFID fields close barcode when focused
        val rfidFocusListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                closeBarcodeScanner()
            }
        }
        binding.etBagTagId.onFocusChangeListener = rfidFocusListener
        binding.etAssignmentTagId.onFocusChangeListener = rfidFocusListener
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == 139 || keyCode == 280) {
            if (isRfidField()) {
                startRfidInventory()
                return true
            }
            // When barcode field is focused, let the system handle the trigger
            // (barcode decoder is open and will fire automatically)
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == 139 || keyCode == 280) {
            if (isRfidField()) {
                stopRfidInventory()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
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
            currentSubTopic = "offload"
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
                uiHandler.postDelayed(idle, scanIdleAdvanceMs)
            }
        })
    }

    private fun onSubmitBagWeight() {
        val tagId = binding.etBagTagId.text?.toString()?.trim().orEmpty()
        val barcode = binding.etBagBarcode.text?.toString()?.trim().orEmpty()
        val weightStr = binding.spinnerWeights.text.toString().replace(" kg", "")
        val weight = weightStr.toIntOrNull() ?: 0

        if (tagId.isEmpty()) {
            showToast("Tag ID is required")
            return
        }
        if (barcode.isEmpty()) {
            showToast("Barcode is required")
            return
        }

        publishScan(tagId, barcode, weight)
        
        // Reset fields
        binding.etBagTagId.setText("")
        binding.etBagBarcode.setText("")
        binding.etBagTagId.requestFocus()
    }

    private fun finalizeAndSendAssignment() {
        val tagId = binding.etAssignmentTagId.text?.toString()?.trim().orEmpty()
        if (tagId.isEmpty()) return

        publishScan(tagId, null, null)
        
        binding.etAssignmentTagId.setText("")
        binding.etAssignmentTagId.requestFocus()
    }

    private fun publishScan(tagId: String, barcode: String?, weight: Int?) {
        if (mqtt?.state != MqttClientState.CONNECTED) {
            showToast("MQTT Not Connected")
            return
        }

        val json = JSONObject().apply {
            put("ts", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("deviceId", deviceId)
            put("tagId", tagId)
            barcode?.let { put("barcode", it) }
            weight?.let { put("bag_size", it.toString()) }
        }

        val topic = "$baseTopic/$currentSubTopic"
        mqtt?.publishWith()
            ?.topic(topic)
            ?.payload(json.toString().toByteArray(StandardCharsets.UTF_8))
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.send()
            ?.whenComplete { _, throwable: Throwable? ->
                this@MainActivity.runOnUiThread {
                    if (throwable != null) {
                        showToast("Publish Failed: ${throwable.message}")
                    } else {
                        addToHistory(tagId, barcode)
                    }
                }
            }
    }

    private fun addToHistory(tagId: String, barcode: String?) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val label = if (barcode != null) "$tagId ($barcode)" else tagId
        val entry = "[$time] $label"

        val historyText = binding.tvHistory.text.toString()
        val lines = if (historyText.isEmpty()) mutableListOf() else historyText.split("\n").toMutableList()
        
        lines.add(0, entry)
        if (lines.size > MAX_HISTORY_ITEMS) {
            lines.removeAt(lines.size - 1)
        }
        
        binding.tvHistory.text = lines.joinToString("\n")
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
                this@MainActivity.runOnUiThread {
                    if (throwable != null) {
                        Log.e(TAG, "MQTT Connection Failed", throwable)
                        updateStatusUI(ConnectionStatus.OFFLINE)
                        scheduleReconnect()
                    } else {
                        Log.i(TAG, "MQTT Connected")
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

        mReader?.free()
        closeBarcodeScanner()
    }
}
