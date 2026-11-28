package com.postbox.mobile.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.postbox.mobile.PostboxApp
import com.postbox.mobile.PostboxTheme
import com.postbox.mobile.syncParcelsNow
import com.postbox.shared.notification.EXTRA_PARCEL_ID

class MainActivity : ComponentActivity() {
    private var pendingParcelId: String? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingParcelId = intent?.getStringExtra(EXTRA_PARCEL_ID)
        val dependencies = (application as PostboxApplication).dependencies
        setContent {
            PostboxTheme {
                PostboxApp(dependencies = dependencies, deviceName = Build.MODEL, pendingParcelId = pendingParcelId)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.getStringExtra(EXTRA_PARCEL_ID)?.let { pendingParcelId = it }
    }

    override fun onResume() {
        super.onResume()
        val dependencies = (application as PostboxApplication).dependencies
        if (dependencies.syncSettings.backgroundPollingEnabled()) {
            syncParcelsNow(this)
        }
    }
}
