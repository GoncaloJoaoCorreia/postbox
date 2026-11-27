package com.postbox.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.postbox.shared.api.ApiException
import com.postbox.shared.auth.AuthRepository
import com.postbox.shared.domain.model.DeviceSessionInfo
import com.postbox.shared.domain.model.UserAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountUiState(
    val account: UserAccount? = null,
    val devices: List<DeviceSessionInfo> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val newPassword: String = "",
    val actionError: String? = null,
    val isBusy: Boolean = false,
    val confirmLogout: Boolean = false
)

class AccountViewModel(private val authRepository: AuthRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    fun currentSessionId(): String? = authRepository.currentSessionId()

    fun reload() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val account = authRepository.me()
                val devices = authRepository.listDevices()
                _uiState.update { it.copy(account = account, devices = devices, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun onPasswordChange(newPassword: String) {
        _uiState.update { it.copy(newPassword = newPassword, actionError = null) }
    }

    fun changePassword() {
        val password = _uiState.value.newPassword
        if (password.length < 8 || _uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, actionError = null) }
            try {
                authRepository.changePassword(password)
                _uiState.update { it.copy(isBusy = false) }
            } catch (e: ApiException) {
                _uiState.update { it.copy(isBusy = false, actionError = e.message) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, actionError = "Could not reach the server: ${e.message}") }
            }
        }
    }

    fun revokeDevice(id: String) {
        viewModelScope.launch {
            try {
                authRepository.revokeDevice(id)
                reload()
            } catch (e: ApiException) {
                _uiState.update { it.copy(actionError = e.message) }
            } catch (e: Exception) {
                _uiState.update { it.copy(actionError = "Could not reach the server: ${e.message}") }
            }
        }
    }

    fun onLogoutClick() {
        _uiState.update { it.copy(confirmLogout = true) }
    }

    fun onLogoutDismissed() {
        _uiState.update { it.copy(confirmLogout = false) }
    }

    fun logout() {
        _uiState.update { it.copy(confirmLogout = false) }
        viewModelScope.launch {
            authRepository.logout()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(authRepository: AuthRepository) {
    val viewModel: AccountViewModel = viewModel { AccountViewModel(authRepository) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Account") }) }
    ) { padding ->
        if (state.isLoading) {
            Column(modifier = Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(state.account?.displayName ?: "", style = MaterialTheme.typography.titleMedium)
                        Text(state.account?.email ?: "", style = MaterialTheme.typography.bodyMedium)
                        Text("Role: ${state.account?.role}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Change password", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Changing the password revokes all device sessions, including this one.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = state.newPassword,
                        onValueChange = viewModel::onPasswordChange,
                        label = { Text("New password (min. 8 characters)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = viewModel::changePassword,
                        enabled = state.newPassword.length >= 8 && !state.isBusy
                    ) {
                        Text("Change password")
                    }
                }
            }
            item {
                Text("Device sessions", style = MaterialTheme.typography.titleMedium)
            }
            items(state.devices) { device ->
                val isCurrent = device.id == viewModel.currentSessionId()
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(device.deviceName + if (isCurrent) " (this device)" else "", style = MaterialTheme.typography.bodyMedium)
                            Text("Created: ${device.createdAt}", style = MaterialTheme.typography.bodySmall)
                            if (device.revokedAt != null) {
                                Text("Revoked: ${device.revokedAt}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (device.revokedAt == null) {
                            TextButton(onClick = { viewModel.revokeDevice(device.id) }) {
                                Text("Revoke")
                            }
                        }
                    }
                }
            }
            item {
                state.actionError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(
                    onClick = viewModel::onLogoutClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Log out")
                }
            }
        }
    }
    if (state.confirmLogout) {
        AlertDialog(
            onDismissRequest = viewModel::onLogoutDismissed,
            title = { Text("Log out?") },
            text = { Text("The session on this device is revoked on the server and local data is wiped.") },
            confirmButton = {
                TextButton(onClick = viewModel::logout) {
                    Text("Log out")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onLogoutDismissed) {
                    Text("Cancel")
                }
            }
        )
    }
}
