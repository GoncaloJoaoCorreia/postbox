package com.postbox.shared.api

import com.postbox.shared.domain.model.Carrier
import com.postbox.shared.domain.model.DetectionResult
import com.postbox.shared.domain.model.Parcel
import com.postbox.shared.domain.model.ParcelEvent

interface ParcelsRemoteDataSource {
    suspend fun listParcels(): List<Parcel>
    suspend fun getParcel(id: String): Parcel
    suspend fun listParcelEvents(id: String): List<ParcelEvent>
    suspend fun refreshParcel(id: String): Parcel
    suspend fun updateParcel(
        id: String,
        displayName: String? = null,
        archived: Boolean? = null,
        carrier: Carrier? = null,
        metadata: Map<String, String>? = null
    ): Parcel
    suspend fun deleteParcel(id: String)
    suspend fun detect(trackingNumber: String, carrier: Carrier? = null, metadata: Map<String, String> = emptyMap()): DetectionResult
    suspend fun createParcel(trackingNumber: String, displayName: String? = null, carrier: Carrier? = null, metadata: Map<String, String> = emptyMap()): Parcel
}
