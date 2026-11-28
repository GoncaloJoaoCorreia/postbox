package com.postbox.mobile.app

import android.app.Application
import com.postbox.mobile.cancelParcelSync
import com.postbox.mobile.scheduleParcelSync
import com.postbox.shared.DependenciesProvider
import com.postbox.shared.PostboxDependencies

class PostboxApplication : Application(), DependenciesProvider {
    override val dependencies: PostboxDependencies = PostboxDependencies(this)

    override fun onCreate() {
        super.onCreate()
        if (dependencies.syncSettings.backgroundPollingEnabled()) {
            scheduleParcelSync(this)
        } else {
            cancelParcelSync(this)
        }
    }
}
