package com.mitas.ppnam.station1

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mitas.ppnam.station1.databinding.ActivityMainBinding
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var scannerInt = 1

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread {
            binding.connectionPill.setStatus(status)
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
        forceLightStatusBarIcons()

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupDashboard()

        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)
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
            startActivityForward(Intent(this, ManualSapEntryActivity::class.java))
        }

        binding.tileProductRequest.setOnClickListener {
            val sapPrefs = getSharedPreferences("sap_data", Context.MODE_PRIVATE)
            val intent = Intent(this, ProductRequestActivity::class.java).apply {
                putExtra("doc_number", sapPrefs.getString("last_doc_number", ""))
                putExtra("doc_type", sapPrefs.getString("last_doc_type", ""))
            }
            startActivityForward(intent)
        }

        binding.tileTagAssignment.setOnClickListener {
            startActivityForward(Intent(this, TagAssignmentActivity::class.java))
        }

        binding.tileOffload.setOnClickListener {
            startActivityForward(Intent(this, AssignmentActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            startActivityForward(Intent(this, SettingsActivity::class.java))
        }

        binding.tileSapLookup.applyPressScaleFeedback()
        binding.tileProductRequest.applyPressScaleFeedback()
        binding.tileTagAssignment.applyPressScaleFeedback()
        binding.tileOffload.applyPressScaleFeedback()
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

    override fun onDestroy() {
        super.onDestroy()
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
        MqttManager.getInstance(this).removeStationStatusListener(stationStatusListener)
    }
}
