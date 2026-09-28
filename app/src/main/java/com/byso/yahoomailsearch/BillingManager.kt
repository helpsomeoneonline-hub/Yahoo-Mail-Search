package com.byso.yahoomailsearch

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

class BillingManager(
    private val context: Context,
    private val onEntitlementChanged: (() -> Unit)? = null
) {
    private val prefs = context.getSharedPreferences("billing", Context.MODE_PRIVATE)

    private val billing = BillingClient.newBuilder(context)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .enableAutoServiceReconnection()
        .setListener { _, purchases ->
            purchases?.forEach { handlePurchase(it) }
        }
        .build()

    fun isPro(): Boolean =
        BuildConfig.DEBUG || prefs.getBoolean(KEY_PRO, false)

    fun connect(onReady: (() -> Unit)? = null) {
        if (billing.isReady) {
            refreshEntitlement(onReady)
            return
        }
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    refreshEntitlement(onReady)
                } else {
                    onReady?.invoke()
                }
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    fun upgrade(activity: Activity, onMessage: (String) -> Unit) {
        connect {
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_PRO)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(product))
                .build()

            billing.queryProductDetailsAsync(params) { result, details ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    onMessage("Google Play billing is unavailable right now.")
                    return@queryProductDetailsAsync
                }
                val detail = details.productDetailsList.firstOrNull()
                val offer = detail?.subscriptionOfferDetails?.firstOrNull()
                if (detail == null || offer == null) {
                    onMessage(
                        "Pro subscription is not configured in Google Play yet. " +
                            "Create the subscription product '$PRODUCT_PRO' in Play Console."
                    )
                    return@queryProductDetailsAsync
                }

                val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(detail)
                    .setOfferToken(offer.offerToken)
                    .build()

                val flow = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(productParams))
                    .build()

                billing.launchBillingFlow(activity, flow)
            }
        }
    }

    fun restore(onDone: (Boolean) -> Unit) {
        connect {
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            billing.queryPurchasesAsync(params) { _, purchases ->
                purchases.forEach { handlePurchase(it) }
                onDone(isPro())
            }
        }
    }

    private fun refreshEntitlement(onReady: (() -> Unit)? = null) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billing.queryPurchasesAsync(params) { _, purchases ->
            val active = purchases.any {
                it.products.contains(PRODUCT_PRO) &&
                    it.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PURCHASED
            }
            prefs.edit().putBoolean(KEY_PRO, active).apply()
            purchases.forEach { handlePurchase(it) }
            onEntitlementChanged?.invoke()
            onReady?.invoke()
        }
    }

    private fun handlePurchase(purchase: com.android.billingclient.api.Purchase) {
        if (
            purchase.products.contains(PRODUCT_PRO) &&
            purchase.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PURCHASED
        ) {
            prefs.edit().putBoolean(KEY_PRO, true).apply()
            if (!purchase.isAcknowledged) {
                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billing.acknowledgePurchase(params) { }
            }
            onEntitlementChanged?.invoke()
        }
    }

    fun close() {
        if (billing.isReady) billing.endConnection()
    }

    companion object {
        const val PRODUCT_PRO = "mail_search_pro"
        private const val KEY_PRO = "pro_entitlement"
    }
}
