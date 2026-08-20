package ua.com.devinsider.pdfscanner.data.repository

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.core.content.edit

class BillingManager(private val context: Context) : PurchasesUpdatedListener {

    private val prefs: SharedPreferences = context.getSharedPreferences("billing_prefs", Context.MODE_PRIVATE)

    private val _isPro = MutableStateFlow(getCachedProStatus())
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    init {
        Log.d(TAG, "BillingManager init — cached isPro=${_isPro.value}")
        startConnection()
    }

    /**
     * Returns the cached Pro status, but only if the last successful sync
     * was within [SYNC_STALENESS_MS]. Otherwise returns false to prevent
     * stale Pro access after refunds.
     */
    private fun getCachedProStatus(): Boolean {
        val cachedPro = prefs.getBoolean(PREF_IS_PRO, false)
        if (!cachedPro) return false

        val lastSync = prefs.getLong(PREF_LAST_SYNC_TIME, 0L)
        val elapsed = System.currentTimeMillis() - lastSync
        if (elapsed > SYNC_STALENESS_MS) {
            Log.d(TAG, "Cached Pro status is stale (${elapsed}ms since last sync), defaulting to false")
            return false
        }
        return true
    }

    private fun setProStatus(isProStatus: Boolean) {
        Log.d(TAG, "setProStatus: $isProStatus")
        _isPro.value = isProStatus
        prefs.edit {
            putBoolean(PREF_IS_PRO, isProStatus)
            putLong(PREF_LAST_SYNC_TIME, System.currentTimeMillis())
        }
    }

    private fun startConnection() {
        Log.d(TAG, "Starting billing connection...")
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                Log.d(TAG, "Billing setup finished: responseCode=${billingResult.responseCode}")
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryPurchases()
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.d(TAG, "Billing service disconnected, reconnecting...")
                // Try to restart the connection on the next request to Google Play
                startConnection()
            }
        })
    }

    private fun queryPurchases() {
        if (!billingClient.isReady) {
            Log.d(TAG, "queryPurchases: billing client not ready, skipping")
            return
        }

        Log.d(TAG, "queryPurchases: querying INAPP purchases...")
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { billingResult, purchasesList ->
            Log.d(TAG, "queryPurchases result: responseCode=${billingResult.responseCode}, purchaseCount=${purchasesList.size}")
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                // Acknowledge any unacknowledged purchases
                purchasesList.forEach { acknowledgePurchaseIfNeeded(it) }

                val activePurchase = purchasesList.find { purchase ->
                    purchase.products.contains(PREMIUM_SIGN_PRODUCT_ID) &&
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }

                if (activePurchase != null) {
                    val lastServerSync = prefs.getLong(PREF_LAST_SERVER_SYNC_TIME, 0L)
                    val elapsed = System.currentTimeMillis() - lastServerSync
                    
                    if (elapsed > SYNC_STALENESS_MS) {
                        Log.d(TAG, "Server validation needed (elapsed ${elapsed}ms). Validating...")
                        verifyPurchaseWithServer(activePurchase.purchaseToken) { isValid ->
                            if (isValid) {
                                prefs.edit { putLong(PREF_LAST_SERVER_SYNC_TIME, System.currentTimeMillis()) }
                            }
                            setProStatus(isValid)
                        }
                    } else {
                        Log.d(TAG, "Server validation skipped, cache is fresh.")
                        setProStatus(true)
                    }
                } else {
                    setProStatus(false)
                }
            }
        }
    }

    /**
     * Sends the purchase token to your Cloudflare Worker to verify if the purchase is still active.
     */
    private fun verifyPurchaseWithServer(token: String, onResult: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Send request to Cloudflare Worker
                val url = java.net.URL("https://buycheck.i-366.workers.dev/")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                // Send the purchase token as JSON
                val jsonPayload = "{\"purchaseToken\": \"$token\"}"
                connection.outputStream.write(jsonPayload.toByteArray())

                val responseCode = connection.responseCode
                if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val responseString = connection.inputStream.bufferedReader().readText()
                    Log.d(TAG, "Server response: $responseString")
                    // TODO: Parse your actual JSON response here. Assuming simple {"isValid": true}
                    val isValid = responseString.contains("\"isValid\":true") || responseString.contains("\"isValid\": true")
                    CoroutineScope(Dispatchers.Main).launch { onResult(isValid) }
                } else {
                    Log.e(TAG, "Server validation failed with code: $responseCode")
                    // If the server fails (e.g. offline), we might want to temporarily trust local cache 
                    // or revoke. Here we fallback to false (or true, depending on your strictness).
                    CoroutineScope(Dispatchers.Main).launch { onResult(false) }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Server validation exception", e)
                CoroutineScope(Dispatchers.Main).launch { onResult(false) }
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        Log.d(TAG, "onPurchasesUpdated: responseCode=${billingResult.responseCode}, purchaseCount=${purchases?.size}")
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                acknowledgePurchaseIfNeeded(purchase)
            }
        }
        // Re-query to get authoritative Pro status from Google Play
        queryPurchases()
    }

    /**
     * Only handles acknowledgment of a purchase. Does NOT set Pro status —
     * that is exclusively done by [queryPurchases] to ensure refunds are respected.
     */
    private fun acknowledgePurchaseIfNeeded(purchase: Purchase) {
        if (purchase.products.contains(PREMIUM_SIGN_PRODUCT_ID) &&
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
            !purchase.isAcknowledged
        ) {
            Log.d(TAG, "Acknowledging purchase: token=${purchase.purchaseToken}")
            val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                Log.d(TAG, "Acknowledge result: responseCode=${billingResult.responseCode}")
            }
        }
    }

    fun purchasePremium(activity: Activity) {
        if (!billingClient.isReady) {
            Log.d(TAG, "purchasePremium: billing client not ready")
            return
        }

        val queryProductDetailsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PREMIUM_SIGN_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            ).build()

        billingClient.queryProductDetailsAsync(queryProductDetailsParams) { billingResult, productDetailsResult ->
            val productList = productDetailsResult.productDetailsList
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && !productList.isNullOrEmpty()) {
                val productDetails = productList.first()
                val productDetailsParamsList = listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .build()
                )
                val billingFlowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(productDetailsParamsList)
                    .build()
                billingClient.launchBillingFlow(activity, billingFlowParams)
            }
        }
    }

    companion object {
        private const val TAG = "BillingManager"
        const val PREMIUM_SIGN_PRODUCT_ID = "premium_features"
        const val PREF_IS_PRO = "pref_is_pro"
        private const val PREF_LAST_SYNC_TIME = "pref_last_sync_time"
        private const val PREF_LAST_SERVER_SYNC_TIME = "pref_last_server_sync_time"
        private const val SYNC_STALENESS_MS = 24 * 60 * 60 * 1000L // 24 hours
    }
}
