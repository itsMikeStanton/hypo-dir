/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.twok.nerve

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log

import com.twok.nerve.NerveSDK.getApplicationContext
//import com.facebook.appevents.internal.AutomaticAnalyticsLogger.isImplicitPurchaseLoggingEnabled
//import com.facebook.appevents.internal.AutomaticAnalyticsLogger.logPurchase
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONException
import org.json.JSONObject

object InAppPurchaseActivityLifecycleTracker {
  private val TAG = "NERVEIAP_cycleTracker"
  private const val SERVICE_INTERFACE_NAME =
      "com.android.vending.billing.IInAppBillingService\$Stub"
  private const val BILLING_ACTIVITY_NAME = "com.android.billingclient.api.ProxyBillingActivity"
  private val isTracking = AtomicBoolean(false)
  private var hasBillingService: Boolean? = null
  private var hasBillingActivity: Boolean? = null
  private lateinit var serviceConnection: ServiceConnection
  private lateinit var callbacks: Application.ActivityLifecycleCallbacks
  private lateinit var intent: Intent
  private var inAppBillingObj: Any? = null

  /** Start iap logging if enable, initialize billing service if not */
  @JvmStatic
  fun startIapLogging() {
    initializeIfNotInitialized()
    if (hasBillingService == false) {
      Log.d(TAG, "no billing service for startIap, return")
      return
    }
      startTracking()
  }

  private fun initializeIfNotInitialized() {
    if (hasBillingService != null) {
      Log.d(TAG, "billing service != null")
      return
    }

    hasBillingService = InAppPurchaseUtils.getClass(SERVICE_INTERFACE_NAME) != null
    if (hasBillingService == false) {
      Log.d(TAG, "failed to get service interface name")
      return
    }

    hasBillingActivity = InAppPurchaseUtils.getClass(BILLING_ACTIVITY_NAME) != null

    InAppPurchaseEventManager.clearSkuDetailsCache()

    intent =
        Intent("com.android.vending.billing.InAppBillingService.BIND")
            .setPackage("com.android.vending")
    serviceConnection =
        object : ServiceConnection {
          override fun onServiceConnected(name: ComponentName, service: IBinder) {
            inAppBillingObj =
              InAppPurchaseEventManager.asInterface(getApplicationContext(), service)
          }

          override fun onServiceDisconnected(name: ComponentName) = Unit
        }
    callbacks =
        object : Application.ActivityLifecycleCallbacks {
          override fun onActivityResumed(activity: Activity) {
            Log.d(TAG, "RESUMED")
            try {
              //getExecutor().execute {
                val context = getApplicationContext()
                val purchasesInapp =
                  InAppPurchaseEventManager.getPurchasesInapp(context, inAppBillingObj)
                logPurchase(context, purchasesInapp, false)
                val purchasesSubs =
                  InAppPurchaseEventManager.getPurchasesSubs(context, inAppBillingObj)
                logPurchase(context, purchasesSubs, true)
              //}
            } catch (ep: Exception) {
              /*no op*/
            }
          }

          override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
          override fun onActivityStarted(activity: Activity) = Unit
          override fun onActivityPaused(activity: Activity) = Unit
          override fun onActivityStopped(activity: Activity) {
            try {
              if (hasBillingActivity == true && activity.localClassName == BILLING_ACTIVITY_NAME) {
                //getExecutor().execute {
                  val context = getApplicationContext()
                  var purchases =
                    InAppPurchaseEventManager.getPurchasesInapp(context, inAppBillingObj)
                  if (purchases.isEmpty()) {
                    purchases =
                      InAppPurchaseEventManager.getPurchaseHistoryInapp(context, inAppBillingObj)
                  }
                  logPurchase(context, purchases, false)
                //}
              }
            } catch (ep: Exception) {
              /*no op*/
            }
          }

          override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
          override fun onActivityDestroyed(activity: Activity) = Unit
        }
  }

  private fun startTracking() {
    if (!isTracking.compareAndSet(false, true)) {
      return
    }
    val context = getApplicationContext()
    if (context is Application) {
      context.registerActivityLifecycleCallbacks(callbacks)
      context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
  }

  private fun logPurchase(context: Context, purchases: ArrayList<String>, isSubscription: Boolean) {
    if (purchases.isEmpty()) {
      return
    }
    val purchaseMap = hashMapOf<String, String>()
    val skuList = arrayListOf<String>()
    for (purchase in purchases) {
      try {
        val purchaseJson = JSONObject(purchase)
        val sku = purchaseJson.getString("productId")
        purchaseMap[sku] = purchase
        skuList.add(sku)
      } catch (e: JSONException) {
        Log.e(TAG, "Error parsing in-app purchase data.", e)
      }
    }
    val skuDetailsMap =
      InAppPurchaseEventManager.getSkuDetails(context, skuList, inAppBillingObj, isSubscription)
    for ((key, value) in skuDetailsMap) {
      Log.d(TAG, "DO SOME LOGGERS")
      //purchaseMap[key]?.let { logPurchase(it, value, isSubscription) }
    }
  }
}
