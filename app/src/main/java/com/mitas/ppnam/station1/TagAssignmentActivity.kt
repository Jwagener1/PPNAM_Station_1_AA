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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputLayout
import com.mitas.ppnam.station1.databinding.ActivityTagAssignmentBinding
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant

class TagAssignmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTagAssignmentBinding
    private var scannerInt = 1
    private var stationInt = 1
    private var selectedProducts: List<String> = emptyList()
    private var palletSequence: Int = 1
    /** productCode -> (bagSize, bagCount), from the SAP product response (see ProductRequestActivity). */
    private var productMeta: Map<String, Pair<String, Int>> = emptyMap()

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread { binding.connectionPill.setStatus(status) }
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
        binding = ActivityTagAssignmentBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        forceLightStatusBarIcons()

        loadSettings()
        setupToolbar()
        loadProductMeta()
        setupProductSpinner()
        setupBagFieldWatchers()

        subscribeToResults()
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this) { finishBackward() }

        binding.btnSubmit.setOnClickListener {
            val rfid = binding.etRfid.text.toString().trim()
            val product = binding.spinnerProduct.text.toString()
            val bagSize = binding.etBagSize.text.toString().trim()
            val bagCount = binding.etBagCount.text.toString().trim().toIntOrNull()

            if (product.isEmpty()) {
                Toast.makeText(this, "Please select a product", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (rfid.isEmpty()) {
                Toast.makeText(this, "Please scan a tag first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (bagSize.isEmpty() || bagCount == null || bagCount <= 0) {
                Toast.makeText(this, "Enter a valid bag size and count", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            submitTag(rfid, product, bagSize, bagCount)
        }

        binding.btnAllAssigned.setOnClickListener {
            showConfirmationDialog()
        }

        binding.btnSubmit.applyPressScaleFeedback()
        binding.btnAllAssigned.applyPressScaleFeedback()

        checkFormCompletion()
    }

    private fun loadProductMeta() {
        val raw = intent.getStringExtra("product_meta_json")
            ?: getSharedPreferences("sap_data", Context.MODE_PRIVATE).getString("product_meta_json", "")
        if (raw.isNullOrBlank()) return
        try {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, Pair<String, Int>>()
            json.keys().forEach { code ->
                val entry = json.getJSONObject(code)
                map[code] = entry.optString("bagSize", "") to entry.optInt("bagCount", 0)
            }
            productMeta = map
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Auto-fills bag size/count from the selected product's SAP metadata; the operator can still edit either. */
    private fun applyProductDefaults(productCode: String) {
        val meta = productMeta[productCode] ?: return
        val (bagSize, bagCount) = meta
        if (bagSize.isNotEmpty()) binding.etBagSize.setText(bagSize)
        if (bagCount > 0) binding.etBagCount.setText(bagCount.toString())
    }

    private fun setupBagFieldWatchers() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = checkFormCompletion()
        }
        binding.etRfid.addTextChangedListener(watcher)
        binding.etBagSize.addTextChangedListener(watcher)
        binding.etBagCount.addTextChangedListener(watcher)
        binding.spinnerProduct.setOnItemClickListener { _, _, _, _ ->
            applyProductDefaults(binding.spinnerProduct.text.toString().split(" - ").firstOrNull() ?: "")
            checkFormCompletion()
        }
    }

    private fun updateFieldColor(til: TextInputLayout, valid: Boolean) {
        til.boxStrokeColor = getColor(if (valid) R.color.success else R.color.border_primary)
    }

    private fun checkFormCompletion() {
        val rfidOk = binding.etRfid.text.toString().isNotBlank()
        val productOk = binding.spinnerProduct.text.toString().isNotBlank() && binding.spinnerProduct.isEnabled
        val bagSizeOk = binding.etBagSize.text.toString().isNotBlank()
        val bagCountOk = (binding.etBagCount.text.toString().toIntOrNull() ?: 0) > 0

        updateFieldColor(binding.tilRfid, rfidOk)
        updateFieldColor(binding.tilBagSize, bagSizeOk)
        updateFieldColor(binding.tilBagCount, bagCountOk)

        val complete = rfidOk && productOk && bagSizeOk && bagCountOk
        val wasEnabled = binding.btnSubmit.isEnabled
        binding.btnSubmit.isEnabled = complete
        binding.btnSubmit.alpha = if (complete) 1f else 0.4f
        if (complete && !wasEnabled) binding.btnSubmit.flashAttention()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        scannerInt = prefs.getInt("scanner_int", 1)
        stationInt = prefs.getInt("station_int", 1)
    }

    private fun getSessionId(): String {
        return getSharedPreferences("sap_data", Context.MODE_PRIVATE)
            .getString("session_id", "") ?: ""
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.tab_tag_assignment)
    }

    private fun setupProductSpinner() {
        selectedProducts = intent.getStringArrayListExtra("selected_products") ?: emptyList()
        if (selectedProducts.isNotEmpty()) {
            val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, selectedProducts)
            binding.spinnerProduct.setAdapter(adapter)
            binding.spinnerProduct.setText(selectedProducts[0], false)
            applyProductDefaults(selectedProducts[0].split(" - ").firstOrNull() ?: "")
        } else {
            binding.tilProduct.hint = "No products selected"
            binding.spinnerProduct.isEnabled = false
        }
    }

    private fun subscribeToResults() {
        val mqtt = MqttManager.getInstance(this)
        val assignmentResultTopic = "PPNAM/scanner_$scannerInt/res/assignment_result"
        val allAssignedResultTopic = "PPNAM/scanner_$scannerInt/res/all_assigned_result"
        val printResultTopic = "PPNAM/scanner_$scannerInt/res/print_all_result"
        val directProductsResponseTopic = "PPNAM/scanner_$scannerInt/res/sap_products_response"
        val broadcastProductsResponseTopic = "PPNAM/station_$stationInt/res/sap_products_response"

        mqtt.subscribe(assignmentResultTopic) { publish ->
            val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
            handleAssignmentResult(payload)
        }

        mqtt.subscribe(allAssignedResultTopic) { publish ->
            val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
            handleAllAssignedResult(payload)
        }

        mqtt.subscribe(printResultTopic) { publish ->
            val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
            handlePrintResult(payload)
        }

        mqtt.subscribe(directProductsResponseTopic) { publish ->
            val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
            handleProductsResponse(payload)
        }

        mqtt.subscribe(broadcastProductsResponseTopic) { publish ->
            val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
            handleProductsResponse(payload)
        }
    }

    private fun handleProductsResponse(payload: String) {
        try {
            val json = JSONObject(payload)
            if (!MqttManager.getInstance(this).isRelevantToThisScanner(json)) return
            val productsArray = json.optJSONArray("products") ?: return
            val newProductList = mutableListOf<String>()
            for (i in 0 until productsArray.length()) {
                val p = productsArray.getJSONObject(i)
                newProductList.add("${p.getString("productCode")} - ${p.getString("productDescription")}")
            }

            runOnUiThread {
                if (newProductList.isNotEmpty()) {
                    selectedProducts = newProductList
                    val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, selectedProducts)
                    binding.spinnerProduct.setAdapter(adapter)
                    // Keep current selection if still valid, otherwise reset
                    val currentSelection = binding.spinnerProduct.text.toString()
                    if (!selectedProducts.contains(currentSelection)) {
                        binding.spinnerProduct.setText(selectedProducts[0], false)
                        applyProductDefaults(selectedProducts[0].split(" - ").firstOrNull() ?: "")
                    }
                    checkFormCompletion()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handlePrintResult(payload: String) {
        try {
            val json = JSONObject(payload)
            if (!MqttManager.getInstance(this).isRelevantToThisScanner(json)) return
            val msg = json.optString("message", "")
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                
                getSharedPreferences("sap_data", Context.MODE_PRIVATE).edit()
                    .putInt("current_step", 3) // Step 3: Assignment complete, enable Offloading
                    .apply()

                moveToOffload()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleAssignmentResult(payload: String) {
        try {
            val json = JSONObject(payload)
            if (!MqttManager.getInstance(this).isRelevantToThisScanner(json)) return
            val status = json.optString("status", "Unknown")
            val message = json.optString("message", "")
            val palletCode = json.optString("palletCode", "")
            val barcode = json.optString("barcode", "")

            runOnUiThread {
                if (status.equals("Success", ignoreCase = true)) {
                    Toast.makeText(this, "Success: $palletCode assigned. Barcode: $barcode", Toast.LENGTH_SHORT).show()
                    binding.etRfid.setText("")
                    palletSequence++
                } else {
                    checkFormCompletion()
                    AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
                        .setTitle("Assignment Failed")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleAllAssignedResult(payload: String) {
        try {
            val json = JSONObject(payload)
            if (!MqttManager.getInstance(this).isRelevantToThisScanner(json)) return
            val status = json.optString("status", "Unknown")
            val message = json.optString("message", "")

            runOnUiThread {
                binding.btnAllAssigned.isEnabled = true
                if (status.equals("Success", ignoreCase = true)) {
                    palletSequence = 1
                    showPrintDialog()
                } else {
                    AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
                        .setTitle("Completion Failed")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun submitTag(rfid: String, product: String, bagSize: String, bagCount: Int) {
        val productid = product.split(" - ").firstOrNull() ?: product
        val docNum = intent.getStringExtra("doc_number") ?: ""
        val docType = intent.getStringExtra("doc_type") ?: ""

        val payload = JSONObject().apply {
            put("ts", Instant.now().toString())
            put("deviceId", "scanner_$scannerInt")
            getSessionId().takeIf { it.isNotBlank() }?.let { put("sessionId", it) }
            put("tagId", rfid)
            put("sourceDocumentType", docType)
            put("sourceDocumentNumber", docNum)
            put("productCode", productid)
            put("actualPalletSequence", palletSequence)
            // Field name is snake_case on the wire (RfidAssignmentMessage.bag_size) -
            // do not "fix" it to camelCase, the station won't recognize bagSize here.
            put("bag_size", bagSize)
            put("bagCount", bagCount)
            put("useDefaultBatchRef", true)
            put("batchRef", "")
        }

        val topic = "PPNAM/scanner_$scannerInt/req/assignment_v2"
        binding.btnSubmit.isEnabled = false
        MqttManager.getInstance(this).publish(topic, payload.toString()) { throwable ->
            runOnUiThread {
                if (throwable != null) {
                    Toast.makeText(this, "Publish Failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                    checkFormCompletion()
                } else {
                    Toast.makeText(this, "Sending Assignment...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun submitAllAssigned() {
        val payload = JSONObject().apply {
            put("ts", Instant.now().toString())
            put("deviceId", "scanner_$scannerInt")
            getSessionId().takeIf { it.isNotBlank() }?.let { put("sessionId", it) }
            put("allAssigned", true)
        }

        val topic = "PPNAM/scanner_$scannerInt/req/assignment"
        binding.btnAllAssigned.isEnabled = false
        MqttManager.getInstance(this).publish(topic, payload.toString()) { throwable ->
            runOnUiThread {
                if (throwable != null) {
                    Toast.makeText(this, "Publish Failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                    binding.btnAllAssigned.isEnabled = true
                } else {
                    Toast.makeText(this, "Completing assignments...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showPrintDialog() {
        AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
            .setTitle("Assignments Complete")
            .setMessage("Do you want to print all pallet labels now?")
            .setPositiveButton("Print All") { _, _ ->
                submitPrintAll()
            }
            .setNegativeButton("Skip") { _, _ ->
                moveToOffload()
            }
            .setCancelable(false)
            .show()
    }

    private fun submitPrintAll() {
        val docNum = intent.getStringExtra("doc_number") ?: ""
        val payload = JSONObject().apply {
            put("ts", Instant.now().toString())
            put("deviceId", "scanner_$scannerInt")
            getSessionId().takeIf { it.isNotBlank() }?.let { put("sessionId", it) }
            put("sourceDocumentNumber", docNum)
            put("printAll", true)
        }

        val topic = "PPNAM/scanner_$scannerInt/req/print_all"
        MqttManager.getInstance(this).publish(topic, payload.toString()) { throwable ->
            runOnUiThread {
                if (throwable != null) {
                    Toast.makeText(this, "Publish Failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Printing...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun moveToOffload() {
        val docNum = intent.getStringExtra("doc_number") ?: ""
        val docType = intent.getStringExtra("doc_type") ?: ""
        val productMetaJson = intent.getStringExtra("product_meta_json")
            ?: getSharedPreferences("sap_data", Context.MODE_PRIVATE).getString("product_meta_json", "")
        val intent = Intent(this, AssignmentActivity::class.java).apply {
            putExtra("doc_number", docNum)
            putExtra("doc_type", docType)
            putExtra("product_meta_json", productMetaJson)
        }
        startActivityForward(intent)
        finish()
    }

    private fun showConfirmationDialog() {
        AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
            .setTitle("Confirm Completion")
            .setMessage("Are you sure all tag assignments have been completed?")
            .setPositiveButton("Yes") { _, _ ->
                submitAllAssigned()
            }
            .setNegativeButton("No", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        val rfidFilter = IntentFilter("com.rscja.scanner.action.scanner.RFID")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rfidReceiver, rfidFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(rfidReceiver, rfidFilter)
        }
    }

    override fun onPause() {
        super.onPause()
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
        mqtt.unsubscribe("PPNAM/scanner_$scannerInt/res/assignment_result")
        mqtt.unsubscribe("PPNAM/scanner_$scannerInt/res/all_assigned_result")
        mqtt.unsubscribe("PPNAM/scanner_$scannerInt/res/print_all_result")
        mqtt.unsubscribe("PPNAM/scanner_$scannerInt/res/sap_products_response")
        mqtt.unsubscribe("PPNAM/station_$stationInt/res/sap_products_response")
    }
}
