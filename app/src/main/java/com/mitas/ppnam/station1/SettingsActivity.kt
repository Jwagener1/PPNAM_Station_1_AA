package com.mitas.ppnam.station1

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.mitas.ppnam.station1.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread { binding.connectionPill.setStatus(status) }
    }

    // Ported from Station 2's SettingsViewModel so both apps' supervisor lock behave identically.
    private val correctPin = "079545"
    private var failedPinAttempts = 0
    private var lockedOutUntilMs = 0L

    private companion object {
        const val MAX_PIN_ATTEMPTS = 5
        const val PIN_LOCKOUT_MS = 30_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()

        setupToolbar()
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val currentScannerInt = prefs.getInt("scanner_int", 1)
        val currentStationInt = prefs.getInt("station_int", 1)

        binding.etSettingsDeviceId.setText(currentScannerInt.toString())
        binding.etSettingsStationId.setText(currentStationInt.toString())

        binding.btnUnlock.setOnClickListener { submitPin() }
        binding.etPin.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitPin()
                true
            } else {
                false
            }
        }

        binding.btnSaveSettings.setOnClickListener {
            val newScannerStr = binding.etSettingsDeviceId.text.toString().trim()
            val newStationStr = binding.etSettingsStationId.text.toString().trim()

            val newScannerInt = newScannerStr.toIntOrNull()
            val newStationInt = newStationStr.toIntOrNull()

            if (newScannerInt != null && newStationInt != null) {
                // 1. Properly disconnect the OLD ID first
                MqttManager.getInstance(this).disconnect {
                    runOnUiThread {
                        // 2. Save the NEW IDs after the old one is offline
                        prefs.edit()
                            .putInt("scanner_int", newScannerInt)
                            .putInt("station_int", newStationInt)
                            .apply()

                        // 3. Reconnect with the new ID
                        MqttManager.getInstance(this).connect()

                        // Restart app to apply changes
                        val intent = Intent(this, MainActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        startActivity(intent)
                        finish()
                    }
                }
            } else {
                if (newScannerInt == null) binding.etSettingsDeviceId.error = "Invalid number"
                if (newStationInt == null) binding.etSettingsStationId.error = "Invalid number"
            }
        }

        binding.btnUnassignMode.setOnClickListener {
            startActivityForward(Intent(this, UnassignActivity::class.java))
        }

        binding.btnReassignMode.setOnClickListener {
            startActivityForward(Intent(this, ReassignActivity::class.java))
        }

        binding.btnUnlock.applyPressScaleFeedback()
        binding.btnSaveSettings.applyPressScaleFeedback()
        binding.btnUnassignMode.applyPressScaleFeedback()
        binding.btnReassignMode.applyPressScaleFeedback()

        onBackPressedDispatcher.addCallback(this) { finishBackward() }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun submitPin() {
        val now = System.currentTimeMillis()
        if (now < lockedOutUntilMs) {
            val remainingSec = (lockedOutUntilMs - now + 999) / 1_000
            showLockoutMessage("Too many attempts. Try again in ${remainingSec}s.")
            binding.etPin.setText("")
            return
        }

        if (binding.etPin.text.toString() == correctPin) {
            failedPinAttempts = 0
            hidePinMessages()
            binding.cardPinLock.visibility = View.GONE
            binding.groupSettingsFields.visibility = View.VISIBLE
        } else {
            binding.etPin.setText("")
            failedPinAttempts++
            if (failedPinAttempts >= MAX_PIN_ATTEMPTS) {
                lockedOutUntilMs = now + PIN_LOCKOUT_MS
                failedPinAttempts = 0
                showLockoutMessage("Too many attempts. Try again in ${PIN_LOCKOUT_MS / 1_000}s.")
            } else {
                val left = MAX_PIN_ATTEMPTS - failedPinAttempts
                showErrorMessage("Incorrect PIN. $left attempt${if (left == 1) "" else "s"} left before lockout.")
            }
        }
    }

    private fun showErrorMessage(message: String) {
        binding.tvPinError.text = message
        binding.tvPinError.visibility = View.VISIBLE
        binding.tvPinLockout.visibility = View.GONE
    }

    private fun showLockoutMessage(message: String) {
        binding.tvPinLockout.text = message
        binding.tvPinLockout.visibility = View.VISIBLE
        binding.tvPinError.visibility = View.GONE
    }

    private fun hidePinMessages() {
        binding.tvPinError.visibility = View.GONE
        binding.tvPinLockout.visibility = View.GONE
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
