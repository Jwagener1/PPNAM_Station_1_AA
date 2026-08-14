package com.mitas.ppnam.station1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mitas.ppnam.station1.databinding.ActivityUnassignBinding
import org.json.JSONObject
import java.time.Instant

class UnassignActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnassignBinding
    private var scannerInt = 1
    private var stationInt = 1

    private val rfidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.rscja.scanner.action.scanner.RFID") {
                val data = intent.getStringExtra("data")
                if (!data.isNullOrEmpty()) {
                    handleUnassign(data)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnassignBinding.inflate(layoutInflater)
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
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        scannerInt = prefs.getInt("scanner_int", 1)
        stationInt = prefs.getInt("station_int", 1)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Unassign Mode"
    }

    private fun subscribeToResults() {
        val mqtt = MqttManager.getInstance(this)
        val unassignResultTopic = "PPNAM/station_$stationInt/unassign_result"
        mqtt.subscribe(unassignResultTopic) { publish ->
            val payload = String(publish.payloadAsBytes)
            handleUnassignResult(payload)
        }
    }

    private fun handleUnassignResult(payload: String) {
        try {
            val json = JSONObject(payload)
            if (!MqttManager.getInstance(this).isRelevantToThisScanner(json)) return
            val status = json.optString("status", "Unknown")
            val message = json.optString("message", "")

            runOnUiThread {
                binding.tvUnassignStatus.text = message
                if (status.equals("Success", ignoreCase = true)) {
                    binding.tvUnassignStatus.setTextColor(getColor(R.color.success))
                } else {
                    binding.tvUnassignStatus.setTextColor(getColor(R.color.danger))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleUnassign(rfid: String) {
        binding.tvLastScanned.text = rfid
        binding.tvUnassignStatus.text = "Sending unassign request..."
        binding.tvUnassignStatus.setTextColor(getColor(R.color.text_muted))

        val payload = JSONObject().apply {
            put("ts", Instant.now().toString())
            put("deviceId", "scanner_$scannerInt")
            put("tagId", rfid)
        }

        val topic = "PPNAM/scanner_$scannerInt/unassign"
        MqttManager.getInstance(this).publish(topic, payload.toString()) { throwable ->
            runOnUiThread {
                if (throwable != null) {
                    binding.tvUnassignStatus.text = "Failed to send: ${throwable.message}"
                    binding.tvUnassignStatus.setTextColor(getColor(R.color.danger))
                } else {
                    binding.tvUnassignStatus.text = "Unassign request sent successfully"
                    binding.tvUnassignStatus.setTextColor(getColor(R.color.success))
                }
            }
        }
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
        MqttManager.getInstance(this).unsubscribe("PPNAM/station_$stationInt/unassign_result")
    }
}
