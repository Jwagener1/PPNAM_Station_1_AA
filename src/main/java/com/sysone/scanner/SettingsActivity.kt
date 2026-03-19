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
        val currentDeviceId = prefs.getString("device_id", "C72-001")
        val currentMode = prefs.getString("mode", "BOTH") // BOTH, BAG_WEIGHT, TAG_ASSIGNMENT

        binding.etSettingsDeviceId.setText(currentDeviceId)
        when (currentMode) {
            "BAG_WEIGHT" -> binding.rbBagWeight.isChecked = true
            "TAG_ASSIGNMENT" -> binding.rbTagAssignment.isChecked = true
            else -> binding.rbBagWeight.isChecked = true // Default
        }

        binding.btnSaveSettings.setOnClickListener {
            val newDeviceId = binding.etSettingsDeviceId.text.toString().trim()
            val newMode = if (binding.rbBagWeight.isChecked) "BAG_WEIGHT" else "TAG_ASSIGNMENT"

            if (newDeviceId.isNotEmpty()) {
                prefs.edit()
                    .putString("device_id", newDeviceId)
                    .putString("mode", newMode)
                    .apply()

                // Restart app to apply changes
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                finish()
            }
        }
    }
}
