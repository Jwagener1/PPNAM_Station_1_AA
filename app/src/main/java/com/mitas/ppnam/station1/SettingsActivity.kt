package com.mitas.ppnam.station1

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
        runOnUiThread {
            binding.connectionPill.setStatus(status)
            updateDiagnostics(status)
        }
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

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.tvDeviceId.text = DeviceIdentity.deviceId(this)
        setupSessionSection()

        val settingsRepository = SettingsRepository(this)
        val current = settingsRepository.brokerSettings()

        binding.etBrokerHost.setText(current.host)
        binding.etBrokerPort.setText(current.port.toString())
        binding.swBrokerWebSocket.isChecked = current.useWebSocket
        binding.swBrokerTls.isChecked = current.useTls
        binding.etBrokerUsername.setText(current.username)
        // The password field stays empty: the stored credential is never echoed back into the UI.
        // A blank field on save means "keep the provisioned password" (see save below).

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
            val host = binding.etBrokerHost.text.toString().trim()
            val port = BrokerSettings.parsePort(binding.etBrokerPort.text.toString())
            if (host.isBlank()) {
                binding.etBrokerHost.error = "Host required"
                return@setOnClickListener
            }
            if (port == null) {
                binding.etBrokerPort.error = "Invalid port (1–65535)"
                return@setOnClickListener
            }

            val typedPassword = binding.etBrokerPassword.text.toString()
            val newSettings = BrokerSettings(
                host = host,
                port = port,
                useWebSocket = binding.swBrokerWebSocket.isChecked,
                useTls = binding.swBrokerTls.isChecked,
                username = binding.etBrokerUsername.text.toString().trim(),
                // Blank field keeps the already-provisioned password: the repository only
                // writes a non-blank password to the Keystore.
                password = typedPassword.ifBlank { settingsRepository.brokerSettings().password },
            )

            // 1. Properly disconnect from the OLD broker first
            MqttManager.getInstance(this).disconnect {
                runOnUiThread {
                    // 2. Save the new settings after the old presence is offline
                    if (!settingsRepository.save(newSettings)) {
                        binding.etBrokerPassword.error = "Could not store the password securely"
                        MqttManager.getInstance(this).connect()
                        return@runOnUiThread
                    }

                    // 3. Reconnect against the new broker
                    MqttManager.getInstance(this).connect()

                    // Restart app to apply changes
                    val intent = Intent(this, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    finish()
                }
            }
        }

        binding.btnUnlock.applyPressScaleFeedback()
        binding.btnSaveSettings.applyPressScaleFeedback()
        binding.btnLogOut.applyPressScaleFeedback()

        onBackPressedDispatcher.addCallback(this) { finishBackward() }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    /**
     * The Diagnostics card, mirroring Station 2's SettingsScreen: broker link and station
     * presence are separate failures with separate remedies, and the composite pill can only
     * name one of them at a time — so both get their own row here.
     */
    private fun updateDiagnostics(status: ConnectionStatus) {
        val green = getColor(R.color.success)
        val blue = getColor(R.color.primary_action)
        val red = getColor(R.color.danger)
        val muted = getColor(R.color.text_muted)

        when (status) {
            ConnectionStatus.CONNECTED, ConnectionStatus.STATION_OFFLINE ->
                binding.pillBroker.setAppearance(green, "Connected")
            ConnectionStatus.RECONNECTING ->
                binding.pillBroker.setAppearance(blue, "Reconnecting")
            ConnectionStatus.OFFLINE ->
                binding.pillBroker.setAppearance(red, "Disconnected")
        }

        // With the broker down, the retained presence value is stale rather than false — saying
        // "offline" there would blame the station for the broker's fault.
        when (status) {
            ConnectionStatus.CONNECTED -> binding.pillStation.setAppearance(green, "Online")
            ConnectionStatus.STATION_OFFLINE -> binding.pillStation.setAppearance(blue, "Offline")
            else -> binding.pillStation.setAppearance(muted, "Unknown")
        }
    }

    /**
     * The Session card, mirroring Station 2's: the home screen's operator label is one route to
     * switching users, and Settings is the obvious second home for it.
     */
    private fun setupSessionSection() {
        val session = OperatorSessionHolder.session
        if (session == null) {
            binding.groupSession.visibility = View.GONE
            return
        }
        binding.groupSession.visibility = View.VISIBLE
        binding.tvSignedInAs.text =
            if (session.role.isNotBlank()) "${session.operatorName} · ${session.role}"
            else session.operatorName
        binding.btnLogOut.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
                .setTitle(getString(R.string.logout_dialog_title))
                .setMessage(getString(R.string.logout_dialog_message))
                .setPositiveButton(getString(R.string.btn_log_out)) { _, _ ->
                    AuthClient(this).logout {
                        startActivity(Intent(this, LoginActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        })
                        finish()
                    }
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
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
