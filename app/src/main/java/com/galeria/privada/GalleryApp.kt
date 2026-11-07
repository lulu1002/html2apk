package com.galeria.privada

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Bloqueia o app 10s depois que ele vai para segundo plano. */
class GalleryApp : Application() {
    private var started = 0
    private val handler = Handler(Looper.getMainLooper())
    private val lockRunnable = Runnable { Session.appUnlocked = false }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                handler.removeCallbacks(lockRunnable)
            }
            override fun onActivityStopped(activity: Activity) {
                started--
                if (started <= 0) handler.postDelayed(lockRunnable, 10_000L)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
