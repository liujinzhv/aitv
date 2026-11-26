package com.aitv.launcher.util

import android.util.Log
import java.util.concurrent.Executors

object Async {
    private val pool = Executors.newFixedThreadPool(4)

    fun io(task: () -> Unit) {
        pool.execute {
            try {
                task()
            } catch (t: Throwable) {
                Log.w("MyTV", "background task failed", t)
            }
        }
    }
}
