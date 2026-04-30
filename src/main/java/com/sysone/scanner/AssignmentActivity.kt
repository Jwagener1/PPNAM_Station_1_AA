package com.sysone.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.sysone.scanner.databinding.ActivityAssignmentBinding
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

class AssignmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssignmentBinding
    private var scanner_int = 1
    private var mqtt: Mqtt3AsyncClient? = null

    // Bag size options in kg
    private val bagSizeOptions = listOf(450, 500, 600, 750, 1000)

    private val barcodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.scanner.broadcast") {
                val data = intent.getStringExtra("data")
                if (!data.isNullOrEmpty()) {
                    binding.etBarcode.setText(data)
                }
            }
        }
    }

    private val rfidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.rscja.scanner.action.scanner.RFID") {
                val data = intent.getStringExtra("data")
                if (!data.isNullOrEmpty()) {
                    binding.etRfid.setText(data)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAssignmentBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)

        loadSettings()
        setupToolbar()
        setupBagSizeSpinner()
        setupBatchRefToggle()
        initMqtt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.btnSubmit.setOnClickListener { validateAndSubmit() }
        binding.btnAllOffloaded.setOnClickListener { showAllOffloadedDialog() }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        scanner_int = prefs.getInt("scanner_int", 1)
    }

    private fun getSessionId(): String {
        return getSharedPreferences("sap_data", Context.MODE_PRIVATE)
            .getString("session_id", "") ?: ""
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Offloading"
    }

    private fun setupBagSizeSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, bagSizeOptions)
        binding.spinnerBagWeight.setAdapter(adapter)

        // Set default value (convert Int → String)
        binding.spinnerBagWeight.setText(bagSizeOptions[0].toString(), false)

        // Prevent manual typing (dropdown only)
        binding.spinnerBagWeight.keyListener = null
    }

    private fun setupBatchRefToggle() {
        binding.tilBatchRef.visibility = View.VISIBLE

        binding.cbUseDefaultBatchRef.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.tilBatchRef.visibility = View.GONE
                binding.etBatchRef.setText("")
            } else {
                binding.tilBatchRef.visibility = View.VISIBLE
            }
        }
    }

    private fun initMqtt() {
        mqtt = MqttClient.builder()
            .useMqttVersion3()
            .identifier("OFFLOAD_" + UUID.randomUUID().toString().take(8))
            .serverHost("mqtt.sysone.co.za")
            .serverPort(443)
            .sslWithDefaultConfig()
            .webSocketWithDefaultConfig()
            .buildAsync()

        mqtt?.connectWith()
            ?.simpleAuth()
            ?.username("admin")
            ?.password("admin".toByteArray())
            ?.applySimpleAuth()
            ?.send()
            ?.whenComplete { _, throwable ->
                if (throwable != null) {
                    runOnUiThread {
                        Toast.makeText(this, "MQTT Connect Failed", Toast.LENGTH_LONG).show()
                    }
                }
            }
    }

    private fun validateAndSubmit() {
        val barcode = binding.etBarcode.text.toString().trim()
        val rfid = binding.etRfid.text.toString().trim()
        val bagSize = binding.spinnerBagWeight.text.toString().trim().toIntOrNull()
        val useDefault = binding.cbUseDefaultBatchRef.isChecked
        val batchRef = binding.etBatchRef.text.toString().trim()

        if (barcode.isEmpty() || rfid.isEmpty()) {
            Toast.makeText(this, "Barcode and RFID Tag are required", Toast.LENGTH_SHORT).show()
            return
        }

        if (bagSize == null) {
            Toast.makeText(this, "Invalid bag weight", Toast.LENGTH_SHORT).show()
            return
        }

        if (!useDefault && batchRef.isEmpty()) {
            Toast.makeText(this, "Please enter a Batch Reference or select Use Default", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceId = "scanner_$scanner_int"

        val payload = JSONObject().apply {
            put("ts", Instant.now().toString())
            put("deviceId", deviceId)
            put("sessionId", getSessionId())
            put("tagId", rfid)
            put("barcode", barcode)
            put("batchRef", if (useDefault) "" else batchRef)
            put("useDefaultBatchRef", useDefault)
            put("bagWeightKg", bagSize) // ✅ INT VALUE
        }

        val topic = "PPNAM/$deviceId/offload"
        publishToMqtt(topic, payload.toString(), "Offload Data Sent") {
            binding.etBarcode.setText("")
            binding.etRfid.setText("")
            binding.spinnerBagWeight.setText(bagSizeOptions[0].toString(), false)
            if (!useDefault) binding.etBatchRef.setText("")
        }
    }

    private fun showAllOffloadedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Confirm All Offloaded")
            .setMessage("Are you sure all items have been offloaded?")
            .setPositiveButton("Yes") { _, _ -> submitAllOffloaded() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun submitAllOffloaded() {
        val sapPrefs = getSharedPreferences("sap_data", Context.MODE_PRIVATE)
        val sourceDocNum = sapPrefs.getString("last_doc_number", "") ?: ""
        val deviceId = "scanner_$scanner_int"

        val payload = JSONObject().apply {
            put("ts", Instant.now().toString())
            put("deviceId", deviceId)
            put("sessionId", getSessionId())
            put("sourceDocumentNumber", sourceDocNum)
            put("allOffloaded", true)
        }

        val topic = "PPNAM/$deviceId/all_offloaded"
        publishToMqtt(topic, payload.toString(), "All Offloaded Confirmed") {
            finish()
        }
    }

    private fun publishToMqtt(topic: String, json: String, successMsg: String, onSuccess: () -> Unit) {
        if (mqtt?.state != MqttClientState.CONNECTED) {
            Toast.makeText(this, "MQTT Not Connected", Toast.LENGTH_SHORT).show()
            return
        }

        mqtt?.publishWith()
            ?.topic(topic)
            ?.payload(json.toByteArray(StandardCharsets.UTF_8))
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.send()
            ?.whenComplete { _, throwable ->
                runOnUiThread {
                    if (throwable != null) {
                        Toast.makeText(this, "Publish Failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, successMsg, Toast.LENGTH_SHORT).show()
                        onSuccess()
                    }
                }
            }
    }

    override fun onResume() {
        super.onResume()
        val barcodeFilter = IntentFilter("com.scanner.broadcast")
        val rfidFilter = IntentFilter("com.rscja.scanner.action.scanner.RFID")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(barcodeReceiver, barcodeFilter, Context.RECEIVER_EXPORTED)
            registerReceiver(rfidReceiver, rfidFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(barcodeReceiver, barcodeFilter)
            registerReceiver(rfidReceiver, rfidFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(barcodeReceiver)
        unregisterReceiver(rfidReceiver)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        mqtt?.disconnect()
    }
}