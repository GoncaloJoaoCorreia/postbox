package com.postbox.mobile

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun PostboxApp(baseUrl: String?) {
    if (baseUrl == null) {
        ErrorState(message = "No Postbox instance configured yet")
        return
    }
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "parcels") {
        composable("parcels") {
            ParcelListScreen(onParcelClick = { id -> navController.navigate("parcels/$id") })
        }
        composable("parcels/{id}") { entry ->
            ParcelDetailScreen(parcelId = entry.arguments?.getString("id").orEmpty())
        }
    }
}
