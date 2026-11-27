package com.postbox.mobile

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute

@Composable
fun PostboxApp(baseUrl: String?) {
    if (baseUrl == null) {
        ErrorState(message = "No Postbox instance configured yet")
        return
    }
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ParcelListRoute) {
        composable<ParcelListRoute> {
            ParcelListScreen(onParcelClick = { id -> navController.navigate(ParcelDetailRoute(id)) })
        }
        composable<ParcelDetailRoute> { entry ->
            ParcelDetailScreen(parcelId = entry.toRoute<ParcelDetailRoute>().parcelId)
        }
    }
}
