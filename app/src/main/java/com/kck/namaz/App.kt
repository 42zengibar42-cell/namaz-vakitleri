package com.kck.namaz

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Bütün ekranlarda tam ekran: üst (saat/pil) ve alt (gezinme) çubukları gizlenir, kenardan kaydırınca görünür. */
class NamazApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) = applyFullScreen(a)
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    companion object {
        fun fullScreenOn(a: Activity) = a.getSharedPreferences("namaz", MODE_PRIVATE).getBoolean("fullScreen", true)

        fun applyFullScreen(a: Activity) {
            val c = WindowCompat.getInsetsController(a.window, a.window.decorView)
            if (fullScreenOn(a)) {
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            } else c.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
