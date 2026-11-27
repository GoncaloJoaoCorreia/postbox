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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.postbox.shared.api.ApiException
import com.postbox.shared.api.SessionExpiredException
import com.postbox.shared.auth.AuthRepository
import com.postbox.shared.domain.model.DeviceSessionInfo
import com.postbox.shared.domain.model.UserAccount
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    authRepository: AuthRepository,
    onSessionEnded: () -> Unit
) {
    var account by remember { mutableStateOf<UserAccount?>(null) }
    var devices by remember { mutableStateOf<List<DeviceSessionInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var newPassword by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        account = authRepository.me()
        devices = authRepository.listDevices()
    }

    LaunchedEffect(Unit) {
        try {
            reload()
        } catch (e: SessionExpiredException) {
            onSessionEnded()
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Account") }) }
    ) { padding ->
        if (loading) {
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
                        Text(account?.displayName ?: "", style = MaterialTheme.typography.titleMedium)
                        Text(account?.email ?: "", style = MaterialTheme.typography.bodyMedium)
                        Text("Role: ${account?.role}", style = MaterialTheme.typography.bodySmall)
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
                        value = newPassword,
                        onValueChange = { newPassword = it; actionError = null },
                        label = { Text("New password (min. 8 characters)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            busy = true
                            actionError = null
                            scope.launch {
                                try {
                                    authRepository.changePassword(newPassword)
                                    onSessionEnded()
                                } catch (e: SessionExpiredException) {
                                    onSessionEnded()
                                } catch (e: ApiException) {
                                    actionError = e.message
                                } catch (e: Exception) {
                                    actionError = "Could not reach the server: ${e.message}"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = newPassword.length >= 8 && !busy
                    ) {
                        Text("Change password")
                    }
                }
            }
            item {
                Text("Device sessions", style = MaterialTheme.typography.titleMedium)
            }
            items(devices) { device ->
                val isCurrent = device.id == authRepository.currentSessionId()
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
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            authRepository.revokeDevice(device.id)
                                            if (isCurrent) onSessionEnded() else reload()
                                        } catch (e: SessionExpiredException) {
                                            onSessionEnded()
                                        } catch (e: ApiException) {
                                            actionError = e.message
                                        }
                                    }
                                }
                            ) {
                                Text("Revoke")
                            }
                        }
                    }
                }
            }
            item {
                actionError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(
                    onClick = { confirmLogout = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Log out")
                }
            }
        }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("The session on this device is revoked on the server and local data is wiped.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        scope.launch {
                            authRepository.logout()
                            onSessionEnded()
                        }
                    }
                ) {
                    Text("Log out")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
