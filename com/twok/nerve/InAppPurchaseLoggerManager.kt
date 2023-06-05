/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.twok.nerve

import android.content.Context
import android.content.SharedPreferences
import android.support.annotation.RestrictTo
import android.support.annotation.VisibleForTesting
import android.util.Log

import com.twok.nerve.NerveSDK.getApplicationContext

import java.lang.Exception
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import org.json.JSONObject

import com.leanplum.Leanplum
// For tracking user sessions.


// For tracking user sessions.
// For tracking user sessions.


//@AutoHandleExceptions
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object InAppPurchaseLoggerManager {
  private lateinit var sharedPreferences: SharedPreferences
  private val cachedPurchaseSet: MutableSet<String> = CopyOnWriteArraySet()
  private val cachedPurchaseMap: MutableMap<String, Long> = ConcurrentHashMap()
  private const val PURCHASE_TIME = "purchaseTime"
  private const val PRODUCT_DETAILS_STORE = "com.facebook.internal.iap.PRODUCT_DETAILS"
  private const val LAST_CLEARED_TIME = "LAST_CLEARED_TIME"
  private const val PURCHASE_DETAILS_SET = "PURCHASE_DETAILS_SET"
  private const val LAST_QUERY_PURCHASE_HISTORY_TIME = "LAST_QUERY_PURCHASE_HISTORY_TIME"
  private const val CACHE_CLEAR_TIME_LIMIT_SEC = 7 * 24 * 60 * 60 // 7 days
  private const val PURCHASE_IN_CACHE_INTERVAL = 24 * 60 * 60 // 1 day

  private const val TAG = "NERVEIAP_LoggerManager"
  private fun readPurchaseCache() {
    // clear cached purchases logged by lib 1
    val cachedSkuSharedPref =
        getApplicationContext()
            .getSharedPreferences("com.facebook.internal.SKU_DETAILS", Context.MODE_PRIVATE)
    val cachedPurchaseSharedPref =
        getApplicationContext()
            .getSharedPreferences("com.facebook.internal.PURCHASE", Context.MODE_PRIVATE)
    if (cachedSkuSharedPref.contains("LAST_CLEARED_TIME")) {
      cachedSkuSharedPref.edit().clear().apply()
      cachedPurchaseSharedPref.edit().clear().apply()
    }
    sharedPreferences =
        getApplicationContext().getSharedPreferences(PRODUCT_DETAILS_STORE, Context.MODE_PRIVATE)
    cachedPurchaseSet.addAll(
        sharedPreferences.getStringSet(PURCHASE_DETAILS_SET, hashSetOf()) ?: hashSetOf())

    // Construct purchase de-dup map.
    for (purchaseHistory in cachedPurchaseSet) {
      val splitPurchase = purchaseHistory.split(";", limit = 2)
      cachedPurchaseMap[splitPurchase[0]] = splitPurchase[1].toLong()
    }

    // Clean up cache every 7 days, and only keep recent 1 day purchases
    clearOutdatedProductInfoInCache()
  }

  @JvmStatic
  fun filterPurchaseLogging(
      purchaseDetailsMap: MutableMap<String, JSONObject>,
      skuDetailsMap: Map<String, JSONObject?>
  ) {
    readPurchaseCache()
    val loggingReadyMap: Map<String, String> =
        constructLoggingReadyMap(cacheDeDupPurchase(purchaseDetailsMap), skuDetailsMap)
    logPurchases(loggingReadyMap)
  }

  private fun logPurchases(purchaseDetailsMap: Map<String, String>) {
    for ((purchaseDetails, skuDetails) in purchaseDetailsMap) {
      if (purchaseDetails != null && skuDetails != null) {
        //logPurchase(purchaseDetails, skuDetails, false)
        Log.d("NERVEIAP", "PURCHASE LOGGED!!!")

        val amp = com.amplitude.api.Amplitude.getInstance();

        val eventProperties = JSONObject(skuDetails)
        /*try {
          eventProperties.put("PurchasePrice", skuDetails.getPrice())
        } catch (e: JSONException) {
          Log.d(TAG, "Invalid JSON")
        }*/
        amp.logEvent("PurchaseDidTransact", eventProperties);
        Leanplum.track ("PurchaseDidTransact");

        Log.d("NERVEIAP", purchaseDetails)
        Log.d("NERVEIAP", skuDetails)
      }
    }
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  internal fun cacheDeDupPurchase(
      purchaseDetailsMap: MutableMap<String, JSONObject>
  ): Map<String, JSONObject> {
    val nowSec = System.currentTimeMillis() / 1000L
    val tempPurchaseDetailsMap: Map<String, JSONObject> = purchaseDetailsMap.toMap()
    for ((key, purchaseJson) in tempPurchaseDetailsMap) {
      try {
        if (purchaseJson.has("purchaseToken")) {
          val purchaseToken = purchaseJson.getString("purchaseToken")
          if (cachedPurchaseMap.containsKey(purchaseToken)) {
            purchaseDetailsMap.remove(key)
          } else {
            cachedPurchaseSet.add("$purchaseToken;$nowSec")
          }
        }
      } catch (e: Exception) {
        /* swallow */
      }
    }
    sharedPreferences.edit().putStringSet(PURCHASE_DETAILS_SET, cachedPurchaseSet).apply()
    return HashMap(purchaseDetailsMap)
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  internal fun clearOutdatedProductInfoInCache() {
    val nowSec = System.currentTimeMillis() / 1000L
    val lastClearedTimeSec = sharedPreferences.getLong(LAST_CLEARED_TIME, 0)
    if (lastClearedTimeSec == 0L) {
      sharedPreferences.edit().putLong(LAST_CLEARED_TIME, nowSec).apply()
    } else if (nowSec - lastClearedTimeSec > CACHE_CLEAR_TIME_LIMIT_SEC) {
      val tempPurchaseMap: Map<String, Long> = cachedPurchaseMap.toMap()
      for ((purchaseToken, historyPurchaseTime) in tempPurchaseMap) {
        if (nowSec - historyPurchaseTime > PURCHASE_IN_CACHE_INTERVAL) {
          cachedPurchaseSet.remove("$purchaseToken;$historyPurchaseTime")
          cachedPurchaseMap.remove(purchaseToken)
        }
      }
      sharedPreferences
          .edit()
          .putStringSet(PURCHASE_DETAILS_SET, cachedPurchaseSet)
          .putLong(LAST_CLEARED_TIME, nowSec)
          .apply()
    }
  }

  @JvmStatic
  fun eligibleQueryPurchaseHistory(): Boolean {
    readPurchaseCache()
    val nowSec = System.currentTimeMillis() / 1000L
    val lastQueryPurchaseHistoryTime =
        sharedPreferences.getLong(LAST_QUERY_PURCHASE_HISTORY_TIME, 0)
    if (lastQueryPurchaseHistoryTime != 0L &&
        nowSec - lastQueryPurchaseHistoryTime < PURCHASE_IN_CACHE_INTERVAL) {
      return false
    }
    sharedPreferences.edit().putLong(LAST_QUERY_PURCHASE_HISTORY_TIME, nowSec).apply()
    return true
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  internal fun constructLoggingReadyMap(
      purchaseDetailsMap: Map<String, JSONObject>,
      skuDetailsMap: Map<String, JSONObject?>
  ): Map<String, String> {
    val nowSec = System.currentTimeMillis() / 1000L
    val purchaseResultMap: MutableMap<String, String> = mutableMapOf()
    for ((key, purchaseDetail) in purchaseDetailsMap) {
      val skuDetail = skuDetailsMap[key]
      if (purchaseDetail != null && purchaseDetail.has(PURCHASE_TIME)) {
        try {
          val purchaseTime = purchaseDetail.getLong(PURCHASE_TIME)
          // Purchase is too old (more than 24h) to log
          if (nowSec - purchaseTime / 1000L > PURCHASE_IN_CACHE_INTERVAL) {
            continue
          }
          if (skuDetail != null) {
            purchaseResultMap[purchaseDetail.toString()] = skuDetail.toString()
          }
        } catch (e: Exception) {
          /* swallow */
        }
      }
    }
    return purchaseResultMap
  }
}
