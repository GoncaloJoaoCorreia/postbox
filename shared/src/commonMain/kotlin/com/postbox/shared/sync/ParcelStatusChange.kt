package com.postbox.shared.sync

import com.postbox.shared.domain.model.Parcel
import com.postbox.shared.domain.model.TrackingStatus

data class ParcelStatusChange(
    val parcel: Parcel,
    val previousStatus: TrackingStatus,
    val newStatus: TrackingStatus
)
