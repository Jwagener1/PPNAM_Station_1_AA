package com.mitas.ppnam.station1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mitas.ppnam.station1.databinding.ActivityLoginBinding

/**
 * Operator login, mirroring Station 2 AA's LoginScreen: username/password (SCRAM under the hood)
 * or an RFID badge scan, with the same connection pill and a Settings shortcut in the top bar.
 * This is the launcher activity — MainActivity requires a session.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authClient: AuthClient

    /** Blocks re-entry for the whole logging-in -> navigated span, exactly like Station 2's
     *  LoginViewModel: a repeat badge read arriving after success but before navigation must not
     *  start a second, concurrent login that could overwrite the just-established session. */
    private var loginInFlight = false
    private var loggedIn = false

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread { binding.connectionPill.setStatus(status) }
    }

    private val badgeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.rscja.scanner.action.scanner.RFID") {
                val tag = intent.getStringExtra("data") ?: return
                if (tag.isNotBlank()) attemptBadgeLogin(tag)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Already logged in (e.g. relaunched from recents) — straight to the dashboard.
        if (OperatorSessionHolder.session != null) {
            loggedIn = true // also blocks a badge scan racing the finish() below
            goHome()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()

        authClient = AuthClient(this)
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        binding.btnLogin.setOnClickListener { submitCredentials() }
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitCredentials()
                true
            } else {
                false
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivityForward(Intent(this, SettingsActivity::class.java))
        }

        binding.btnLogin.applyPressScaleFeedback()

        // Back from the launcher screen would drop to the Android home screen without warning —
        // easy to hit by accident on a shared handheld. Ask first, like Station 2.
        onBackPressedDispatcher.addCallback(this) { showExitDialog() }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.rscja.scanner.action.scanner.RFID")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(badgeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(badgeReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(badgeReceiver)
    }

    private fun submitCredentials() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        if (username.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.error_fill_all_fields))
            return
        }
        if (loginInFlight || loggedIn) return
        setLoggingIn(true)
        authClient.login(username, password) { result -> onLoginResult(result) }
    }

    private fun attemptBadgeLogin(badgeTag: String) {
        if (loginInFlight || loggedIn) return
        runOnUiThread {
            setLoggingIn(true)
            authClient.loginWithBadge(badgeTag) { result -> onLoginResult(result) }
        }
    }

    private fun onLoginResult(result: Result<OperatorSession>) {
        result
            .onSuccess {
                loggedIn = true
                goHome()
            }
            .onFailure { e ->
                setLoggingIn(false)
                showError(e.message ?: "Login failed")
            }
    }

    private fun setLoggingIn(inFlight: Boolean) {
        loginInFlight = inFlight
        binding.btnLogin.isEnabled = !inFlight
        binding.etUsername.isEnabled = !inFlight
        binding.etPassword.isEnabled = !inFlight
        binding.btnLogin.text = if (inFlight) "" else getString(R.string.btn_log_in)
        binding.progressLogin.visibility = if (inFlight) View.VISIBLE else View.GONE
        if (inFlight) binding.tvLoginError.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.tvLoginError.text = message
        binding.tvLoginError.visibility = View.VISIBLE
    }

    private fun goHome() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
            .setTitle(getString(R.string.exit_dialog_title))
            .setMessage(getString(R.string.exit_dialog_message))
            .setPositiveButton(getString(R.string.exit_dialog_close)) { _, _ -> finishAffinity() }
            .setNegativeButton(getString(R.string.exit_dialog_stay), null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::authClient.isInitialized) {
            MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
        }
    }
}
