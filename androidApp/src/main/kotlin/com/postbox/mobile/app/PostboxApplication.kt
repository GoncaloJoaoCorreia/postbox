package com.postbox.mobile.app

import android.app.Application
import com.postbox.shared.PostboxDependencies

class PostboxApplication : Application() {
    val dependencies: PostboxDependencies by lazy { PostboxDependencies(this) }
}
