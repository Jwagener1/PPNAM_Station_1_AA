package com.sysone.scanner

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.sysone.scanner.databinding.ActivityManualSapEntryBinding
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

class ManualSapEntryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManualSapEntryBinding
    private var scanner_int = 1
    private var mqtt: Mqtt3AsyncClient? = null

    private val docTypes = listOf("Purchase Order", "Transfer Request")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManualSapEntryBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)

        loadSettings()
        setupToolbar()
        setupSpinners()
        initMqtt()

        binding.btnSubmit.setOnClickListener { validateAndSubmit() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        scanner_int = prefs.getInt("scanner_int", 1)

        val sapPrefs = getSharedPreferences("sap_data", Context.MODE_PRIVATE)
        binding.etDocNumber.setText(sapPrefs.getString("last_doc_number", ""))
        val lastType = sapPrefs.getString("last_doc_type", docTypes[0])
        binding.spinnerDocType.setText(lastType, false)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.title_lookup_sap)
    }

    private fun setupSpinners() {
        val typeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, docTypes)
        binding.spinnerDocType.setAdapter(typeAdapter)
    }

    private fun initMqtt() {
        mqtt = MqttClient.builder()
            .useMqttVersion3()
            .identifier("SAP_LOOKUP_" + UUID.randomUUID().toString().take(8))
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
                if (throwable == null) {
                    subscribeToResults()
                }
            }
    }

    private fun subscribeToResults() {
        val sapResultTopic = "PPNAM/scanner_$scanner_int/sap_result"

        mqtt?.subscribeWith()
            ?.topicFilter(sapResultTopic)
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.callback { publish ->
                val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
                handleSapResult(payload)
            }
            ?.send()
    }

    private fun handleSapResult(payload: String) {
        try {
            val json = JSONObject(payload)
            val status = json.optString("status", "Unknown")
            val message = json.optString("message", "")

            runOnUiThread {
                binding.cardResult.visibility = View.VISIBLE
                binding.tvResultStatus.text = status
                binding.tvResultMessage.text = message

                if (status.equals("Success", ignoreCase = true)) {
                    binding.cardResult.setStrokeColor(ContextCompat.getColorStateList(this, R.color.success))
                    binding.tvResultStatus.setTextColor(ContextCompat.getColor(this, R.color.success))

                    // Persist sessionId from SAP response for use in all downstream activities
                    val sessionId = json.optString("sessionId", "")
                    val docNum = binding.etDocNumber.text?.toString()?.trim()
                    val docType = binding.spinnerDocType.text.toString()

                    getSharedPreferences("sap_data", Context.MODE_PRIVATE).edit()
                        .putString("session_id", sessionId)
                        .apply()

                    val intent = Intent(this, ProductRequestActivity::class.java).apply {
                        putExtra("doc_number", docNum)
                        putExtra("doc_type", docType)
                    }
                    startActivity(intent)
                    finish()
                } else {
                    binding.cardResult.setStrokeColor(ContextCompat.getColorStateList(this, R.color.danger))
                    binding.tvResultStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun validateAndSubmit() {
        val docNum = binding.etDocNumber.text?.toString()?.trim().orEmpty()
        if (docNum.isEmpty()) {
            Toast.makeText(this, "Source Document Number is required", Toast.LENGTH_SHORT).show()
            return
        }

        val type = binding.spinnerDocType.text.toString()

        // Save doc details; clear any previous sessionId since this is a fresh SAP lookup
        getSharedPreferences("sap_data", Context.MODE_PRIVATE).edit()
            .putString("last_doc_number", docNum)
            .putString("last_doc_type", type)
            .remove("session_id")
            .apply()

        val payload = JSONObject().apply {
            put("ts", Instant.now().toString())
            put("deviceId", "scanner_$scanner_int")
            put("sourceDocumentType", type)
            put("sourceDocumentNumber", docNum)
        }

        publishToMqtt(payload.toString())
    }

    private fun publishToMqtt(json: String) {
        if (mqtt?.state != MqttClientState.CONNECTED) {
            Toast.makeText(this, "MQTT Not Connected", Toast.LENGTH_SHORT).show()
            return
        }

        val topic = "PPNAM/scanner_$scanner_int/sap"

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
                        Toast.makeText(this, "Lookup Request Sent", Toast.LENGTH_SHORT).show()
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
