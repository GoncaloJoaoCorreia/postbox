package com.postbox.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.postbox.shared.api.ApiException
import com.postbox.shared.domain.model.Carrier
import com.postbox.shared.domain.model.CarrierMetadata
import com.postbox.shared.domain.model.DetectionResult
import com.postbox.shared.domain.model.MaxDisplayNameLength
import com.postbox.shared.domain.model.MaxTrackingNumberLength
import com.postbox.shared.domain.model.MetadataField
import com.postbox.shared.domain.model.accepts
import com.postbox.shared.domain.model.isValidDisplayName
import com.postbox.shared.domain.model.normalizeTrackingNumber
import com.postbox.shared.repository.ParcelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddParcelUiState(
    val trackingNumber: String = "",
    val displayName: String = "",
    val trackingNumberError: String? = null,
    val displayNameError: String? = null,
    val detection: DetectionResult? = null,
    val carrierOverride: Carrier? = null,
    val metadataValues: Map<String, String> = emptyMap(),
    val metadataErrors: Map<String, String> = emptyMap(),
    val isDetecting: Boolean = false,
    val isCreating: Boolean = false,
    val error: String? = null,
    val createdParcelId: String? = null
)

class AddParcelViewModel(private val repository: ParcelRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(AddParcelUiState())
    val uiState: StateFlow<AddParcelUiState> = _uiState.asStateFlow()

    private val requiredMetadata: List<MetadataField>
        get() = (_uiState.value.carrierOverride ?: _uiState.value.detection?.carrier)
            ?.let(CarrierMetadata::requiredFieldsFor)
            .orEmpty()

    fun onTrackingNumberChange(value: String) {
        _uiState.update { it.copy(trackingNumber = value, trackingNumberError = null, detection = null, error = null) }
    }

    fun onDisplayNameChange(value: String) {
        _uiState.update { it.copy(displayName = value, displayNameError = null, error = null) }
    }

    fun onCarrierSelected(carrier: Carrier?) {
        _uiState.update { it.copy(carrierOverride = carrier, error = null) }
    }

    fun onMetadataChange(name: String, value: String) {
        _uiState.update {
            it.copy(
                metadataValues = it.metadataValues + (name to value),
                metadataErrors = it.metadataErrors - name,
                error = null
            )
        }
    }

    fun onDetect() {
        val state = _uiState.value
        val number = normalizeTrackingNumber(state.trackingNumber)
        if (number == null) {
            _uiState.update {
                it.copy(trackingNumberError = "Tracking number must be 1-$MaxTrackingNumberLength characters of letters, digits or hyphens")
            }
            return
        }
        if (state.isDetecting || state.isCreating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDetecting = true, error = null) }
            try {
                val detection = repository.detect(number, state.carrierOverride, state.metadataValues)
                _uiState.update { it.copy(isDetecting = false, detection = detection) }
            } catch (e: ApiException) {
                val inline = e.code == "invalid-tracking-number"
                _uiState.update {
                    it.copy(
                        isDetecting = false,
                        trackingNumberError = if (inline) e.message else it.trackingNumberError,
                        error = if (inline) null else e.message
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isDetecting = false, error = "Could not reach the server: ${e.message}") }
            }
        }
    }

    fun onCreate() {
        val state = _uiState.value
        val number = normalizeTrackingNumber(state.trackingNumber)
        if (number == null) {
            _uiState.update {
                it.copy(trackingNumberError = "Tracking number must be 1-$MaxTrackingNumberLength characters of letters, digits or hyphens")
            }
            return
        }
        if (state.displayName.isNotBlank() && !isValidDisplayName(state.displayName.trim())) {
            _uiState.update { it.copy(displayNameError = "Display name must be 1-$MaxDisplayNameLength characters") }
            return
        }
        val metadataErrors = requiredMetadata.mapNotNull { field ->
            val value = state.metadataValues[field.name]?.trim().orEmpty()
            val error = when {
                value.isEmpty() -> "Required"
                !field.accepts(value) -> "Invalid format"
                else -> null
            }
            error?.let { field.name to it }
        }.toMap()
        if (metadataErrors.isNotEmpty()) {
            _uiState.update { it.copy(metadataErrors = metadataErrors) }
            return
        }
        if (state.isCreating || state.isDetecting) return
        val carrier = state.carrierOverride ?: state.detection?.carrier
        val metadata = state.metadataValues.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        val displayName = state.displayName.trim().ifBlank { null }
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, error = null) }
            try {
                val parcel = repository.createParcel(number, displayName, carrier, metadata)
                _uiState.update { it.copy(isCreating = false, createdParcelId = parcel.id) }
            } catch (e: ApiException) {
                _uiState.update {
                    it.copy(
                        isCreating = false,
                        trackingNumberError = if (e.code == "invalid-tracking-number") e.message else it.trackingNumberError,
                        displayNameError = if (e.code == "invalid-display-name") e.message else it.displayNameError,
                        error = if (e.code == "invalid-tracking-number" || e.code == "invalid-display-name") null else e.message
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isCreating = false, error = "Could not reach the server: ${e.message}") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddParcelScreen(viewModel: AddParcelViewModel, onParcelCreated: (String) -> Unit, onNavigateUp: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.createdParcelId) {
        state.createdParcelId?.let(onParcelCreated)
    }
    val detection = state.detection
    val effectiveCarrier = state.carrierOverride ?: detection?.carrier
    val requiredMetadata = effectiveCarrier?.let(CarrierMetadata::requiredFieldsFor).orEmpty()
    val methodLabel = when {
        state.carrierOverride != null -> "override"
        detection != null -> detection.method.name.lowercase()
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add parcel") },
                navigationIcon = { TextButton(onClick = onNavigateUp) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = state.trackingNumber,
                onValueChange = viewModel::onTrackingNumberChange,
                label = { Text("Tracking number") },
                singleLine = true,
                isError = state.trackingNumberError != null,
                supportingText = { state.trackingNumberError?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::onDisplayNameChange,
                label = { Text("Name (optional)") },
                singleLine = true,
                isError = state.displayNameError != null,
                supportingText = { state.displayNameError?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = viewModel::onDetect,
                enabled = !state.isDetecting && !state.isCreating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isDetecting) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text(if (state.isDetecting) "Detecting carrier…" else "Detect carrier")
            }
            if (detection != null || methodLabel != null) {
                Text(
                    "Carrier: ${effectiveCarrier?.name ?: "unknown"} · method: $methodLabel",
                    style = MaterialTheme.typography.bodyMedium
                )
                detection?.let { result ->
                    Text(
                        "Candidates: ${result.candidates.joinToString { it.name }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text("Carrier override", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.carrierOverride == null,
                    onClick = { viewModel.onCarrierSelected(null) },
                    label = { Text("Auto") }
                )
                Carrier.entries.forEach { carrier ->
                    FilterChip(
                        selected = state.carrierOverride == carrier,
                        onClick = { viewModel.onCarrierSelected(carrier) },
                        label = { Text(carrier.name) }
                    )
                }
            }
            requiredMetadata.forEach { field ->
                OutlinedTextField(
                    value = state.metadataValues[field.name].orEmpty(),
                    onValueChange = { viewModel.onMetadataChange(field.name, it) },
                    label = { Text(field.label) },
                    singleLine = true,
                    isError = state.metadataErrors[field.name] != null,
                    supportingText = { Text(state.metadataErrors[field.name] ?: field.hint) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = viewModel::onCreate,
                enabled = !state.isCreating && !state.isDetecting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isCreating) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text(if (state.isCreating) "Adding…" else "Add parcel")
            }
        }
    }
}
