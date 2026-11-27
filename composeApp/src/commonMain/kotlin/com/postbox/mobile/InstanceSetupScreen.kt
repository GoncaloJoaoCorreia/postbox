package com.postbox.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.runtime.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.postbox.shared.instance.InstanceProbeResult
import com.postbox.shared.instance.InstanceRepository
import com.postbox.shared.instance.InstanceUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InstanceSetupUiState(
    val input: String = "",
    val acknowledgedInsecure: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val connectedSetupPending: Boolean? = null
)

class InstanceSetupViewModel(private val instanceRepository: InstanceRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(InstanceSetupUiState())
    val uiState: StateFlow<InstanceSetupUiState> = _uiState.asStateFlow()

    val hasSavedInstance: Boolean
        get() = instanceRepository.currentBaseUrl() != null

    fun onInputChange(input: String) {
        _uiState.update { it.copy(input = input, error = null) }
    }

    fun onAcknowledgeInsecure(acknowledged: Boolean) {
        _uiState.update { it.copy(acknowledgedInsecure = acknowledged) }
    }

    fun onConnect() {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = instanceRepository.connect(_uiState.value.input)) {
                is InstanceProbeResult.Connected ->
                    _uiState.update { it.copy(isLoading = false, connectedSetupPending = result.setupPending) }
                InstanceProbeResult.InvalidUrl ->
                    _uiState.update { it.copy(isLoading = false, error = "Enter a valid server address, e.g. postbox.example.com") }
                is InstanceProbeResult.Unreachable -> {
                    val normalized = InstanceUrl.normalize(_uiState.value.input)
                    _uiState.update { it.copy(isLoading = false, error = "Could not reach $normalized: ${result.message}") }
                }
            }
        }
    }

    fun onUseSavedServer() {
        _uiState.update { it.copy(connectedSetupPending = false) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceSetupScreen(viewModel: InstanceSetupViewModel, onConnected: (Boolean) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.connectedSetupPending) { setupPending ->
        if (setupPending != null) onConnected(setupPending)
    }

    val normalized = InstanceUrl.normalize(state.input)
    val plainHttp = normalized != null && InstanceUrl.isPlainHttp(normalized)
    val canConnect = normalized != null && (!plainHttp || state.acknowledgedInsecure) && !state.isLoading

    Scaffold(
        topBar = { TopAppBar(title = { Text("Connect to a server") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Enter the address of your Postbox server. HTTPS is used by default.",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                label = { Text("Server address") },
                placeholder = { Text("postbox.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            if (plainHttp) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Plain HTTP is not encrypted. Only use it on a trusted local network.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = state.acknowledgedInsecure,
                                onCheckedChange = viewModel::onAcknowledgeInsecure
                            )
                            Text(
                                "I understand this connection is not encrypted",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = viewModel::onConnect,
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text(if (state.isLoading) "Connecting…" else "Connect")
            }
            if (viewModel.hasSavedInstance) {
                TextButton(onClick = viewModel::onUseSavedServer) {
                    Text("Use the saved server instead")
                }
            }
        }
    }
}
