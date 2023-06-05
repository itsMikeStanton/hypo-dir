/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.appevents.iap

import android.content.pm.PackageManager
import android.support.annotation.RestrictTo
import android.util.Log
import com.facebook.appevents.iap.NerveSDK.getApplicationContext
import java.util.concurrent.atomic.AtomicBoolean

//@AutoHandleExceptions
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object InAppPurchaseManager {
  private const val GOOGLE_BILLINGCLIENT_VERSION = "com.google.android.play.billingclient.version"
  private val enabled = AtomicBoolean(false)
  private val TAG = "NERVEIAP_Manager"

  @JvmStatic
  fun enableAutoLogging() {
    enabled.set(true)
    startTracking()
  }

  @JvmStatic
  fun startTracking() {
    Log.d(TAG, "START TRACKING!!!")
    if (enabled.get()) {
      Log.d(TAG, "enabled.get")
      if (usingBillingLib2Plus()) {
        Log.d("NERVEIAP_Manager", "start lib2plus")
        InAppPurchaseAutoLogger.startIapLogging(getApplicationContext())
      } else {
        Log.d(TAG, "no lib2plus")
        InAppPurchaseActivityLifecycleTracker.startIapLogging()
      }
    }
  }

  private fun usingBillingLib2Plus(): Boolean {
    return try {
      val context = getApplicationContext()
      val info =
          context.packageManager.getApplicationInfo(
              context.packageName, PackageManager.GET_META_DATA)
      if (info != null) {
        val version = info.metaData.getString(GOOGLE_BILLINGCLIENT_VERSION)
        val versionArray = if (version === null) return false else version.split(".", limit = 3)
        return versionArray[0].toInt() >= 2
      }
      false
    } catch (e: Exception) {
      false
    }
  }
}
