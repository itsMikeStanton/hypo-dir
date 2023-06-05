package com.facebook.appevents.iap

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log

open class NerveIAPActivity : Activity() {

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        Log.d("NERVEIAPACTIVIVTY", "NERVEY NERVE - "+applicationContext)
        NerveSDK.init (applicationContext);
    }

}