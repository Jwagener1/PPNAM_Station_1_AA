package com.mitas.ppnam.station1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

        loadSettings()
        setupToolbar()
        setupProductSpinner()
        
        subscribeToResults()

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.btnSubmit.setOnClickListener {
            val rfid = binding.etRfid.text.toString()
            val product = binding.spinnerProduct.text.toString()

            if (product.isEmpty()) {
                Toast.makeText(this, "Please select a product", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (rfid.isNotEmpty()) {
                submitTag(rfid, product)
            } else {
                Toast.makeText(this, "Please scan a tag first", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAllAssigned.setOnClickListener {
            showConfirmationDialog()
        }
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
        } else {
            binding.tilProduct.hint = "No products selected"
            binding.spinnerProduct.isEnabled = false
        }
    }

    private fun subscribeToResults() {
        val mqtt = MqttManager.getInstance(this)
        val assignmentResultTopic = "PPNAM/station_$stationInt/assignment_result"
        val allAssignedResultTopic = "PPNAM/station_$stationInt/all_assigned_result"
        val printResultTopic = "PPNAM/station_$stationInt/print_all_result"
        val productsResponseTopic = "PPNAM/station_$stationInt/sap_products_response"

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

        mqtt.subscribe(productsResponseTopic) { publish ->
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
                    }
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
                binding.btnSubmit.isEnabled = true
                if (status.equals("Success", ignoreCase = true)) {
                    Toast.makeText(this, "Success: $palletCode assigned. Barcode: $barcode", Toast.LENGTH_SHORT).show()
                    binding.etRfid.setText("")
                    palletSequence++
                } else {
                    AlertDialog.Builder(this)
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
                    AlertDialog.Builder(this)
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

    private fun submitTag(rfid: String, product: String) {
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
        }

        val topic = "PPNAM/scanner_$scannerInt/assignment_v2"
        binding.btnSubmit.isEnabled = false
        MqttManager.getInstance(this).publish(topic, payload.toString()) { throwable ->
            runOnUiThread {
                if (throwable != null) {
                    Toast.makeText(this, "Publish Failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                    binding.btnSubmit.isEnabled = true
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

        val topic = "PPNAM/scanner_$scannerInt/assignment"
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
        AlertDialog.Builder(this)
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

        val topic = "PPNAM/scanner_$scannerInt/print_all"
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
        val intent = Intent(this, AssignmentActivity::class.java).apply {
            putExtra("doc_number", docNum)
            putExtra("doc_type", docType)
        }
        startActivity(intent)
        finish()
    }

    private fun showConfirmationDialog() {
        AlertDialog.Builder(this)
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
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        val mqtt = MqttManager.getInstance(this)
        mqtt.unsubscribe("PPNAM/station_$stationInt/assignment_result")
        mqtt.unsubscribe("PPNAM/station_$stationInt/all_assigned_result")
        mqtt.unsubscribe("PPNAM/station_$stationInt/print_all_result")
        mqtt.unsubscribe("PPNAM/station_$stationInt/sap_products_response")
    }
}
