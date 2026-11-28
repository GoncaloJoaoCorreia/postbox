package com.postbox.shared.notification

import com.postbox.shared.storage.PlatformContext
import com.postbox.shared.sync.ParcelStatusChange

const val EXTRA_PARCEL_ID = "com.postbox.mobile.PARCEL_ID"

interface StatusChangeNotifier {
    fun notifyStatusChanges(changes: List<ParcelStatusChange>)
}

expect fun createStatusChangeNotifier(context: PlatformContext): StatusChangeNotifier
