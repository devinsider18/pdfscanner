package ua.com.devinsider.pdfscanner.utils

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

object AnalyticsHelper {

    fun logEvent(context: Context, eventName: String, params: Bundle? = null) {
        try {
            FirebaseAnalytics.getInstance(context).logEvent(eventName, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun logScreenView(context: Context, screenName: String) {
        try {
            val bundle = Bundle().apply {
                putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
                putString(FirebaseAnalytics.Param.SCREEN_CLASS, screenName)
            }
            FirebaseAnalytics.getInstance(context).logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, bundle)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun recordException(throwable: Throwable) {
        try {
            FirebaseCrashlytics.getInstance().recordException(throwable)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun logMessage(message: String) {
        try {
            FirebaseCrashlytics.getInstance().log(message)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
