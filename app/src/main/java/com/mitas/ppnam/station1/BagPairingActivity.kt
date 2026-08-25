package com.mitas.ppnam.station1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.mitas.ppnam.station1.databinding.ActivityBagPairingBinding
import org.json.JSONObject
import java.time.Instant

/**
 * Bag Pairing: pair a scanned RFID tag with a scanned barcode, plus three operator-entered
 * values — bag weight, number of bags and batch reference — each locked in with its own
 * Confirm (and re-opened with Edit) so a value can't drift after the operator checked it.
 */
class BagPairingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBagPairingBinding

    private var stationInt = 1

    /** One edit-and-confirm field: Confirm locks the value, Edit re-opens it. */
    private inner class ConfirmableField(
        val editText: TextInputEditText,
        val button: MaterialButton,
    ) {
        var confirmed = false
            private set

        init {
            button.setOnClickListener {
                if (confirmed) unlock() else lock()
            }
            button.applyPressScaleFeedback()
        }

        private fun lock() {
            if (editText.text.isNullOrBlank()) {
                editText.error = getString(R.string.error_value_required)
                return
            }
            confirmed = true
            editText.isEnabled = false
            button.text = getString(R.string.btn_edit)
            button.backgroundTintList = getColorStateList(R.color.success)
            updateSubmitEnabled()
        }

        private fun unlock() {
            confirmed = false
            editText.isEnabled = true
            button.text = getString(R.string.btn_confirm)
            button.backgroundTintList = getColorStateList(R.color.primary_action)
            updateSubmitEnabled()
        }

        fun reset() {
            unlock()
            editText.setText("")
        }

        fun value(): String = editText.text.toString().trim()
    }

    private lateinit var bagWeight: ConfirmableField
    private lateinit var bagCount: ConfirmableField
    private lateinit var batchRef: ConfirmableField

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread { binding.connectionPill.setStatus(status) }
    }

    private val rfidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.rscja.scanner.action.scanner.RFID") {
                val data = intent.getStringExtra("data")
                if (!data.isNullOrEmpty()) {
                    binding.etTag.setText(data)
                    updateSubmitEnabled()
                }
            }
        }
    }

    private val barcodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.scanner.broadcast") {
                val data = intent.getStringExtra("data")
                if (!data.isNullOrEmpty()) {
                    binding.etBarcode.setText(data)
                    updateSubmitEnabled()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBagPairingBinding.inflate(layoutInflater)
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

        bagWeight = ConfirmableField(binding.etBagWeight, binding.btnConfirmBagWeight)
        bagCount = ConfirmableField(binding.etBagCount, binding.btnConfirmBagCount)
        batchRef = ConfirmableField(binding.etBatchRef, binding.btnConfirmBatchRef)

        binding.etTag.addTextChangedListener(SimpleTextWatcher { updateSubmitEnabled() })
        binding.etBarcode.addTextChangedListener(SimpleTextWatcher { updateSubmitEnabled() })

        binding.btnSubmitPairing.setOnClickListener { submitPairing() }
        binding.btnSubmitPairing.applyPressScaleFeedback()

        onBackPressedDispatcher.addCallback(this) { finishBackward() }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        stationInt = prefs.getInt("station_int", 1)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.tab_bag_pairing)
    }

    /** Everything scanned and every value confirmed — only then can the pairing go out. */
    private fun updateSubmitEnabled() {
        binding.btnSubmitPairing.isEnabled =
            binding.etTag.text.toString().isNotBlank() &&
            binding.etBarcode.text.toString().isNotBlank() &&
            bagWeight.confirmed && bagCount.confirmed && batchRef.confirmed
    }

    /**
     * Provisional wire shape — the final MQTT message set for the stripped-down app is still to
     * be agreed with the station side. Payload follows the app's usual envelope (`ts`,
     * `deviceId`), plus the operator's session for attribution.
     */
    private fun submitPairing() {
        val deviceId = DeviceIdentity.deviceId(this)
        val payload = JSONObject().apply {
            put("ts", Instant.now().toString())
            put("deviceId", deviceId)
            put("operatorSessionId", OperatorSessionHolder.currentSessionIdOrEmpty())
            put("tagId", binding.etTag.text.toString().trim())
            put("barcode", binding.etBarcode.text.toString().trim())
            put("bagWeight", bagWeight.value())
            put("bagCount", bagCount.value())
            put("batchReference", batchRef.value())
        }

        binding.btnSubmitPairing.isEnabled = false
        binding.tvPairingStatus.visibility = View.VISIBLE
        binding.tvPairingStatus.text = getString(R.string.status_sending)
        binding.tvPairingStatus.setTextColor(getColor(R.color.text_muted))

        val topic = MqttTopics.deviceRequest(stationInt, deviceId, "bag_pairing")
        MqttManager.getInstance(this).publish(topic, payload.toString()) { throwable ->
            runOnUiThread {
                if (throwable != null) {
                    binding.tvPairingStatus.text = getString(R.string.status_send_failed)
                    binding.tvPairingStatus.setTextColor(getColor(R.color.danger))
                    updateSubmitEnabled()
                } else {
                    binding.tvPairingStatus.text = getString(R.string.msg_pairing_sent)
                    binding.tvPairingStatus.setTextColor(getColor(R.color.success))
                    // Clear for the next bag; the confirmed values re-open so nothing stale
                    // rides along into the next pairing unchecked.
                    binding.etTag.setText("")
                    binding.etBarcode.setText("")
                    bagWeight.reset()
                    bagCount.reset()
                    batchRef.reset()
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

/** Minimal TextWatcher wrapper so field listeners read as one line at the call site. */
private class SimpleTextWatcher(private val onChanged: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) = onChanged()
}
