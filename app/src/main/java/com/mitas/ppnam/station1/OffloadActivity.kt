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
import com.mitas.ppnam.station1.databinding.ActivityOffloadBinding
import org.json.JSONObject

/**
 * Offload (contract v3.1.0 §6):
 *
 *  1. Scan the pallet's RFID tag and barcode, then Match Pallet -> `offload_scan`. A matched
 *     result carries the pallet's expected bagWeight/bagCount/batchReference as prefill plus
 *     the open document the pallet belongs to (documentType/documentNumber and pallet
 *     progress) — the scanner is never locked to a document; the reference is per-pallet
 *     metadata from this lookup.
 *  2. Review/edit the prefilled values, then Confirm Offload -> `offload_confirm` with the
 *     final typed values and the document reference repeated verbatim. The station
 *     re-validates the pair at confirm time, so no client-side pairing state must survive
 *     between the two steps.
 *  3. After each accepted confirm: "Are you done?" — Done closes the looked-up document as
 *     Short / Complete / Over via `offload_complete`; Next Pallet just keeps scanning.
 *
 * Value-validation rejections (INVALID_BAG_WEIGHT / INVALID_BAG_COUNT /
 * BATCH_REFERENCE_REQUIRED) keep the operator on the edit step; any other rejection returns
 * to scanning.
 */
class OffloadActivity : AppCompatActivity() {

    private enum class Step { SCAN, MATCHING, EDIT, CONFIRMING, CLOSING }

    private lateinit var binding: ActivityOffloadBinding
    private lateinit var workflow: WorkflowClient
    private var step = Step.SCAN
    private var matchedTag = ""
    private var matchedBarcode = ""
    private var currentDocument: OffloadDocument? = null

    private val editStepErrors = setOf("INVALID_BAG_WEIGHT", "INVALID_BAG_COUNT", "BATCH_REFERENCE_REQUIRED")

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread { binding.connectionPill.setStatus(status) }
    }

    private val rfidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.rscja.scanner.action.scanner.RFID") {
                val data = intent.getStringExtra("data")
                if (!data.isNullOrEmpty() && step == Step.SCAN) {
                    binding.etTag.setText(data)
                    updateMatchEnabled()
                }
            }
        }
    }

    private val barcodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.scanner.broadcast") {
                val data = intent.getStringExtra("data")
                if (!data.isNullOrEmpty() && step == Step.SCAN) {
                    binding.etBarcode.setText(data)
                    updateMatchEnabled()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOffloadBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        forceLightStatusBarIcons()

        setupToolbar()
        workflow = WorkflowClient(this)
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.etTag.addTextChangedListener(SimpleTextWatcher { updateMatchEnabled() })
        binding.etBarcode.addTextChangedListener(SimpleTextWatcher { updateMatchEnabled() })

        binding.btnMatchPallet.setOnClickListener { matchPallet() }
        binding.btnMatchPallet.applyPressScaleFeedback()
        binding.btnConfirmOffload.setOnClickListener { confirmOffload() }
        binding.btnConfirmOffload.applyPressScaleFeedback()
        binding.btnBackToScan.setOnClickListener { enterScanStep(clearScan = false) }

        onBackPressedDispatcher.addCallback(this) { finishBackward() }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.tab_offload)
    }

    // ---- step transitions --------------------------------------------------------------------

    private fun enterScanStep(clearScan: Boolean) {
        step = Step.SCAN
        currentDocument = null
        binding.cardValues.visibility = View.GONE
        binding.tvConfirmStatus.visibility = View.GONE
        binding.tvScanStatus.visibility = View.GONE
        binding.etTag.isEnabled = true
        binding.etBarcode.isEnabled = true
        if (clearScan) {
            binding.etTag.setText("")
            binding.etBarcode.setText("")
        }
        binding.scrollOffload.post { binding.scrollOffload.smoothScrollTo(0, 0) }
        updateMatchEnabled()
    }

    private fun enterEditStep(
        tagId: String,
        barcode: String,
        prefill: OffloadPrefill,
        document: OffloadDocument,
    ) {
        step = Step.EDIT
        matchedTag = tagId
        matchedBarcode = barcode
        currentDocument = document
        binding.tvScanStatus.visibility = View.GONE
        binding.cardValues.visibility = View.VISIBLE
        binding.tvConfirmStatus.visibility = View.GONE
        binding.tvDocumentInfo.text = getString(
            R.string.label_document_progress,
            document.documentNumber, document.palletsScanned, document.palletsExpected,
        )
        binding.etBagWeight.setText(WorkflowMessages.formatWeight(prefill.bagWeight))
        binding.etBagCount.setText(prefill.bagCount.toString())
        binding.etBatchRef.setText(prefill.batchReference)
        binding.btnConfirmOffload.isEnabled = true
        // On the C72's display the values card lands below the fold; bring it into view so
        // the operator sees the prefill and the Confirm button without hunting for them.
        binding.scrollOffload.post { binding.scrollOffload.smoothScrollTo(0, binding.cardValues.top) }
    }

    private fun updateMatchEnabled() {
        binding.btnMatchPallet.isEnabled = step == Step.SCAN &&
            binding.etTag.text.toString().isNotBlank() &&
            binding.etBarcode.text.toString().isNotBlank()
    }

    // ---- step 1: offload_scan ----------------------------------------------------------------

    private fun matchPallet() {
        if (step != Step.SCAN) return
        val tagId = binding.etTag.text.toString().trim()
        val barcode = binding.etBarcode.text.toString().trim()

        step = Step.MATCHING
        binding.btnMatchPallet.isEnabled = false
        binding.etTag.isEnabled = false
        binding.etBarcode.isEnabled = false
        showScanStatus(getString(R.string.status_sending), R.color.text_muted)

        val payload = WorkflowMessages.offloadScan(
            deviceId = DeviceIdentity.deviceId(this),
            operatorSessionId = OperatorSessionHolder.currentSessionIdOrEmpty(),
            tagId = tagId,
            barcode = barcode,
        )
        workflow.request(
            requestType = "offload_scan",
            responseType = "offload_scan_result",
            payload = payload,
            matches = { it.optString("tagId") == tagId && it.optString("barcode") == barcode },
        ) { result ->
            if (step != Step.MATCHING) return@request
            result
                .onSuccess { json ->
                    if (json.optBoolean("matched", false)) {
                        val prefill = OffloadPrefill.fromScanResult(json)
                        val document = OffloadDocument.fromScanResult(json)
                        if (prefill != null && document != null) {
                            enterEditStep(tagId, barcode, prefill, document)
                        } else {
                            // "matched" without usable prefill or document breaks §6.1/§6.2 —
                            // treat as no match.
                            backToScanWithError(getString(R.string.error_incomplete_match))
                        }
                    } else {
                        if (handleSessionRejection(json)) return@request
                        backToScanWithError(stationReason(json))
                    }
                }
                .onFailure { e -> backToScanWithError(failureText(e)) }
        }
    }

    private fun backToScanWithError(message: String) {
        enterScanStep(clearScan = false)
        showScanStatus(message, R.color.danger)
    }

    // ---- step 2: offload_confirm -------------------------------------------------------------

    private fun confirmOffload() {
        if (step != Step.EDIT) return
        val document = currentDocument
            ?: return backToScanWithError(getString(R.string.error_incomplete_match))
        val weight = OffloadInput.parseWeight(binding.etBagWeight.text.toString())
            ?: return showConfirmStatus(getString(R.string.error_invalid_weight), R.color.danger)
        val count = OffloadInput.parseCount(binding.etBagCount.text.toString())
            ?: return showConfirmStatus(getString(R.string.error_invalid_count), R.color.danger)
        val batch = OffloadInput.parseBatch(binding.etBatchRef.text.toString())
            ?: return showConfirmStatus(getString(R.string.error_batch_required), R.color.danger)

        step = Step.CONFIRMING
        binding.btnConfirmOffload.isEnabled = false
        showConfirmStatus(getString(R.string.status_sending), R.color.text_muted)

        val payload = WorkflowMessages.offloadConfirm(
            deviceId = DeviceIdentity.deviceId(this),
            operatorSessionId = OperatorSessionHolder.currentSessionIdOrEmpty(),
            tagId = matchedTag,
            barcode = matchedBarcode,
            documentType = document.documentType,
            documentNumber = document.documentNumber,
            bagWeight = weight,
            bagCount = count,
            batchReference = batch,
        )
        workflow.request(
            requestType = "offload_confirm",
            responseType = "offload_confirm_result",
            payload = payload,
            matches = { it.optString("tagId") == matchedTag && it.optString("barcode") == matchedBarcode },
        ) { result ->
            if (step != Step.CONFIRMING) return@request
            result
                .onSuccess { json ->
                    when {
                        json.optBoolean("accepted", false) -> {
                            val scanned = json.optInt("palletsScanned", -1)
                            val expected = json.optInt("palletsExpected", -1)
                            enterScanStep(clearScan = true)
                            showScanStatus(getString(R.string.msg_offload_recorded), R.color.success)
                            showDonePrompt(document, scanned, expected)
                        }
                        json.optString("errorCode", "") in editStepErrors -> {
                            step = Step.EDIT
                            binding.btnConfirmOffload.isEnabled = true
                            showConfirmStatus(stationReason(json), R.color.danger)
                        }
                        else -> {
                            if (handleSessionRejection(json)) return@request
                            backToScanWithError(stationReason(json))
                        }
                    }
                }
                .onFailure { e ->
                    step = Step.EDIT
                    binding.btnConfirmOffload.isEnabled = true
                    showConfirmStatus(failureText(e), R.color.danger)
                }
        }
    }

    // ---- step 3: "Are you done?" and offload_complete ----------------------------------------

    /**
     * §6.4: after every accepted confirm the operator may close the looked-up document.
     * Custom view: the two choices carry distinct colors (continue = blue, done = green)
     * instead of the theme's identical dialog buttons.
     */
    private fun showDonePrompt(document: OffloadDocument, scanned: Int, expected: Int) {
        val message =
            if (scanned >= 0 && expected >= 0) {
                getString(R.string.dialog_done_message, scanned, expected, document.documentNumber)
            } else {
                getString(R.string.dialog_done_message_no_progress, document.documentNumber)
            }
        val view = com.mitas.ppnam.station1.databinding.DialogOffloadDoneBinding
            .inflate(layoutInflater)
        view.tvDoneMessage.text = message
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
            .setTitle(getString(R.string.dialog_done_title))
            .setView(view.root)
            .show()
        view.btnNextPallet.setOnClickListener { dialog.dismiss() }
        view.btnDoneClose.setOnClickListener {
            dialog.dismiss()
            showClosePrompt(document)
        }
        view.btnNextPallet.applyPressScaleFeedback()
        view.btnDoneClose.applyPressScaleFeedback()
    }

    /** Short = amber, Complete = green, Over = red — the classification is color-coded. */
    private fun showClosePrompt(document: OffloadDocument) {
        val view = com.mitas.ppnam.station1.databinding.DialogOffloadCloseBinding
            .inflate(layoutInflater)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
            .setTitle(getString(R.string.dialog_close_title, document.documentNumber))
            .setView(view.root)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
        val choices = listOf(
            view.btnCloseShort to OffloadStatus.SHORT,
            view.btnCloseComplete to OffloadStatus.COMPLETE,
            view.btnCloseOver to OffloadStatus.OVER,
        )
        for ((button, wireValue) in choices) {
            button.setOnClickListener {
                dialog.dismiss()
                sendCompletion(document, wireValue, button.text.toString())
            }
            button.applyPressScaleFeedback()
        }
    }

    private fun sendCompletion(document: OffloadDocument, status: String, statusLabel: String) {
        step = Step.CLOSING
        updateMatchEnabled()
        showScanStatus(getString(R.string.status_sending), R.color.text_muted)

        val payload = WorkflowMessages.offloadComplete(
            deviceId = DeviceIdentity.deviceId(this),
            operatorSessionId = OperatorSessionHolder.currentSessionIdOrEmpty(),
            documentType = document.documentType,
            documentNumber = document.documentNumber,
            status = status,
        )
        workflow.request(
            requestType = "offload_complete",
            responseType = "offload_complete_result",
            payload = payload,
            matches = { it.optString("status") == status },
        ) { result ->
            if (step != Step.CLOSING) return@request
            step = Step.SCAN
            updateMatchEnabled()
            result
                .onSuccess { json ->
                    if (json.optBoolean("accepted", false)) {
                        showScanStatus(
                            getString(R.string.msg_document_closed, document.documentNumber, statusLabel),
                            R.color.success,
                        )
                    } else {
                        if (handleSessionRejection(json)) return@request
                        showScanStatus(stationReason(json), R.color.danger)
                        // Let the operator retry the closure (or cancel back to scanning).
                        showClosePrompt(document)
                    }
                }
                .onFailure { e ->
                    showScanStatus(failureText(e), R.color.danger)
                    showClosePrompt(document)
                }
        }
    }

    // ---- shared ------------------------------------------------------------------------------

    private fun stationReason(json: JSONObject): String =
        json.optString("reason", "").ifBlank {
            json.optString("errorCode", "").ifBlank { getString(R.string.status_send_failed) }
        }

    /** §8: a closed/expired session sends the operator back to login, not into a dead end. */
    private fun handleSessionRejection(json: JSONObject): Boolean {
        if (!WorkflowClient.isSessionRejection(json)) return false
        OperatorSessionHolder.clear()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
        return true
    }

    private fun failureText(e: Throwable): String =
        if (e is WorkflowTimeout) getString(R.string.status_no_response)
        else getString(R.string.status_send_failed)

    private fun showScanStatus(message: String, colorRes: Int) {
        binding.tvScanStatus.visibility = View.VISIBLE
        binding.tvScanStatus.text = message
        binding.tvScanStatus.setTextColor(getColor(colorRes))
    }

    private fun showConfirmStatus(message: String, colorRes: Int) {
        binding.tvConfirmStatus.visibility = View.VISIBLE
        binding.tvConfirmStatus.text = message
        binding.tvConfirmStatus.setTextColor(getColor(colorRes))
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
