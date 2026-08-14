package com.mitas.ppnam.station1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mitas.ppnam.station1.databinding.ActivityReassignBinding
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant

class ReassignActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReassignBinding
    private var scannerInt = 1
    private var stationInt = 1

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
        binding = ActivityReassignBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)

        loadSettings()
        setupToolbar()
        subscribeToResults()

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.btnSubmit.setOnClickListener {
            validateAndSubmit()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        scannerInt = prefs.getInt("scanner_int", 1)
        stationInt = prefs.getInt("station_int", 1)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Reassign Mode"
    }

    private fun subscribeToResults() {
        val mqtt = MqttManager.getInstance(this)
        val reassignResultTopic = "PPNAM/station_$stationInt/reassign_result"
        mqtt.subscribe(reassignResultTopic) { publish ->
            val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
            handleReassignResult(payload)
        }
    }

    private fun handleReassignResult(payload: String) {
        try {
            val json = JSONObject(payload)
            if (!MqttManager.getInstance(this).isRelevantToThisScanner(json)) return
            val status = json.optString("status", "Unknown")
            val message = json.optString("message", "")

            runOnUiThread {
                binding.btnSubmit.isEnabled = true
                binding.tvReassignStatus.text = message
                if (status.equals("Success", ignoreCase = true)) {
                    binding.tvReassignStatus.setTextColor(getColor(R.color.success))
                    binding.etBarcode.setText("")
                    binding.etRfid.setText("")
                } else {
                    binding.tvReassignStatus.setTextColor(getColor(R.color.danger))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun validateAndSubmit() {
        val barcode = binding.etBarcode.text.toString().trim()
        val rfid = binding.etRfid.text.toString().trim()

        if (barcode.isEmpty() || rfid.isEmpty()) {
            Toast.makeText(this, "Barcode and RFID Tag are required", Toast.LENGTH_SHORT).show()
            return
        }

        binding.tvReassignStatus.text = getString(R.string.msg_sending_reassign)
        binding.tvReassignStatus.setTextColor(getColor(R.color.text_muted))

        val payload = JSONObject().apply {
            put("ts", Instant.now().toString())
            put("deviceId", "scanner_$scannerInt")
            put("tagId", rfid)
            put("barcode", barcode)
        }

        val topic = "PPNAM/scanner_$scannerInt/reassign"
        binding.btnSubmit.isEnabled = false
        MqttManager.getInstance(this).publish(topic, payload.toString()) { throwable ->
            runOnUiThread {
                if (throwable != null) {
                    binding.btnSubmit.isEnabled = true
                    binding.tvReassignStatus.text = getString(R.string.msg_reassign_failed, throwable.message)
                    binding.tvReassignStatus.setTextColor(getColor(R.color.danger))
                } else {
                    binding.tvReassignStatus.text = getString(R.string.msg_reassign_success)
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
        MqttManager.getInstance(this).unsubscribe("PPNAM/station_$stationInt/reassign_result")
    }
}
