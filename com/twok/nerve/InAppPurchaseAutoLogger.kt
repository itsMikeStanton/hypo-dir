/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.twok.nerve

import android.content.Context
import android.support.annotation.RestrictTo

//@AutoHandleExceptions
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object InAppPurchaseAutoLogger {
  private const val BILLING_CLIENT_PURCHASE_NAME = "com.android.billingclient.api.Purchase"

  @JvmStatic
  fun startIapLogging(context: Context) {
    // check if the app has IAP with Billing Lib
    if (InAppPurchaseUtils.getClass(BILLING_CLIENT_PURCHASE_NAME) == null) {
      return
    }
    val billingClientWrapper =
        InAppPurchaseBillingClientWrapper.getOrCreateInstance(context) ?: return
    if (InAppPurchaseBillingClientWrapper.isServiceConnected.get()) {
      if (InAppPurchaseLoggerManager.eligibleQueryPurchaseHistory()) {
        billingClientWrapper.queryPurchaseHistory("inapp",
        Runnable {
          logPurchase()
        })
      } else {
        billingClientWrapper.queryPurchase("inapp",
        Runnable {
          logPurchase()
        })
      }
    }
  }

  private fun logPurchase() {
    InAppPurchaseLoggerManager.filterPurchaseLogging(
        InAppPurchaseBillingClientWrapper.purchaseDetailsMap,
        InAppPurchaseBillingClientWrapper.skuDetailsMap)
    InAppPurchaseBillingClientWrapper.purchaseDetailsMap.clear()
  }
}
