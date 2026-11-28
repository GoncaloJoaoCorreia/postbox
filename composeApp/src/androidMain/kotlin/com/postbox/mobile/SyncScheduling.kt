package com.postbox.mobile

import com.postbox.mobile.sync.ParcelSyncScheduler
import com.postbox.shared.storage.PlatformContext

actual fun scheduleParcelSync(context: PlatformContext) {
    ParcelSyncScheduler.schedulePeriodic(context)
}

actual fun cancelParcelSync(context: PlatformContext) {
    ParcelSyncScheduler.cancelPeriodic(context)
}

actual fun syncParcelsNow(context: PlatformContext) {
    ParcelSyncScheduler.syncNow(context)
}
