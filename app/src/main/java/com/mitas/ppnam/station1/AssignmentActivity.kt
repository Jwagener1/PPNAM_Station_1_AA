package com.mitas.ppnam.station1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputLayout
import com.mitas.ppnam.station1.databinding.ActivityAssignmentBinding
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant

class AssignmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssignmentBinding
    private var scanner_int = 1
    private val bagSizeOptions = listOf(450, 500, 600, 750, 1000)

    /** A pallet already known from an offload_start broadcast or the tag-assignment product list. */
    private data class PalletHint(
        val productCode: String,
        val bagCount: Int?,
        val bagSizeKg: Int?,
        val palletCode: String?,
        val palletRowId: Long?,
    )
    private var palletHintsByTagOrBarcode: Map<String, PalletHint> = emptyMap()
    private var productMeta: Map<String, Pair<String, Int>> = emptyMap()

    /** Set once a scanned barcode/RFID resolves against palletHints; carried into the submit payload. */
    private var resolvedProductCode: String? = null
    private var resolvedPalletCode: String? = null
    private var resolvedPalletRowId: Long? = null

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread { binding.connectionPill.setStatus(status) }
    }

    private val barcodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.scanner.broadcast") {
                val data = intent.getStringExtra("data")
                if (!data.isNullOrEmpty()) {
                    binding.etBarcode.setText(data)
                    applyPalletHintIfMatched()
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
                    applyPalletHintIfMatched()
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
        loadPalletHints()

        subscribeToResults()
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this) { finishBackward() }

        binding.btnSubmit.setOnClickListener { validateAndSubmit() }
        binding.btnAllOffloaded.setOnClickListener { showAllOffloadedDialog() }
        binding.btnSubmit.applyPressScaleFeedback()
        binding.btnAllOffloaded.applyPressScaleFeedback()

        setupValidationWatchers()
        checkFormCompletion()
    }

    /**
     * Loads two possible sources of per-pallet/per-product bag composition, forwarded
     * from wherever this screen was entered from: the offload_start broadcast's
     * palletStates[] (precise, per-pallet - see ScannerApp) or Product Request's
     * product_meta_json (a per-product fallback, from SAP's product response).
     */
    private fun loadPalletHints() {
        val sapPrefs = getSharedPreferences("sap_data", Context.MODE_PRIVATE)

        val palletStatesRaw = intent.getStringExtra("pallet_states_json")
            ?: sapPrefs.getString("pallet_states_json", "")
        if (!palletStatesRaw.isNullOrBlank()) {
            try {
                val array = org.json.JSONArray(palletStatesRaw)
                val map = mutableMapOf<String, PalletHint>()
                for (i in 0 until array.length()) {
                    val p = array.getJSONObject(i)
                    val hint = PalletHint(
                        productCode = p.optString("productCode", ""),
                        bagCount = p.optInt("bagCount", 0).takeIf { it > 0 },
                        bagSizeKg = p.optString("bagSize", "").toIntOrNull(),
                        palletCode = p.optString("palletCode", "").takeIf { it.isNotEmpty() },
                        palletRowId = if (p.has("palletRowId")) p.optLong("palletRowId") else null,
                    )
                    p.optString("tagId", "").takeIf { it.isNotEmpty() }?.let { map[it] = hint }
                    p.optString("barcode", "").takeIf { it.isNotEmpty() }?.let { map[it] = hint }
                }
                palletHintsByTagOrBarcode = map
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val productMetaRaw = intent.getStringExtra("product_meta_json")
            ?: sapPrefs.getString("product_meta_json", "")
        if (!productMetaRaw.isNullOrBlank()) {
            try {
                val json = JSONObject(productMetaRaw)
                val map = mutableMapOf<String, Pair<String, Int>>()
                json.keys().forEach { code ->
                    val entry = json.getJSONObject(code)
                    map[code] = entry.optString("bagSize", "") to entry.optInt("bagCount", 0)
                }
                productMeta = map
                // Single-product session: safe to default the bag fields immediately,
                // same as the operator would if they'd already scanned that one product.
                if (map.size == 1 && palletHintsByTagOrBarcode.isEmpty()) {
                    val (code, meta) = map.entries.first()
                    resolvedProductCode = code
                    meta.first.toIntOrNull()?.let { binding.spinnerBagWeight.setText(it.toString(), false) }
                    if (meta.second > 0) binding.etBagCount.setText(meta.second.toString())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Auto-fills bag size/count/product-code the moment a scanned tag or barcode matches a known pallet. */
    private fun applyPalletHintIfMatched() {
        val barcode = binding.etBarcode.text.toString().trim()
        val rfid = binding.etRfid.text.toString().trim()
        val hint = palletHintsByTagOrBarcode[barcode] ?: palletHintsByTagOrBarcode[rfid] ?: return

        resolvedProductCode = hint.productCode.takeIf { it.isNotEmpty() }
        resolvedPalletCode = hint.palletCode
        resolvedPalletRowId = hint.palletRowId
        hint.bagSizeKg?.let { binding.spinnerBagWeight.setText(it.toString(), false) }
        hint.bagCount?.let { binding.etBagCount.setText(it.toString()) }
    }

    private fun setupValidationWatchers() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = checkFormCompletion()
        }
        binding.etBarcode.addTextChangedListener(watcher)
        binding.etRfid.addTextChangedListener(watcher)
        binding.etBagCount.addTextChangedListener(watcher)
        binding.etBatchRef.addTextChangedListener(watcher)
        binding.spinnerBagWeight.addTextChangedListener(watcher)
        binding.cbUseDefaultBatchRef.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.tilBatchRef.visibility = View.GONE
                binding.etBatchRef.setText("")
            } else {
                binding.tilBatchRef.visibility = View.VISIBLE
            }
            checkFormCompletion()
        }
    }

    private fun updateFieldColor(til: TextInputLayout, valid: Boolean) {
        til.boxStrokeColor = getColor(if (valid) R.color.success else R.color.outline_dark)
    }

    private fun checkFormCompletion() {
        val barcodeOk = binding.etBarcode.text.toString().isNotBlank()
        val rfidOk = binding.etRfid.text.toString().isNotBlank()
        val bagSizeOk = binding.spinnerBagWeight.text.toString().trim().toIntOrNull() != null
        val bagCountOk = (binding.etBagCount.text.toString().trim().toIntOrNull() ?: 0) > 0
        val useDefault = binding.cbUseDefaultBatchRef.isChecked
        val batchRefOk = useDefault || binding.etBatchRef.text.toString().trim().isNotEmpty()

        updateFieldColor(binding.tilBarcode, barcodeOk)
        updateFieldColor(binding.tilRfid, rfidOk)
        updateFieldColor(binding.tilBagWeight, bagSizeOk)
        updateFieldColor(binding.tilBagCount, bagCountOk)
        updateFieldColor(binding.tilBatchRef, batchRefOk)

        val complete = barcodeOk && rfidOk && bagSizeOk && bagCountOk && batchRefOk
        val wasEnabled = binding.btnSubmit.isEnabled
        binding.btnSubmit.isEnabled = complete
        binding.btnSubmit.alpha = if (complete) 1f else 0.4f
        if (complete && !wasEnabled) binding.btnSubmit.flashAttention()
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
        binding.spinnerBagWeight.setText(bagSizeOptions[0].toString(), false)
        binding.spinnerBagWeight.keyListener = null
    }

    private fun setupBatchRefToggle() {
        // Checkbox listener itself is wired in setupValidationWatchers(), so it can
        // also re-run form validation on toggle - this just sets the initial state.
        binding.tilBatchRef.visibility = View.VISIBLE
    }

    private fun subscribeToResults() {
        val mqtt = MqttManager.getInstance(this)
        val offloadResultTopic = "PPNAM/scanner_$scanner_int/res/offload_result"
        val allOffloadedResultTopic = "PPNAM/scanner_$scanner_int/res/all_offloaded_result"

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
                if (status.equals("Success", ignoreCase = true)) {
                    Toast.makeText(this, "Success: $palletCode updated.", Toast.LENGTH_SHORT).show()
                    clearInputs()
                } else if (status.equals("Mismatch", ignoreCase = true)) {
                    checkFormCompletion()
                    AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
                        .setTitle("Pairing Mismatch")
                        .setMessage("$message\n\nPallet: $palletCode\nProduct: $productCode\nValidated: $pairValidated")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    checkFormCompletion()
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
                        .setPositiveButton("Done") { _, _ -> finishBackward() }
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
        binding.etBagCount.setText("")
        binding.spinnerBagWeight.setText(bagSizeOptions[0].toString(), false)
        if (!binding.cbUseDefaultBatchRef.isChecked) binding.etBatchRef.setText("")
        resolvedProductCode = null
        resolvedPalletCode = null
        resolvedPalletRowId = null
        checkFormCompletion()
    }

    private fun validateAndSubmit() {
        val barcode = binding.etBarcode.text.toString().trim()
        val rfid = binding.etRfid.text.toString().trim()
        val bagSize = binding.spinnerBagWeight.text.toString().trim().toIntOrNull()
        val bagCount = binding.etBagCount.text.toString().trim().toIntOrNull()
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

        if (bagCount == null || bagCount <= 0) {
            Toast.makeText(this, "Invalid bag count", Toast.LENGTH_SHORT).show()
            return
        }

        if (!useDefault && batchRef.isEmpty()) {
            Toast.makeText(this, "Please enter a Batch Reference or select Use Default", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceId = "scanner_$scanner_int"
        val docNum = intent.getStringExtra("doc_number") ?: ""
        val docType = intent.getStringExtra("doc_type") ?: ""

        val payload = JSONObject().apply {
            put("ts", Instant.now().toString())
            put("deviceId", deviceId)
            getSessionId().takeIf { it.isNotBlank() }?.let { put("sessionId", it) }
            put("tagId", rfid)
            put("barcode", barcode)
            put("sourceDocumentType", docType)
            put("sourceDocumentNumber", docNum)
            put("batchRef", if (useDefault) "" else batchRef)
            put("useDefaultBatchRef", useDefault)
            put("bagWeightKg", bagSize)
            put("bagCount", bagCount)
            resolvedProductCode?.let { put("productCode", it) }
            // palletRowId is only a mismatch check server-side - omit unless we resolved
            // it from a known pallet, since a wrong guess here would reject the offload.
            resolvedPalletRowId?.let { put("palletRowId", it) }
            resolvedPalletCode?.let { put("palletCode", it) }
        }

        val topic = "PPNAM/$deviceId/req/offload_v2"
        binding.btnSubmit.isEnabled = false
        MqttManager.getInstance(this).publish(topic, payload.toString()) { throwable ->
            runOnUiThread {
                if (throwable != null) {
                    Toast.makeText(this, "Publish Failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                    checkFormCompletion()
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

        val topic = "PPNAM/$deviceId/req/all_offloaded"
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
            finishBackward()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        val mqtt = MqttManager.getInstance(this)
        mqtt.removeConnectionStatusListener(connectionStatusListener)
        mqtt.unsubscribe("PPNAM/scanner_$scanner_int/res/offload_result")
        mqtt.unsubscribe("PPNAM/scanner_$scanner_int/res/all_offloaded_result")
    }
}
