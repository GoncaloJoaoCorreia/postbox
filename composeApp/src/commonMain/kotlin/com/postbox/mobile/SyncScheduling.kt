package com.postbox.mobile

import com.postbox.shared.storage.PlatformContext

expect fun scheduleParcelSync(context: PlatformContext)

expect fun cancelParcelSync(context: PlatformContext)

expect fun syncParcelsNow(context: PlatformContext)
