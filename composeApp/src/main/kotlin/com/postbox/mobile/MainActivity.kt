package com.postbox.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import com.postbox.shared.PostboxDependencies

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dependencies = PostboxDependencies(this)
        setContent {
            PostboxTheme {
                PostboxApp(baseUrl = dependencies.sessionStore.baseUrl())
            }
        }
    }
}
