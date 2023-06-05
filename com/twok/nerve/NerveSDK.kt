package com.twok.nerve

import android.app.Application
import android.content.Context
import android.util.Log
import com.twok.nerve.ActivityLifecycleTracker.startTracking


object NerveSDK {

    private lateinit var applicationContext: Context

    @JvmStatic
    fun init(applicationContext: Context) {
        Log.d("NERVEIAP", "SDK INIT!!!")
        NerveSDK.applicationContext = applicationContext.applicationContext;
        startTracking(NerveSDK.applicationContext as Application, "applicationId")
        InAppPurchaseManager.enableAutoLogging()
    }

    @JvmStatic
    fun getApplicationContext(): Context {
        return applicationContext
    }

    @JvmStatic
    fun getActivityName(context: Context?): String {
        return if (context == null) {
            "null"
        } else if (context === context.applicationContext) {
            "unknown"
        } else {
            context.javaClass.simpleName
        }
    }

}