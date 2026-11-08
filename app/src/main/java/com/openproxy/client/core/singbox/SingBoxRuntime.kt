package com.openproxy.client.core.singbox

import android.content.Context
import android.util.Log
import com.openproxy.client.BuildConfig
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions

/**
 * Prepares the bundled sing-box library. Must run once before any libbox call.
 */
object SingBoxRuntime {

    private const val TAG = "SingBoxRuntime"

    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                Libbox.setup(
                    SetupOptions().apply {
                        basePath = context.filesDir.absolutePath
                        workingPath = context.filesDir.absolutePath
                        tempPath = context.cacheDir.absolutePath
                        fixAndroidStack = true
                        logMaxLines = 2000L
                        debug = BuildConfig.DEBUG
                    },
                )
            } catch (t: Throwable) {
                Log.e(TAG, "libbox setup failed", t)
            }
            initialized = true
        }
    }

    val version: String by lazy {
        runCatching { Libbox.version() }.getOrDefault("недоступна")
    }
}
