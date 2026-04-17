package com.sysone.scanner

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.sysone.scanner.databinding.ActivityManualSapEntryBinding
import com.sysone.scanner.databinding.ItemSapProductBinding
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class ManualSapEntryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManualSapEntryBinding
    private var deviceId = "C72-001"
    private var mqtt: Mqtt3AsyncClient? = null

    private val docTypes = listOf("Invoice", "PurchaseOrder", "DeliveryNote")
    private val qtyModes = listOf("Known", "Unknown")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManualSapEntryBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)

        loadSettings()
        setupToolbar()
        setupSpinners()
        initMqtt()

        binding.btnAddItem.setOnClickListener { addNewItemRow() }
        binding.btnSubmit.setOnClickListener { validateAndSubmit() }

        // Start with one empty item
        addNewItemRow()

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", "C72-001") ?: "C72-001"
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Manual SAP Entry"
    }

    private fun setupSpinners() {
        val typeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, docTypes)
        binding.spinnerDocType.setAdapter(typeAdapter)
        binding.spinnerDocType.setText(docTypes[0], false)

        val modeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, qtyModes)
        binding.spinnerQtyMode.setAdapter(modeAdapter)
        binding.spinnerQtyMode.setText(qtyModes[0], false)
    }

    private fun addNewItemRow() {
        val itemBinding = ItemSapProductBinding.inflate(LayoutInflater.from(this), binding.containerItems, false)
        val index = binding.containerItems.childCount + 1
        itemBinding.tvItemNumber.text = "Item #$index"
        
        itemBinding.btnRemoveItem.setOnClickListener {
            binding.containerItems.removeView(itemBinding.root)
            updateItemNumbers()
        }

        binding.containerItems.addView(itemBinding.root)
    }

    private fun updateItemNumbers() {
        for (i in 0 until binding.containerItems.childCount) {
            val view = binding.containerItems.getChildAt(i)
            val itemNumberText = view.findViewById<android.widget.TextView>(R.id.tvItemNumber)
            itemNumberText.text = "Item #${i + 1}"
        }
    }

    private fun initMqtt() {
        mqtt = MqttClient.builder()
            .useMqttVersion3()
            .identifier(UUID.randomUUID().toString())
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
    }

    private fun validateAndSubmit() {
        val docNum = binding.etDocNumber.text?.toString()?.trim().orEmpty()
        if (docNum.isEmpty()) {
            Toast.makeText(this, "Source Document Number is required", Toast.LENGTH_SHORT).show()
            return
        }

        val itemsArray = JSONArray()
        for (i in 0 until binding.containerItems.childCount) {
            val view = binding.containerItems.getChildAt(i)
            val productCode = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etProductCode).text?.toString()?.trim().orEmpty()
            val description = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDescription).text?.toString()?.trim().orEmpty()
            val quantity = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etQuantity).text?.toString()?.trim().orEmpty()
            val batchRef = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etBatchRef).text?.toString()?.trim().orEmpty()
            val bagSize = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etBagSize).text?.toString()?.trim().orEmpty()

            // Per row validation: Code/Desc, Qty, and Batch are recommended/required
            if (productCode.isEmpty() && description.isEmpty()) continue // Skip empty rows
            
            val itemJson = JSONObject().apply {
                put("ProductCode", productCode)
                put("ProductDescription", description)
                put("OpenQuantity", quantity.toDoubleOrNull() ?: 0.0)
                put("BagSize", bagSize)
                put("BagsPerPallet", null) // Optional, keeping null as per spec if not in UI
                put("BatchReference", batchRef)
            }
            itemsArray.put(itemJson)
        }

        if (itemsArray.length() == 0) {
            Toast.makeText(this, "At least one valid item is required", Toast.LENGTH_SHORT).show()
            return
        }

        val payload = JSONObject().apply {
            put("SourceDocumentType", binding.spinnerDocType.text.toString())
            put("SourceDocumentNumber", docNum)
            put("QuantityMode", binding.spinnerQtyMode.text.toString())
            put("VendorReference", "")
            put("Warehouse", binding.etWarehouse.text?.toString()?.trim().orEmpty())
            put("GeneratedBatchReference", "")
            put("SapMessage", "Manual SAP entry")
            put("ExpectedPalletCount", null)
            put("Items", itemsArray)
        }

        val root = JSONObject().apply {
            put("timestamp", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("deviceId", deviceId)
            put("payload", payload)
        }

        publishToMqtt(root.toString())
    }

    private fun publishToMqtt(json: String) {
        if (mqtt?.state != MqttClientState.CONNECTED) {
            Toast.makeText(this, "MQTT Not Connected", Toast.LENGTH_SHORT).show()
            return
        }

        mqtt?.publishWith()
            ?.topic("PPNAM/manual-sap-entry")
            ?.payload(json.toByteArray(StandardCharsets.UTF_8))
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.send()
            ?.whenComplete { _, throwable ->
                runOnUiThread {
                    if (throwable != null) {
                        Toast.makeText(this, "Publish Failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Successfully Submitted to SAP", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
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
