package com.postbox.mobile

import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute

@Serializable
data object ParcelListRoute : AppRoute

@Serializable
data class ParcelDetailRoute(val parcelId: String) : AppRoute

@Serializable
data object AccountRoute : AppRoute
