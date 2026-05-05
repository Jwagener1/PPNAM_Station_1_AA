package com.sysone.scanner

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sysone.scanner.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        // Load as int, default to 1 if not set
        val currentScannerInt = prefs.getInt("scanner_int", 1)

        binding.etSettingsDeviceId.setText(currentScannerInt.toString())

        binding.btnSaveSettings.setOnClickListener {
            val newScannerStr = binding.etSettingsDeviceId.text.toString().trim()
            val newScannerInt = newScannerStr.toIntOrNull()

            if (newScannerInt != null) {
                prefs.edit()
                    .putInt("scanner_int", newScannerInt)
                    .apply()

                // Force MQTT reconnect with new device ID
                MqttManager.getInstance(this).connect(force = true)

                // Restart app to apply changes
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                finish()
            } else {
                binding.etSettingsDeviceId.error = "Please enter a valid number"
            }
        }

        binding.btnUnassignMode.setOnClickListener {
            startActivity(Intent(this, UnassignActivity::class.java))
        }
    }
}
