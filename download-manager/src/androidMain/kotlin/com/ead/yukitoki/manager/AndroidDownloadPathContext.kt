package com.ead.yukitoki.manager

import android.content.Context

object AndroidDownloadPathContext {
    @Volatile
    private var appContext: Context? = null

    fun register(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): Context? = appContext
}
