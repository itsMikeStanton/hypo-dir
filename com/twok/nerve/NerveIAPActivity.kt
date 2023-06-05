package com.twok.nerve

import android.app.Activity
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