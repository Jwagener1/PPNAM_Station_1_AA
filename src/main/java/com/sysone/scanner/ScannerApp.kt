package com.sysone.scanner

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ScannerApp : Application() {
    
    private var currentActivity: Activity? = null
    private val SETTINGS_RFID = "E28011700000021B2F6E9827"
    
    // Smart Request Handling State
    private var lastDocNumber: String? = null
    private var lastTimestamp: String? = null
    private var userRejectedDoc: String? = null
    private var activeDialog: AlertDialog? = null

    private val rfidShortcutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.rscja.scanner.action.scanner.RFID") {
                val data = intent.getStringExtra("data")
                if (data == SETTINGS_RFID) {
                    val settingsIntent = Intent(context, SettingsActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(settingsIntent)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) { currentActivity = activity }
            override fun onActivityResumed(activity: Activity) { 
                currentActivity = activity 
                checkStationStatus(activity)
            }
            override fun onActivityPaused(activity: Activity) { if (currentActivity == activity) currentActivity = null }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Register Global RFID Shortcut Receiver
        val rfidFilter = IntentFilter("com.rscja.scanner.action.scanner.RFID")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rfidShortcutReceiver, rfidFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(rfidShortcutReceiver, rfidFilter)
        }

        // Initialize and connect MQTT globally
        val mqtt = MqttManager.getInstance(this)
        mqtt.connect()
        
        mqtt.addStationStatusListener { online ->
            if (!online) {
                currentActivity?.let { checkStationStatus(it) }
            }
        }

        setupSapProductRequestListener()
        setupTagAssignmentRequestListener()
    }

    private fun setupSapProductRequestListener() {
        val mqtt = MqttManager.getInstance(this)
        val requestTopic = "PPNAM/+/sap_products_request"

        mqtt.subscribe(requestTopic) { publish ->
            val topic = publish.topic.toString()
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val stationInt = prefs.getInt("station_int", 1)
            
            if (topic != "PPNAM/station_$stationInt/sap_products_request") return@subscribe

            val payload = String(publish.payloadAsBytes)
            Log.d("ScannerApp", "Received SAP Product Request: $payload")
            
            try {
                val json = JSONObject(payload)
                val ts = json.optString("ts", "")
                val sessionId = json.optString("sessionId", UUID.randomUUID().toString())
                val docNum = json.optString("sourceDocumentNumber", "")
                val docType = json.optString("sourceDocumentType", "")

                // Smart Filter: Ignore if same doc and user said "No"
                if (docNum == userRejectedDoc) {
                    Log.d("ScannerApp", "Ignoring request for rejected doc: $docNum")
                    return@subscribe
                }

                // Smart Filter: If it's an update for an already active doc, just update prefs if approved, 
                // or refresh the dialog if pending.
                if (docNum == lastDocNumber && ts == lastTimestamp) return@subscribe

                lastDocNumber = docNum
                lastTimestamp = ts

                currentActivity?.runOnUiThread {
                    showRequestDialog(sessionId, docNum, docType)
                }
            } catch (e: Exception) {
                Log.e("ScannerApp", "Error parsing SAP request JSON", e)
            }
        }
    }

    private fun showRequestDialog(sessionId: String, docNum: String, docType: String) {
        val activity = currentActivity ?: return
        
        // Don't stack dialogs
        activeDialog?.dismiss()
        
        activeDialog = AlertDialog.Builder(activity)
            .setTitle("SAP Product Request")
            .setMessage("New SAP product request received for Document #$docNum ($docType). Would you like to process it?")
            .setPositiveButton("Process") { _, _ ->
                userRejectedDoc = null // Reset rejection if they say yes
                // Skip SAP Lookup: Save session and move to step 1 (SAP data received)
                val sapPrefs = getSharedPreferences("sap_data", Context.MODE_PRIVATE)
                sapPrefs.edit()
                    .putString("session_id", sessionId)
                    .putString("last_doc_number", docNum)
                    .putString("last_doc_type", docType)
                    .putInt("current_step", 1) // Step 1: Document details fetched
                    .apply()

                val intent = Intent(activity, ProductRequestActivity::class.java).apply {
                    putExtra("doc_number", docNum)
                    putExtra("doc_type", docType)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                activity.startActivity(intent)
            }
            .setNegativeButton("Cancel") { _, _ ->
                userRejectedDoc = docNum // Mark as rejected for this specific doc
            }
            .show()
    }

    private fun setupTagAssignmentRequestListener() {
        val mqtt = MqttManager.getInstance(this)
        val requestTopic = "PPNAM/+/tag_assignment_request"

        mqtt.subscribe(requestTopic) { publish ->
            val topic = publish.topic.toString()
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val stationInt = prefs.getInt("station_int", 1)

            if (topic != "PPNAM/station_$stationInt/tag_assignment_request") return@subscribe

            val payload = String(publish.payloadAsBytes)
            Log.d("ScannerApp", "Received Tag Assignment Request: $payload")

            try {
                val json = JSONObject(payload)
                val ts = json.optString("ts", "")
                val sessionId = json.optString("sessionId", "")
                val docNum = json.optString("sourceDocumentNumber", "")
                val docType = json.optString("sourceDocumentType", "")
                val selectedProductCodes = json.optJSONArray("selectedProductCodes")
                val productsArray = json.optJSONArray("products")

                // Smart Filter: Ignore if same doc and user said "No"
                if (docNum == userRejectedDoc) {
                    return@subscribe
                }

                val productsList = ArrayList<String>()
                if (productsArray != null) {
                    for (i in 0 until productsArray.length()) {
                        val p = productsArray.getJSONObject(i)
                        val code = p.getString("productCode")
                        val desc = p.getString("productDescription")
                        
                        if (selectedProductCodes == null || isCodeInArray(code, selectedProductCodes)) {
                            productsList.add("$code - $desc")
                        }
                    }
                }

                // If it's an update and we are already on the TagAssignment screen, just update intent/prefs
                if (docNum == lastDocNumber && currentActivity is TagAssignmentActivity) {
                    lastTimestamp = ts
                    updateActiveTagAssignment(sessionId, docNum, docType, productsList)
                    return@subscribe
                }

                // If same doc and same ts, ignore
                if (docNum == lastDocNumber && ts == lastTimestamp) return@subscribe

                lastDocNumber = docNum
                lastTimestamp = ts

                currentActivity?.runOnUiThread {
                    showTagAssignmentDialog(sessionId, docNum, docType, productsList)
                }
            } catch (e: Exception) {
                Log.e("ScannerApp", "Error parsing Tag Assignment request JSON", e)
            }
        }
    }

    private fun updateActiveTagAssignment(sessionId: String, docNum: String, docType: String, products: ArrayList<String>) {
        // Silently update SharedPreferences
        val sapPrefs = getSharedPreferences("sap_data", Context.MODE_PRIVATE)
        sapPrefs.edit()
            .putString("session_id", sessionId)
            .putString("last_doc_number", docNum)
            .putString("last_doc_type", docType)
            .apply()
            
        // The TagAssignmentActivity itself listens for sap_products_response
        // which usually follows this message, but we could also broadcast a local update if needed.
        Log.d("ScannerApp", "Silently updated active TagAssignment context for doc $docNum")
    }

    private fun isCodeInArray(code: String, array: JSONArray): Boolean {
        for (i in 0 until array.length()) {
            if (array.optString(i) == code) return true
        }
        return false
    }

    private fun showTagAssignmentDialog(sessionId: String, docNum: String, docType: String, products: ArrayList<String>) {
        val activity = currentActivity ?: return

        activeDialog?.dismiss()

        activeDialog = AlertDialog.Builder(activity)
            .setTitle("Tag Assignment Request")
            .setMessage("Ready to start tag assignment for Document #$docNum?\nSelected Products: ${products.size}")
            .setPositiveButton("Start Assignment") { _, _ ->
                userRejectedDoc = null
                // Save context
                val sapPrefs = getSharedPreferences("sap_data", Context.MODE_PRIVATE)
                sapPrefs.edit()
                    .putString("session_id", sessionId)
                    .putString("last_doc_number", docNum)
                    .putString("last_doc_type", docType)
                    .putInt("current_step", 2) // Step 2: Product selection complete, ready for assignment
                    .apply()

                val intent = Intent(activity, TagAssignmentActivity::class.java).apply {
                    putStringArrayListExtra("selected_products", products)
                    putExtra("doc_number", docNum)
                    putExtra("doc_type", docType)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                activity.startActivity(intent)
            }
            .setNegativeButton("Cancel") { _, _ ->
                userRejectedDoc = docNum
            }
            .show()
    }

    private fun checkStationStatus(activity: Activity) {
        val mqtt = MqttManager.getInstance(this)
        if (!mqtt.isStationOnline && activity !is MainActivity) {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }
    }
}
