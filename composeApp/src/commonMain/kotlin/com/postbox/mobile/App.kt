package com.postbox.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.runtime.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.postbox.shared.PostboxDependencies
import com.postbox.shared.api.SessionExpiredException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AppGate {
    data object InstanceConfig : AppGate
    data object FirstRunSetup : AppGate
    data object Login : AppGate
    data object Main : AppGate
}

class AppViewModel(
    private val dependencies: PostboxDependencies,
    private val deviceName: String
) : ViewModel() {
    private val _gate = MutableStateFlow<AppGate?>(null)
    val gate: StateFlow<AppGate?> = _gate.asStateFlow()

    init {
        viewModelScope.launch {
            _gate.value = initialGate()
            observeSession()
        }
    }

    fun onInstanceConnected(setupPending: Boolean) {
        _gate.value = if (setupPending) AppGate.FirstRunSetup else AppGate.Login
        viewModelScope.launch { observeSession() }
    }

    fun onSetupDone() {
        _gate.value = AppGate.Login
    }

    fun onLoggedIn() {
        _gate.value = AppGate.Main
    }

    private suspend fun initialGate(): AppGate = when {
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

    private suspend fun observeSession() {
        if (dependencies.sessionStore.baseUrl() == null) return
        dependencies.authRepository().sessionState().collect { loggedIn ->
            if (!loggedIn && _gate.value == AppGate.Main) _gate.value = AppGate.Login
        }
    }
}

@Composable
fun PostboxApp(dependencies: PostboxDependencies, deviceName: String) {
    val viewModel: AppViewModel = viewModel { AppViewModel(dependencies, deviceName) }
    val gate by viewModel.gate.collectAsStateWithLifecycle()

    when (val current = gate) {
        null -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
        }
        AppGate.InstanceConfig -> InstanceSetupScreen(
            viewModel = viewModel { InstanceSetupViewModel(dependencies.instanceRepository()) },
            onConnected = viewModel::onInstanceConnected
        )
        AppGate.FirstRunSetup -> FirstRunSetupScreen(
            viewModel = viewModel { FirstRunSetupViewModel(dependencies.api()) },
            onSetupDone = viewModel::onSetupDone
        )
        AppGate.Login -> LoginScreen(
            viewModel = viewModel { LoginViewModel(dependencies.authRepository(), deviceName) },
            onLoggedIn = viewModel::onLoggedIn
        )
        AppGate.Main -> MainNav(dependencies)
    }
}

@Composable
private fun MainNav(dependencies: PostboxDependencies) {
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
            AccountScreen(authRepository = dependencies.authRepository())
        }
    }
}
