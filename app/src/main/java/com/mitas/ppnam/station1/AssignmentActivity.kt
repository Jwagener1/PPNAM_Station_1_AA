package com.mitas.ppnam.station1

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
import com.mitas.ppnam.station1.databinding.ActivityAssignmentBinding
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant

class AssignmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssignmentBinding
    private var scanner_int = 1
    private var station_int = 1
    private val bagSizeOptions = listOf(450, 500, 600, 750, 1000)

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread { binding.connectionPill.setStatus(status) }
    }

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
        forceLightStatusBarIcons()

        loadSettings()
        setupToolbar()
        setupBagSizeSpinner()
        setupBatchRefToggle()
        
        subscribeToResults()
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

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
        station_int = prefs.getInt("station_int", 1)
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
        binding.spinnerBagWeight.setText(bagSizeOptions[0].toString(), false)
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

    private fun subscribeToResults() {
        val mqtt = MqttManager.getInstance(this)
        val offloadResultTopic = "PPNAM/station_$station_int/offload_result"
        val allOffloadedResultTopic = "PPNAM/station_$station_int/all_offloaded_result"

        mqtt.subscribe(offloadResultTopic) { publish ->
            val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
            handleOffloadResult(payload)
        }

        mqtt.subscribe(allOffloadedResultTopic) { publish ->
            val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
            handleAllOffloadedResult(payload)
        }
    }

    private fun handleOffloadResult(payload: String) {
        try {
            val json = JSONObject(payload)
            if (!MqttManager.getInstance(this).isRelevantToThisScanner(json)) return
            val status = json.optString("status", "Unknown")
            val message = json.optString("message", "")
            val pairValidated = json.optBoolean("pairValidated", false)
            val productCode = json.optString("productCode", "")
            val palletCode = json.optString("palletCode", "")

            runOnUiThread {
                binding.btnSubmit.isEnabled = true
                if (status.equals("Success", ignoreCase = true)) {
                    Toast.makeText(this, "Success: $palletCode updated.", Toast.LENGTH_SHORT).show()
                    clearInputs()
                } else if (status.equals("Mismatch", ignoreCase = true)) {
                    AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
                        .setTitle("Pairing Mismatch")
                        .setMessage("$message\n\nPallet: $palletCode\nProduct: $productCode\nValidated: $pairValidated")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
                        .setTitle("Offload Failed")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleAllOffloadedResult(payload: String) {
        try {
            val json = JSONObject(payload)
            if (!MqttManager.getInstance(this).isRelevantToThisScanner(json)) return
            val status = json.optString("status", "Unknown")
            val message = json.optString("message", "")
            val finalizedCount = json.optInt("finalizedPalletCount", 0)
            val incompleteCount = json.optInt("incompletePalletCount", 0)

            runOnUiThread {
                binding.btnAllOffloaded.isEnabled = true
                if (status.equals("Success", ignoreCase = true)) {
                    // Reset workflow on completion
                    getSharedPreferences("sap_data", Context.MODE_PRIVATE).edit()
                        .remove("session_id")
                        .putInt("current_step", 0)
                        .apply()

                    AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
                        .setTitle("Session Complete")
                        .setMessage("$message\n\nFinalized: $finalizedCount\nIncomplete: $incompleteCount")
                        .setPositiveButton("Done") { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                } else {
                    AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
                        .setTitle("Completion Error")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun clearInputs() {
        binding.etBarcode.setText("")
        binding.etRfid.setText("")
        binding.spinnerBagWeight.setText(bagSizeOptions[0].toString(), false)
        if (!binding.cbUseDefaultBatchRef.isChecked) binding.etBatchRef.setText("")
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
            getSessionId().takeIf { it.isNotBlank() }?.let { put("sessionId", it) }
            put("tagId", rfid)
            put("barcode", barcode)
            put("batchRef", if (useDefault) "" else batchRef)
            put("useDefaultBatchRef", useDefault)
            put("bagWeightKg", bagSize)
        }

        val topic = "PPNAM/$deviceId/offload"
        binding.btnSubmit.isEnabled = false
        MqttManager.getInstance(this).publish(topic, payload.toString()) { throwable ->
            runOnUiThread {
                if (throwable != null) {
                    Toast.makeText(this, "Publish Failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                    binding.btnSubmit.isEnabled = true
                } else {
                    Toast.makeText(this, "Sending offload data...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showAllOffloadedDialog() {
        AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
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
            getSessionId().takeIf { it.isNotBlank() }?.let { put("sessionId", it) }
            put("sourceDocumentNumber", sourceDocNum)
            put("allOffloaded", true)
        }

        val topic = "PPNAM/$deviceId/all_offloaded"
        binding.btnAllOffloaded.isEnabled = false
        MqttManager.getInstance(this).publish(topic, payload.toString()) { throwable ->
            runOnUiThread {
                if (throwable != null) {
                    Toast.makeText(this, "Publish Failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                    binding.btnAllOffloaded.isEnabled = true
                } else {
                    Toast.makeText(this, "Confirming completion...", Toast.LENGTH_SHORT).show()
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
        val mqtt = MqttManager.getInstance(this)
        mqtt.removeConnectionStatusListener(connectionStatusListener)
        mqtt.unsubscribe("PPNAM/station_$station_int/offload_result")
        mqtt.unsubscribe("PPNAM/station_$station_int/all_offloaded_result")
    }
}
