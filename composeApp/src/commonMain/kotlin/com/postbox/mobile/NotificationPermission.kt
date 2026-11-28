package com.postbox.mobile

import androidx.compose.runtime.Composable

@Composable
expect fun rememberNotificationPermissionLauncher(onResult: (Boolean) -> Unit): () -> Unit
