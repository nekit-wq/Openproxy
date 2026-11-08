package com.openproxy.client.core

import android.content.Context
import com.openproxy.client.core.store.AppRepository

/** Minimal service locator: one repository for the whole process, no DI framework. */
object AppGraph {
    @Volatile
    private var repositoryInstance: AppRepository? = null

    val repository: AppRepository
        get() = checkNotNull(repositoryInstance) { "AppGraph.init() was not called" }

    fun init(context: Context) {
        if (repositoryInstance == null) {
            synchronized(this) {
                if (repositoryInstance == null) {
                    repositoryInstance = AppRepository(context.applicationContext)
                }
            }
        }
    }
}
