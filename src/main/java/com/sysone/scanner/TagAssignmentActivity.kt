package com.sysone.scanner

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
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.sysone.scanner.databinding.ActivityTagAssignmentBinding
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

class TagAssignmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTagAssignmentBinding
    private var scannerInt = 1
    private var mqtt: Mqtt3AsyncClient? = null
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
        initMqtt()

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

    private fun initMqtt() {
        mqtt = MqttClient.builder()
            .useMqttVersion3()
            .identifier("TAG_ASSIGN_" + UUID.randomUUID().toString().take(8))
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

    private fun submitTag(rfid: String, product: String) {
        val productid = product.split(" - ").firstOrNull() ?: product

        val payload = JSONObject().apply {
            put("ts", Instant.now().toString())
            put("deviceId", "scanner_$scannerInt")
            put("sessionId", getSessionId())
            put("tagId", rfid)
            put("productCode", productid)
            put("actualPalletSequence", palletSequence)
        }

        val topic = "PPNAM/scanner_$scannerInt/assignment"
        publishToMqtt(topic, payload.toString(), "Tag Submitted") {
            binding.etRfid.setText("")
            palletSequence++
        }
    }

    private fun submitAllAssigned() {
        val payload = JSONObject().apply {
            put("ts", Instant.now().toString())
            put("deviceId", "scanner_$scannerInt")
            put("sessionId", getSessionId())
            put("allAssigned", true)
        }

        val topic = "PPNAM/scanner_$scannerInt/assignment"
        publishToMqtt(topic, payload.toString(), "Assignments Completed") {
            palletSequence = 1
            val docNum = intent.getStringExtra("doc_number") ?: ""
            val docType = intent.getStringExtra("doc_type") ?: ""
            val intent = Intent(this, AssignmentActivity::class.java).apply {
                putExtra("doc_number", docNum)
                putExtra("doc_type", docType)
            }
            startActivity(intent)
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
        mqtt?.disconnect()
    }
}
