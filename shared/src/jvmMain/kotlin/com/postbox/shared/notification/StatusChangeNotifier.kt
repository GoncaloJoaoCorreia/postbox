package com.postbox.shared.notification

import com.postbox.shared.storage.PlatformContext
import com.postbox.shared.sync.ParcelStatusChange

actual fun createStatusChangeNotifier(context: PlatformContext): StatusChangeNotifier =
    object : StatusChangeNotifier {
        override fun notifyStatusChanges(changes: List<ParcelStatusChange>) = Unit
    }
