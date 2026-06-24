package com.mitas.ppnam.station1

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mitas.ppnam.station1.databinding.ActivityMainBinding
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var scannerInt = 1
    
    enum class ConnectionStatus(@get:StringRes val stringResId: Int, val dotDrawableResId: Int) {
        ONLINE(R.string.status_online, R.drawable.status_dot_green),
        OFFLINE(R.string.status_offline, R.drawable.status_dot_red),
        CONNECTING(R.string.status_connecting, R.drawable.status_dot_amber)
    }

    private val connectionListener: (Boolean) -> Unit = { connected ->
        runOnUiThread {
            updateStatusUI(if (connected) ConnectionStatus.ONLINE else ConnectionStatus.OFFLINE)
        }
    }

    private val stationStatusListener: (Boolean) -> Unit = { online ->
        runOnUiThread {
            if (online) {
                binding.layoutStationOffline.visibility = android.view.View.GONE
            } else {
                binding.layoutStationOffline.visibility = android.view.View.VISIBLE
                // Bring MainActivity to front and clear others
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        loadSettings()

        binding = ActivityMainBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupDashboard()
        updateStatusUI(ConnectionStatus.CONNECTING)
        
        MqttManager.getInstance(this).addConnectionListener(connectionListener)
        MqttManager.getInstance(this).addStationStatusListener(stationStatusListener)
    }

    override fun onResume() {
        super.onResume()
        updateTileStates()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        scannerInt = prefs.getInt("scanner_int", 1)
    }

    private fun setupDashboard() {
        binding.tileSapLookup.setOnClickListener {
            startActivity(Intent(this, ManualSapEntryActivity::class.java))
        }

        binding.tileProductRequest.setOnClickListener {
            val sapPrefs = getSharedPreferences("sap_data", Context.MODE_PRIVATE)
            val intent = Intent(this, ProductRequestActivity::class.java).apply {
                putExtra("doc_number", sapPrefs.getString("last_doc_number", ""))
                putExtra("doc_type", sapPrefs.getString("last_doc_type", ""))
            }
            startActivity(intent)
        }

        binding.tileTagAssignment.setOnClickListener {
            startActivity(Intent(this, TagAssignmentActivity::class.java))
        }

        binding.tileOffload.setOnClickListener {
            startActivity(Intent(this, AssignmentActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            showPasswordDialog()
        }
    }

    private fun showPasswordDialog() {
        val input = android.widget.EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.setPadding(50, 20, 50, 20)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Settings Access")
            .setMessage("Please enter password to access settings:")
            .setView(input)
            .setPositiveButton("Access") { _, _ ->
                val password = input.text.toString()
                if (password == "Mit@s_") {
                    startActivity(Intent(this, SettingsActivity::class.java))
                } else {
                    android.widget.Toast.makeText(this, "Incorrect password", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateTileStates() {
        val sapPrefs = getSharedPreferences("sap_data", Context.MODE_PRIVATE)
        val sessionId = sapPrefs.getString("session_id", null)
        val currentStep = sapPrefs.getInt("current_step", 0)
        val hasSession = !sessionId.isNullOrEmpty()

        // 1. SAP Lookup is always enabled (Step 0)
        // 2. Product Request enabled after SAP Lookup (Step 1+)
        setTileEnabled(binding.tileProductRequest, hasSession && currentStep >= 1)
        
        // 3. Tag Assignment enabled after Product Request (Step 2+)
        setTileEnabled(binding.tileTagAssignment, hasSession && currentStep >= 2)
        
        // 4. Offloading enabled after Tag Assignment (Step 3+)
        setTileEnabled(binding.tileOffload, hasSession && currentStep >= 3)
    }

    private fun setTileEnabled(view: com.google.android.material.card.MaterialCardView, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1.0f else 0.5f
        view.isClickable = enabled
        view.isFocusable = enabled
    }

    private fun updateStatusUI(status: ConnectionStatus) {
        binding.tvStatus.setText(status.stringResId)
        binding.imgStatusDot.setImageResource(status.dotDrawableResId)
    }

    override fun onDestroy() {
        super.onDestroy()
        MqttManager.getInstance(this).removeConnectionListener(connectionListener)
        MqttManager.getInstance(this).removeStationStatusListener(stationStatusListener)
    }
}
