package com.msj.gfx.core

import android.content.Context

/**
 * Global application context.
 *
 * Lives in its own file on purpose: it was originally declared at the top of
 * MemoryTools.kt, and when that file was rewritten the whole object vanished
 * with it, taking every reference in the project with it.
 */
object Ctx {

    @Volatile
    private var app: Context? = null

    fun install(context: Context) {
        if (app == null) {
            synchronized(this) {
                if (app == null) app = context.applicationContext
            }
        }
    }

    fun get(): Context = app
        ?: error("Ctx not installed - Application.onCreate must call Ctx.install(this)")
}
