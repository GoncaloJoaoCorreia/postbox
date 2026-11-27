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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.postbox.shared.instance.InstanceProbeResult
import com.postbox.shared.instance.InstanceRepository
import com.postbox.shared.instance.InstanceUrl
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceSetupScreen(
    instanceRepository: InstanceRepository,
    onConnected: (setupPending: Boolean) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var acknowledgedInsecure by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val normalized = InstanceUrl.normalize(input)
    val plainHttp = normalized != null && InstanceUrl.isPlainHttp(normalized)
    val canConnect = normalized != null && (!plainHttp || acknowledgedInsecure) && !loading

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
                value = input,
                onValueChange = { input = it; error = null },
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
                                checked = acknowledgedInsecure,
                                onCheckedChange = { acknowledgedInsecure = it }
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
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        when (val result = instanceRepository.connect(input)) {
                            is InstanceProbeResult.Connected -> onConnected(result.setupPending)
                            InstanceProbeResult.InvalidUrl -> {
                                error = "Enter a valid server address, e.g. postbox.example.com"
                                loading = false
                            }
                            is InstanceProbeResult.Unreachable -> {
                                error = "Could not reach $normalized: ${result.message}"
                                loading = false
                            }
                        }
                    }
                },
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text(if (loading) "Connecting…" else "Connect")
            }
            if (instanceRepository.currentBaseUrl() != null) {
                TextButton(onClick = { onConnected(false) }) {
                    Text("Use the saved server instead")
                }
            }
        }
    }
}
