package dev.jellystructure.ravilo.ui

import android.content.Context

object RaviloAppContext {
    private var ctx: Context? = null
    fun init(context: Context) { ctx = context.applicationContext }
    fun get(): Context = checkNotNull(ctx) { "RaviloAppContext not initialized — call init() from Application.onCreate()" }
}
