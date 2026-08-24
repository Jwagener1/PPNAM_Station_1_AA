package com.mitas.ppnam.station1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mitas.ppnam.station1.databinding.ActivityTagAssignmentBinding
import org.json.JSONObject
import java.time.Instant

/**
 * Tag Assignment, stripped down: scan an RFID tag and its information is sent to the station
 * automatically. No document context, no product selection — the station decides what the tag
 * means.
 */
class TagAssignmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTagAssignmentBinding
    private var scannerInt = 1
    private var stationInt = 1
    private var lastSentTag: String? = null

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread { binding.connectionPill.setStatus(status) }
    }

    private val rfidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.rscja.scanner.action.scanner.RFID") {
                val data = intent.getStringExtra("data")
                if (!data.isNullOrEmpty()) onTagScanned(data)
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
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this) { finishBackward() }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        scannerInt = prefs.getInt("scanner_int", 1)
        stationInt = prefs.getInt("station_int", 1)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.tab_tag_assignment)
    }

    private fun onTagScanned(tagId: String) {
        runOnUiThread {
            binding.tvLastTag.text = tagId
            binding.tvSendStatus.visibility = android.view.View.VISIBLE
            binding.tvSendStatus.text = getString(R.string.status_sending)
            binding.tvSendStatus.setTextColor(getColor(R.color.text_muted))
        }
        sendTag(tagId)
    }

    /**
     * Provisional wire shape — the final MQTT message set for the stripped-down app is still to
     * be agreed with the station side. Payload follows the app's usual envelope (`ts`,
     * `deviceId`), plus the operator's session for attribution.
     */
    private fun sendTag(tagId: String) {
        val payload = JSONObject().apply {
            put("ts", Instant.now().toString())
            put("deviceId", "scanner_$scannerInt")
            put("operatorSessionId", OperatorSessionHolder.currentSessionIdOrEmpty())
            put("tagId", tagId)
        }
        val topic = MqttTopics.deviceRequest(stationInt, "scanner_$scannerInt", "tag_scan")
        MqttManager.getInstance(this).publish(topic, payload.toString()) { throwable ->
            runOnUiThread {
                if (throwable != null) {
                    binding.tvSendStatus.text = getString(R.string.status_send_failed)
                    binding.tvSendStatus.setTextColor(getColor(R.color.danger))
                } else {
                    lastSentTag = tagId
                    binding.tvSendStatus.text = getString(R.string.tx_status_sent)
                    binding.tvSendStatus.setTextColor(getColor(R.color.success))
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
            finishBackward()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
    }
}
