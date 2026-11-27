package com.postbox.mobile.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import com.postbox.mobile.PostboxApp
import com.postbox.mobile.PostboxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dependencies = (application as PostboxApplication).dependencies
        setContent {
            PostboxTheme {
                PostboxApp(dependencies = dependencies, deviceName = Build.MODEL)
            }
        }
    }
}
