package com.postbox.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.postbox.shared.PostboxDependencies
import com.postbox.shared.api.SessionExpiredException

private sealed interface AppGate {
    data object InstanceConfig : AppGate
    data object FirstRunSetup : AppGate
    data object Login : AppGate
    data object Main : AppGate
}

@Composable
fun PostboxApp(dependencies: PostboxDependencies, deviceName: String) {
    var gate by remember { mutableStateOf<AppGate?>(null) }

    LaunchedEffect(Unit) {
        gate = when {
            dependencies.sessionStore.baseUrl() == null -> AppGate.InstanceConfig
            dependencies.authRepository().hasSession() -> {
                try {
                    dependencies.authRepository().me()
                    AppGate.Main
                } catch (e: SessionExpiredException) {
                    AppGate.Login
                } catch (e: Exception) {
                    AppGate.Main
                }
            }
            else -> {
                val setupPending = runCatching { dependencies.api().setupStatus() }.getOrDefault(false)
                if (setupPending) AppGate.FirstRunSetup else AppGate.Login
            }
        }
    }

    when (val current = gate) {
        null -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
        }
        AppGate.InstanceConfig -> InstanceSetupScreen(dependencies.instanceRepository()) { setupPending ->
            gate = if (setupPending) AppGate.FirstRunSetup else AppGate.Login
        }
        AppGate.FirstRunSetup -> FirstRunSetupScreen(dependencies.api()) {
            gate = AppGate.Login
        }
        AppGate.Login -> LoginScreen(dependencies.authRepository(), deviceName) {
            gate = AppGate.Main
        }
        AppGate.Main -> MainNav(dependencies) {
            gate = AppGate.Login
        }
    }
}

@Composable
private fun MainNav(dependencies: PostboxDependencies, onSessionEnded: () -> Unit) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ParcelListRoute) {
        composable<ParcelListRoute> {
            ParcelListScreen(
                onParcelClick = { id -> navController.navigate(ParcelDetailRoute(id)) },
                onAccountClick = { navController.navigate(AccountRoute) }
            )
        }
        composable<ParcelDetailRoute> { entry ->
            ParcelDetailScreen(parcelId = entry.toRoute<ParcelDetailRoute>().parcelId)
        }
        composable<AccountRoute> {
            AccountScreen(dependencies.authRepository(), onSessionEnded)
        }
    }
}
