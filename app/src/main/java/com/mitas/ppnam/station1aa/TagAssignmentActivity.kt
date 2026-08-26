package com.mitas.ppnam.station1aa

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
import com.mitas.ppnam.station1aa.databinding.ActivityTagAssignmentBinding

/**
 * Tag Assignment (contract v3.0.0 §5): every scanned RFID tag is sent automatically as
 * `tag_scan`; the station decides what the tag means and answers `tag_scan_result` echoing the
 * tagId. The UI stays pending until that result (or the 10-second timeout) — a PUBACK is
 * transport-only and never shown as success.
 */
class TagAssignmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTagAssignmentBinding
    private lateinit var workflow: WorkflowClient
    private var lastScannedTag: String? = null

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

        setupToolbar()
        workflow = WorkflowClient(this)
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this) { finishBackward() }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.tab_tag_assignment)
    }

    private fun onTagScanned(tagId: String) {
        runOnUiThread {
            lastScannedTag = tagId
            binding.tvLastTag.text = tagId
            showStatus(getString(R.string.status_sending), R.color.text_muted)
        }
        sendTag(tagId)
    }

    private fun sendTag(tagId: String) {
        val payload = WorkflowMessages.tagScan(
            deviceId = DeviceIdentity.deviceId(this),
            operatorSessionId = OperatorSessionHolder.currentSessionIdOrEmpty(),
            tagId = tagId,
        )
        workflow.request(
            requestType = "tag_scan",
            responseType = "tag_scan_result",
            payload = payload,
            matches = { it.optString("tagId") == tagId },
        ) { result ->
            // A newer scan owns the status line by now — its own result will drive the UI.
            if (tagId != lastScannedTag) return@request
            result
                .onSuccess { json ->
                    if (json.optBoolean("accepted", false)) {
                        showStatus(
                            json.optString("reason", "").ifBlank { getString(R.string.status_tag_assigned) },
                            R.color.success,
                        )
                    } else {
                        if (handleSessionRejection(json)) return@request
                        showStatus(stationReason(json), R.color.danger)
                    }
                }
                .onFailure { e ->
                    val message = if (e is WorkflowTimeout) getString(R.string.status_no_response)
                    else getString(R.string.status_send_failed)
                    showStatus(message, R.color.danger)
                }
        }
    }

    private fun stationReason(json: org.json.JSONObject): String =
        json.optString("reason", "").ifBlank {
            json.optString("errorCode", "").ifBlank { getString(R.string.status_send_failed) }
        }

    /** §8: a closed/expired session sends the operator back to login, not into a dead end. */
    private fun handleSessionRejection(json: org.json.JSONObject): Boolean {
        if (!WorkflowClient.isSessionRejection(json)) return false
        OperatorSessionHolder.clear()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
        return true
    }

    private fun showStatus(message: String, colorRes: Int) {
        binding.tvSendStatus.visibility = android.view.View.VISIBLE
        binding.tvSendStatus.text = message
        binding.tvSendStatus.setTextColor(getColor(colorRes))
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
