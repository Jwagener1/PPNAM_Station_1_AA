package com.mitas.ppnam.station1

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mitas.ppnam.station1.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        // Load as int, default to 1 if not set
        val currentScannerInt = prefs.getInt("scanner_int", 1)
        val currentStationInt = prefs.getInt("station_int", 1)

        binding.etSettingsDeviceId.setText(currentScannerInt.toString())
        binding.etSettingsStationId.setText(currentStationInt.toString())

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
            startActivity(Intent(this, UnassignActivity::class.java))
        }

        binding.btnReassignMode.setOnClickListener {
            startActivity(Intent(this, ReassignActivity::class.java))
        }
    }
}
