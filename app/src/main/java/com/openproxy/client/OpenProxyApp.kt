package com.openproxy.client

import android.app.Application
import com.openproxy.client.core.AppGraph
import com.openproxy.client.core.singbox.SingBoxRuntime

class OpenProxyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        // Loading the Go library early keeps the first connect fast.
        SingBoxRuntime.ensureInitialized(this)
    }
}
