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
import android.os.Bundle
import android.support.annotation.RestrictTo
import android.util.Log

import com.twok.nerve.InAppPurchaseManager
import com.twok.nerve.NerveSDK.getActivityName
import com.twok.nerve.SessionInfo
import com.twok.nerve.Constants.getDefaultAppEventsSessionTimeoutInSeconds
import com.twok.nerve.SessionInfo.Companion.clearSavedSessionFromDisk
import com.twok.nerve.SessionInfo.Companion.getStoredSessionInfo


import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object ActivityLifecycleTracker {
  private val TAG: String = "NERVEIAP"
  private const val INCORRECT_IMPL_WARNING =
      "Unexpected activity pause without a " +
          "matching activity resume. Logging data may be incorrect. Make sure you call " +
          "activateApp from your Application's onCreate method"
  private const val INTERRUPTION_THRESHOLD_MILLISECONDS: Long = 1000
  private val singleThreadExecutor = Executors.newSingleThreadScheduledExecutor()

  @Volatile private var currentFuture: ScheduledFuture<*>? = null
  private val currentFutureLock = Any()
  private val foregroundActivityCount = AtomicInteger(0)

  // This member should only be changed or updated when executing on the singleThreadExecutor.
  @Volatile private var currentSession: SessionInfo? = null
  private val tracking = AtomicBoolean(false)
  private var appId: String? = null
  private var currentActivityAppearTime: Long = 0
  private var activityReferences = 0
  private var currActivity: WeakReference<Activity>? = null

  @JvmStatic
  fun startTracking(application: Application, appId: String?) {
    if (!tracking.compareAndSet(false, true)) {
      Log.d(TAG, "start tracking fail")
      return
    }
    /*checkFeature(FeatureManager.Feature.CodelessEvents) { enabled ->
      if (enabled) {
        CodelessManager.enable()
      } else {
        CodelessManager.disable()
      }
    }*/

    Log.d("NERVEIAP", "started tracking")
    ActivityLifecycleTracker.appId = appId
    application.registerActivityLifecycleCallbacks(
        object : Application.ActivityLifecycleCallbacks {
          override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            Log.d(TAG, activity.localClassName+".onActivityCreated")
            onActivityCreated(activity)
          }

          override fun onActivityStarted(activity: Activity) {
            activityReferences++
            Log.d(TAG, activity.localClassName+".onActivityStarted")
          }

          override fun onActivityResumed(activity: Activity) {
            Log.d(TAG, activity.localClassName+".onActivityResumed")
            ActivityLifecycleTracker.onActivityResumed(activity)
          }

          override fun onActivityPaused(activity: Activity) {
            Log.d(TAG, activity.localClassName+".onActivityPaused")
            ActivityLifecycleTracker.onActivityPaused(activity)
          }

          override fun onActivityStopped(activity: Activity) {
            Log.d(TAG, activity.localClassName+".onActivityStopped")
            //AppEventsLogger.onContextStop() TODO important?
            activityReferences--
          }

          override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
            Log.d(TAG, activity.localClassName+".onActivitySaveInstanceState")
          }

          override fun onActivityDestroyed(activity: Activity) {
            Log.d(TAG, activity.localClassName+".onActivityDestroyed")
            ActivityLifecycleTracker.onActivityDestroyed(activity)
          }
        })
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  @JvmStatic
  fun isInBackground(): Boolean {
    return 0 == activityReferences
  }

  @JvmStatic
  fun isTracking(): Boolean {
    return tracking.get()
  }

  @JvmStatic
  fun getCurrentSessionGuid(): UUID? {
    return if (currentSession != null) currentSession?.sessionId else null
  }

  // Public in order to allow unity sdk to correctly log app events
  @JvmStatic
  fun onActivityCreated(activity: Activity?) {
    singleThreadExecutor.execute {
      if (currentSession == null) {
        currentSession = getStoredSessionInfo()
      }
    }
  }

  // Public in order to allow unity sdk to correctly log app events
  @JvmStatic
  fun onActivityResumed(activity: Activity) {
    currActivity = WeakReference(activity)
    foregroundActivityCount.incrementAndGet()
    cancelCurrentTask()
    val currentTime = System.currentTimeMillis()
    currentActivityAppearTime = currentTime
    val activityName = getActivityName(activity)
    //CodelessManager.onActivityResumed(activity)
    //MetadataIndexer.onActivityResumed(activity)
    //SuggestedEventsManager.trackActivity(activity)
    InAppPurchaseManager.startTracking()
    val appContext = activity.applicationContext
    val handleActivityResume = Runnable {
      val lastEventTime = currentSession?.sessionLastEventTime
      if (currentSession == null) {
        currentSession = SessionInfo(currentTime, null)
        //logActivateApp(activityName, null, appId, appContext) TODO
      } else if (lastEventTime != null) {
        val suspendTime = currentTime - lastEventTime
        if (suspendTime > sessionTimeoutInSeconds * 1000) {
          // We were suspended for a significant amount of time.
          // Count this as a new session and log the old session
          //logDeactivateApp(activityName, currentSession, appId) TODO
          //logActivateApp(activityName, null, appId, appContext) TODO
          currentSession = SessionInfo(currentTime, null)
        } else if (suspendTime > INTERRUPTION_THRESHOLD_MILLISECONDS) {
          currentSession?.incrementInterruptionCount()
        }
      }
      currentSession?.sessionLastEventTime = currentTime
      currentSession?.writeSessionToDisk()
    }
    singleThreadExecutor.execute(handleActivityResume)
  }

  private fun onActivityPaused(activity: Activity) {
    val count = foregroundActivityCount.decrementAndGet()
    if (count < 0) {
      // Our ref count can be off if a developer doesn't call activate
      // app from the Application's onCreate method.
      foregroundActivityCount.set(0)
      Log.w(TAG, INCORRECT_IMPL_WARNING)
    }
    cancelCurrentTask()
    val currentTime = System.currentTimeMillis()
    val activityName = getActivityName(activity)
    //CodelessManager.onActivityPaused(activity)
    val handleActivityPaused = Runnable {
      if (currentSession == null) {
        // This can happen if a developer doesn't call activate
        // app from the Application's onCreate method
        currentSession = SessionInfo(currentTime, null)
      }
      currentSession?.sessionLastEventTime = currentTime
      if (foregroundActivityCount.get() <= 0) {
        // Schedule check to see if we still have 0 foreground
        // activities in our set time. This indicates that the app has
        // been backgrounded
        val task = Runnable {
          if (currentSession == null) {
            currentSession = SessionInfo(currentTime, null)
          }
          if (foregroundActivityCount.get() <= 0) {
            //logDeactivateApp(activityName, currentSession, appId) TODO
            clearSavedSessionFromDisk()
            currentSession = null
          }
          synchronized(currentFutureLock) { currentFuture = null }
        }
        synchronized(currentFutureLock) {
          currentFuture =
              singleThreadExecutor.schedule(
                  task, sessionTimeoutInSeconds.toLong(), TimeUnit.SECONDS)
        }
      }
      val appearTime = currentActivityAppearTime
      val timeSpentOnActivityInSeconds =
          if (appearTime > 0) (currentTime - appearTime) / 1000 else 0
      //AutomaticAnalyticsLogger.logActivityTimeSpentEvent(activityName, timeSpentOnActivityInSeconds) TODO
      currentSession?.writeSessionToDisk()
    }
    singleThreadExecutor.execute(handleActivityPaused)
  }

  private fun onActivityDestroyed(activity: Activity) {
    //CodelessManager.onActivityDestroyed(activity)
  }

  private val sessionTimeoutInSeconds: Int
    private get() {
      return getDefaultAppEventsSessionTimeoutInSeconds()
    }

  private fun cancelCurrentTask() {
    synchronized(currentFutureLock) {
      if (currentFuture != null) {
        currentFuture?.cancel(false)
      }
      currentFuture = null
    }
  }

  @JvmStatic
  fun getCurrentActivity(): Activity? {
    return if (currActivity != null) currActivity?.get() else null
  }
}
