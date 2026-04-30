package com.sysone.scanner

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.sysone.scanner.databinding.ActivityProductRequestBinding
import com.sysone.scanner.databinding.ItemSapProductSelectableBinding
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

class ProductRequestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProductRequestBinding
    private var scannerInt = 1
    private var mqtt: Mqtt3AsyncClient? = null
    private val productAdapter = ProductAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProductRequestBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)

        loadSettings()
        setupToolbar()
        setupRecyclerView()
        initMqtt()

        binding.btnFetchProducts.setOnClickListener { fetchProducts() }
        binding.btnSubmitRequest.setOnClickListener { submitSelectedProducts() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Handle data from ManualSapEntryActivity
        val incomingDocNum = intent.getStringExtra("doc_number")
        val incomingDocType = intent.getStringExtra("doc_type")
        if (!incomingDocNum.isNullOrEmpty()) {
            binding.etDocNumber.setText(incomingDocNum)
        }
        if (!incomingDocType.isNullOrEmpty()) {
            binding.spinnerDocType.setText(incomingDocType, false)
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        scannerInt = prefs.getInt("scanner_int", 1)
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

    private fun initMqtt() {
        mqtt = MqttClient.builder()
            .useMqttVersion3()
            .identifier("PROD_REQ_" + UUID.randomUUID().toString().take(8))
            .serverHost("mqtt.sysone.co.za")
            .serverPort(443)
            .sslWithDefaultConfig()
            .webSocketWithDefaultConfig()
            .buildAsync()

        mqtt?.connectWith()
            ?.simpleAuth()
                ?.username("admin")
                ?.password("admin".toByteArray())
                ?.applySimpleAuth()
            ?.send()
            ?.whenComplete { _, throwable ->
                if (throwable == null) {
                    subscribeToResults()

                    // Auto-fetch if we came from SAP Lookup
                    val docNum = intent.getStringExtra("doc_number")
                    if (!docNum.isNullOrEmpty()) {
                        runOnUiThread { fetchProducts() }
                    }
                }
            }
    }

    private fun subscribeToResults() {
        val productsResponseTopic = "PPNAM/scanner_$scannerInt/sap_products_response"

        mqtt?.subscribeWith()
            ?.topicFilter(productsResponseTopic)
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.callback { publish ->
                val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
                handleProductsResponse(payload)
            }
            ?.send()
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

        if (mqtt?.state != MqttClientState.CONNECTED) {
            Toast.makeText(this, "MQTT Not Connected", Toast.LENGTH_SHORT).show()
            return
        }

        mqtt?.publishWith()
            ?.topic(topic)
            ?.payload(payload.toString().toByteArray(StandardCharsets.UTF_8))
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.send()
            ?.whenComplete { _, throwable ->
                runOnUiThread {
                    if (throwable != null) {
                        Toast.makeText(this, "Request Failed", Toast.LENGTH_SHORT).show()
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
                binding.tvStatus.visibility = View.GONE
                if (productList.isNotEmpty()) {
                    binding.cardProductList.visibility = View.VISIBLE
                    productAdapter.submitList(productList)
                } else {
                    Toast.makeText(this, "No products found for this document", Toast.LENGTH_LONG).show()
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

        if (mqtt?.state != MqttClientState.CONNECTED) {
            Toast.makeText(this, "MQTT Not Connected", Toast.LENGTH_SHORT).show()
            return
        }

        mqtt?.publishWith()
            ?.topic(topic)
            ?.payload(payload.toString().toByteArray(StandardCharsets.UTF_8))
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.send()
            ?.whenComplete { _, throwable ->
                runOnUiThread {
                    if (throwable != null) {
                        Toast.makeText(this, "Submission Failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Product Request Submitted", Toast.LENGTH_SHORT).show()

                        val selectedProductsList = ArrayList(selectedItems.map { "${it.code} - ${it.description}" })
                        val intent = Intent(this, TagAssignmentActivity::class.java).apply {
                            putStringArrayListExtra("selected_products", selectedProductsList)
                            putExtra("doc_number", docNum)
                            putExtra("doc_type", docType)
                        }
                        startActivity(intent)
                        finish()
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
        mqtt?.disconnect()
    }

    data class ProductItem(val code: String, val description: String, var isSelected: Boolean = false)

    class ProductAdapter : RecyclerView.Adapter<ProductAdapter.ViewHolder>() {
        private var items = listOf<ProductItem>()

        fun submitList(newList: List<ProductItem>) {
            items = newList
            notifyDataSetChanged()
        }

        fun getSelectedItems(): List<ProductItem> {
            return items.filter { it.isSelected }
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
            holder.binding.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                item.isSelected = isChecked
            }
        }

        override fun getItemCount() = items.size

        class ViewHolder(val binding: ItemSapProductSelectableBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
