package com.mitas.ppnam.station1

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mitas.ppnam.station1.databinding.ActivityManualSapEntryBinding
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant

class ManualSapEntryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManualSapEntryBinding
    private var scanner_int = 1
    private val docTypes = listOf("Purchase Order", "Transfer Request")

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread { binding.connectionPill.setStatus(status) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManualSapEntryBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        forceLightStatusBarIcons()

        loadSettings()
        setupToolbar()
        setupSpinners()
        
        subscribeToResults()
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        binding.btnSubmit.setOnClickListener { validateAndSubmit() }
        binding.btnSubmit.applyPressScaleFeedback()

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this) { finishBackward() }
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

    private fun subscribeToResults() {
        val sapResultTopic = "PPNAM/scanner_$scanner_int/res/sap_result"
        MqttManager.getInstance(this).subscribe(sapResultTopic) { publish ->
            val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
            handleSapResult(payload)
        }
    }

    private fun handleSapResult(payload: String) {
        try {
            val json = JSONObject(payload)
            if (!MqttManager.getInstance(this).isRelevantToThisScanner(json)) return
            val status = json.optString("status", "Unknown")
            val message = json.optString("message", "")

            runOnUiThread {
                binding.cardResult.visibility = View.VISIBLE
                binding.tvResultStatus.text = status
                binding.tvResultMessage.text = message

                if (status.equals("Success", ignoreCase = true)) {
                    binding.cardResult.setStrokeColor(ContextCompat.getColorStateList(this, R.color.success))
                    binding.tvResultStatus.setTextColor(ContextCompat.getColor(this, R.color.success))

                    val sessionId = json.optString("sessionId", "")
                    val docNum = binding.etDocNumber.text?.toString()?.trim()
                    val docType = binding.spinnerDocType.text.toString()

                    getSharedPreferences("sap_data", Context.MODE_PRIVATE).edit()
                        .putString("session_id", sessionId)
                        .putInt("current_step", 1) // Step 1: Lookup complete, enable Product Request
                        .apply()

                    val intent = Intent(this, ProductRequestActivity::class.java).apply {
                        putExtra("doc_number", docNum)
                        putExtra("doc_type", docType)
                    }
                    startActivityForward(intent)
                    finish()
                } else {
                    binding.cardResult.setStrokeColor(ContextCompat.getColorStateList(this, R.color.danger))
                    binding.tvResultStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
                    binding.btnSubmit.isEnabled = true
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

        getSharedPreferences("sap_data", Context.MODE_PRIVATE).edit()
            .putString("last_doc_number", docNum)
            .putString("last_doc_type", type)
            .remove("session_id")
            .putInt("current_step", 0) // Reset to start
            .apply()

        val payload = JSONObject().apply {
            put("ts", Instant.now().toString())
            put("deviceId", "scanner_$scanner_int")
            put("sourceDocumentType", type)
            put("sourceDocumentNumber", docNum)
        }

        binding.btnSubmit.isEnabled = false
        val topic = "PPNAM/scanner_$scanner_int/req/sap"
        MqttManager.getInstance(this).publish(topic, payload.toString()) { throwable ->
            runOnUiThread {
                if (throwable != null) {
                    Toast.makeText(this, "Publish Failed: ${throwable.message}", Toast.LENGTH_SHORT).show()
                    binding.btnSubmit.isEnabled = true
                } else {
                    Toast.makeText(this, "Lookup Request Sent", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
        val sapResultTopic = "PPNAM/scanner_$scanner_int/res/sap_result"
        mqtt.unsubscribe(sapResultTopic)
    }
}
