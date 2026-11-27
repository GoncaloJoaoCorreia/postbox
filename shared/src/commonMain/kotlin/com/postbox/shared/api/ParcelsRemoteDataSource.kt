package com.postbox.shared.api

import com.postbox.shared.api.model.Parcel
import com.postbox.shared.api.model.ParcelEvent

interface ParcelsRemoteDataSource {
    suspend fun listParcels(): List<Parcel>
    suspend fun getParcel(id: String): Parcel
    suspend fun listParcelEvents(id: String): List<ParcelEvent>
    suspend fun refreshParcel(id: String): Parcel
}
