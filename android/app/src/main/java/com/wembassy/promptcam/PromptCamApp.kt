package com.wembassy.promptcam

import android.app.Application
import android.util.Log

/**
 * Application class for PromptCam.
 *
 * Initializes app-level state, logging, and provides a global context reference.
 * Referenced in AndroidManifest.xml as android:name=".PromptCamApp".
 */
class PromptCamApp : Application() {

    companion object {
        private const val TAG = "PromptCamApp"

        @Volatile
        lateinit var instance: PromptCamApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "PromptCam application initialized")

        // Set up default uncaught exception handler for crash logging
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
