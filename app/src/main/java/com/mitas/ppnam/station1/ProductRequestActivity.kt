package com.mitas.ppnam.station1

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mitas.ppnam.station1.databinding.ActivityProductRequestBinding
import com.mitas.ppnam.station1.databinding.ItemSapProductSelectableBinding
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant

class ProductRequestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProductRequestBinding
    private var scannerInt = 1
    private var stationInt = 1
    private val productAdapter = ProductAdapter()
    private var submittedProductList: ArrayList<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProductRequestBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)

        loadSettings()
        setupToolbar()
        setupRecyclerView()
        
        subscribeToResults()

        binding.btnFetchProducts.setOnClickListener { fetchProducts() }
        binding.btnSubmitRequest.setOnClickListener { submitSelectedProducts() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val incomingDocNum = intent.getStringExtra("doc_number")
        val incomingDocType = intent.getStringExtra("doc_type")
        if (!incomingDocNum.isNullOrEmpty()) {
            binding.etDocNumber.setText(incomingDocNum)
            fetchProducts()
        }
        if (!incomingDocType.isNullOrEmpty()) {
            binding.spinnerDocType.setText(incomingDocType, false)
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        scannerInt = prefs.getInt("scanner_int", 1)
        stationInt = prefs.getInt("station_int", 1)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.title_product_request)
    }

    private fun setupRecyclerView() {
        binding.rvProducts.layoutManager = LinearLayoutManager(this)
        binding.rvProducts.adapter = productAdapter
    }

    private fun subscribeToResults() {
        val mqtt = MqttManager.getInstance(this)
        val productsResponseTopic = "PPNAM/station_$stationInt/sap_products_response"
        val selectedResultTopic = "PPNAM/station_$stationInt/sap_products_selected_result"

        mqtt.subscribe(productsResponseTopic) { publish ->
            val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
            handleProductsResponse(payload)
        }

        mqtt.subscribe(selectedResultTopic) { publish ->
            val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
            handleSelectedResult(payload)
        }
    }

    private fun handleSelectedResult(payload: String) {
        try {
            val json = JSONObject(payload)
            val status = json.optString("status", "Unknown")
            val message = json.optString("message", "")

            runOnUiThread {
                if (status.equals("Success", ignoreCase = true)) {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    
                    getSharedPreferences("sap_data", Context.MODE_PRIVATE).edit()
                        .putInt("current_step", 2) // Step 2: Request complete, enable Tag Assignment
                        .apply()

                    val docNum = binding.etDocNumber.text?.toString()?.trim().orEmpty()
                    val docType = intent.getStringExtra("doc_type") ?: ""

                    val intent = Intent(this, TagAssignmentActivity::class.java).apply {
                        putStringArrayListExtra("selected_products", submittedProductList)
                        putExtra("doc_number", docNum)
                        putExtra("doc_type", docType)
                    }
                    startActivity(intent)
                    finish()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Submission Failed")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                    
                    binding.btnSubmitRequest.isEnabled = true
                    binding.btnFetchProducts.isEnabled = true
                    productAdapter.setInteractionEnabled(true)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getSessionId(): String {
        return getSharedPreferences("sap_data", Context.MODE_PRIVATE)
            .getString("session_id", "") ?: ""
    }

    private fun fetchProducts() {
        val docNum = binding.etDocNumber.text?.toString()?.trim().orEmpty()
        val docType = intent.getStringExtra("doc_type") ?: ""

        if (docNum.isEmpty()) {
            Toast.makeText(this, "Source Document Number is required", Toast.LENGTH_SHORT).show()
            return
        }

        val payload = JSONObject().apply {
            put("ts", Instant.now().toString())
            put("deviceId", "scanner_$scannerInt")
            put("sessionId", getSessionId())
            put("sourceDocumentType", docType)
            put("sourceDocumentNumber", docNum)
        }

        val topic = "PPNAM/scanner_$scannerInt/sap_products_request"
        
        binding.btnFetchProducts.isEnabled = false
        MqttManager.getInstance(this).publish(topic, payload.toString()) { throwable ->
            runOnUiThread {
                if (throwable != null) {
                    Toast.makeText(this, "Request Failed", Toast.LENGTH_SHORT).show()
                    binding.btnFetchProducts.isEnabled = true
                } else {
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvStatus.text = "Fetching products from SAP..."
                    binding.cardProductList.visibility = View.GONE
                }
            }
        }
    }

    private fun handleProductsResponse(payload: String) {
        try {
            val json = JSONObject(payload)
            val status = json.optString("status", "")
            val message = json.optString("message", "")
            val respSessionId = json.optString("sessionId", "")
            val respDocNum = json.optString("sourceDocumentNumber", "")
            val respDocType = json.optString("sourceDocumentType", "")

            // Correlation check: session ID or DocType+DocNum
            val activeSessionId = getSessionId()
            val currentDocNum = binding.etDocNumber.text?.toString()?.trim().orEmpty()
            val currentDocType = intent.getStringExtra("doc_type") ?: ""

            val sessionMatch = activeSessionId.isNotEmpty() && activeSessionId == respSessionId
            val docMatch = respDocNum == currentDocNum && respDocType == currentDocType

            if (!sessionMatch && !docMatch) {
                Log.d("ProductRequest", "Ignoring stale/mismatched products response")
                return
            }

            if (status.equals("Failed", ignoreCase = true)) {
                runOnUiThread {
                    binding.tvStatus.text = message
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    binding.btnFetchProducts.isEnabled = true
                }
                return
            }

            val productsArray = json.optJSONArray("products") ?: JSONArray()
            val productList = mutableListOf<ProductItem>()

            for (i in 0 until productsArray.length()) {
                val p = productsArray.getJSONObject(i)
                productList.add(ProductItem(
                    p.getString("productCode"),
                    p.getString("productDescription")
                ))
            }

            runOnUiThread {
                binding.btnFetchProducts.isEnabled = true
                binding.tvStatus.visibility = View.GONE
                if (productList.isNotEmpty()) {
                    binding.cardProductList.visibility = View.VISIBLE
                    productAdapter.submitList(productList) // Authoritative replacement
                } else {
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvStatus.text = "No products found."
                    productAdapter.submitList(emptyList())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun submitSelectedProducts() {
        val selectedItems = productAdapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            Toast.makeText(this, "Please select at least one product", Toast.LENGTH_SHORT).show()
            return
        }

        val docNum = binding.etDocNumber.text?.toString()?.trim().orEmpty()
        val docType = intent.getStringExtra("doc_type") ?: ""

        submittedProductList = ArrayList(selectedItems.map { "${it.code} - ${it.description}" })

        val payload = JSONObject().apply {
            put("ts", Instant.now().toString())
            put("deviceId", "scanner_$scannerInt")
            put("sessionId", getSessionId())
            put("sourceDocumentType", docType)
            put("sourceDocumentNumber", docNum)

            val codesArray = JSONArray()
            selectedItems.forEach { item ->
                codesArray.put(item.code)
            }
            put("selectedProductCodes", codesArray)
        }

        val topic = "PPNAM/scanner_$scannerInt/sap_products_selected"

        binding.btnSubmitRequest.isEnabled = false
        binding.btnFetchProducts.isEnabled = false
        productAdapter.setInteractionEnabled(false)

        MqttManager.getInstance(this).publish(topic, payload.toString()) { throwable ->
            runOnUiThread {
                if (throwable != null) {
                    Toast.makeText(this, "Submission Failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                    binding.btnSubmitRequest.isEnabled = true
                    binding.btnFetchProducts.isEnabled = true
                    productAdapter.setInteractionEnabled(true)
                } else {
                    Toast.makeText(this, "Waiting for confirmation...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        val mqtt = MqttManager.getInstance(this)
        mqtt.unsubscribe("PPNAM/station_$stationInt/sap_products_response")
        mqtt.unsubscribe("PPNAM/station_$stationInt/sap_products_selected_result")
    }

    data class ProductItem(val code: String, val description: String, var isSelected: Boolean = false)

    class ProductAdapter : RecyclerView.Adapter<ProductAdapter.ViewHolder>() {
        private var items = listOf<ProductItem>()
        private var interactionEnabled = true

        fun submitList(newList: List<ProductItem>) {
            items = newList
            notifyDataSetChanged()
        }

        fun getSelectedItems(): List<ProductItem> {
            return items.filter { it.isSelected }
        }
        
        fun setInteractionEnabled(enabled: Boolean) {
            interactionEnabled = enabled
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemSapProductSelectableBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.binding.tvProductCode.text = item.code
            holder.binding.tvProductDescription.text = item.description
            
            holder.binding.cbSelect.setOnCheckedChangeListener(null)
            holder.binding.cbSelect.isChecked = item.isSelected
            holder.binding.cbSelect.isEnabled = interactionEnabled
            
            holder.binding.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                item.isSelected = isChecked
            }

            holder.binding.root.setOnClickListener {
                if (interactionEnabled) {
                    holder.binding.cbSelect.toggle()
                }
            }
            holder.binding.root.isClickable = interactionEnabled
            holder.binding.root.isFocusable = interactionEnabled
        }

        override fun getItemCount() = items.size

        class ViewHolder(val binding: ItemSapProductSelectableBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
