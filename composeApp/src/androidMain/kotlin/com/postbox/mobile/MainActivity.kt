package com.postbox.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.postbox.shared.storage.createInstanceSessionStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = createInstanceSessionStore(this)
        setContent {
            PostboxTheme {
                PostboxApp(baseUrl = session.baseUrl())
            }
        }
    }
}
