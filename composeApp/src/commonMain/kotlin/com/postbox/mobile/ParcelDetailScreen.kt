package com.postbox.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.postbox.shared.api.ApiException
import com.postbox.shared.domain.model.Carrier
import com.postbox.shared.domain.model.MaxDisplayNameLength
import com.postbox.shared.domain.model.MetadataField
import com.postbox.shared.domain.model.Parcel
import com.postbox.shared.domain.model.ParcelEvent
import com.postbox.shared.domain.model.accepts
import com.postbox.shared.domain.model.isValidDisplayName
import com.postbox.shared.repository.ParcelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ParcelDetailUiState(
    val parcel: Parcel? = null,
    val events: List<ParcelEvent> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val missingMetadata: List<MetadataField>? = null,
    val metadataValues: Map<String, String> = emptyMap(),
    val metadataErrors: Map<String, String> = emptyMap(),
    val isSavingMetadata: Boolean = false,
    val showManageMenu: Boolean = false,
    val showRenameDialog: Boolean = false,
    val renameValue: String = "",
    val renameError: String? = null,
    val showCarrierDialog: Boolean = false,
    val carrierSelection: Carrier? = null,
    val showDeleteDialog: Boolean = false,
    val isBusy: Boolean = false,
    val deleted: Boolean = false
)

class ParcelDetailViewModel(
    private val repository: ParcelRepository,
    private val parcelId: String
) : ViewModel() {
    private val _uiState = MutableStateFlow(ParcelDetailUiState())
    val uiState: StateFlow<ParcelDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val parcel = repository.parcelDetail(parcelId)
                val events = repository.parcelEvents(parcelId)
                _uiState.update {
                    it.copy(
                        parcel = parcel,
                        events = events,
                        isLoading = false,
                        missingMetadata = it.missingMetadata ?: parcel.missingMetadata
                    )
                }
                if (parcel.lastCheckedAt == null) refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null, missingMetadata = null) }
            try {
                val parcel = repository.refreshParcel(parcelId)
                val events = repository.parcelEvents(parcelId)
                _uiState.update {
                    it.copy(
                        parcel = parcel,
                        events = events,
                        isRefreshing = false,
                        missingMetadata = parcel.missingMetadata
                    )
                }
            } catch (e: ApiException) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        error = e.message,
                        missingMetadata = if (e.code == "missing-carrier-metadata") e.fields else it.missingMetadata
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefreshing = false, error = "Could not reach the server: ${e.message}") }
            }
        }
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

    fun saveMetadata() {
        val fields = _uiState.value.missingMetadata.orEmpty()
        val metadataErrors = fields.mapNotNull { field ->
            val value = _uiState.value.metadataValues[field.name]?.trim().orEmpty()
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
        if (_uiState.value.isSavingMetadata) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingMetadata = true, error = null) }
            try {
                val metadata = _uiState.value.metadataValues.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
                val parcel = repository.updateParcel(parcelId, metadata = metadata)
                _uiState.update {
                    it.copy(
                        parcel = parcel,
                        isSavingMetadata = false,
                        missingMetadata = null,
                        metadataValues = emptyMap(),
                        metadataErrors = emptyMap()
                    )
                }
                refresh()
            } catch (e: ApiException) {
                _uiState.update { it.copy(isSavingMetadata = false, error = e.message) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSavingMetadata = false, error = "Could not reach the server: ${e.message}") }
            }
        }
    }

    fun onManageClick() {
        _uiState.update { it.copy(showManageMenu = !it.showManageMenu) }
    }

    fun onManageDismissed() {
        _uiState.update { it.copy(showManageMenu = false) }
    }

    fun openRenameDialog() {
        _uiState.update {
            it.copy(
                showManageMenu = false,
                showRenameDialog = true,
                renameValue = it.parcel?.displayName.orEmpty(),
                renameError = null
            )
        }
    }

    fun onRenameValueChange(value: String) {
        _uiState.update { it.copy(renameValue = value, renameError = null) }
    }

    fun dismissRenameDialog() {
        _uiState.update { it.copy(showRenameDialog = false) }
    }

    fun rename() {
        val value = _uiState.value.renameValue.trim()
        if (!isValidDisplayName(value)) {
            _uiState.update { it.copy(renameError = "Display name must be 1-$MaxDisplayNameLength characters") }
            return
        }
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            try {
                val parcel = repository.updateParcel(parcelId, displayName = value)
                _uiState.update { it.copy(parcel = parcel, isBusy = false, showRenameDialog = false) }
            } catch (e: ApiException) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        renameError = if (e.code == "invalid-display-name") e.message else it.renameError,
                        error = if (e.code == "invalid-display-name") null else e.message
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, error = "Could not reach the server: ${e.message}") }
            }
        }
    }

    fun setArchived(archived: Boolean) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(showManageMenu = false, isBusy = true, error = null) }
            try {
                val parcel = repository.updateParcel(parcelId, archived = archived)
                _uiState.update { it.copy(parcel = parcel, isBusy = false) }
            } catch (e: ApiException) {
                _uiState.update { it.copy(isBusy = false, error = e.message) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, error = "Could not reach the server: ${e.message}") }
            }
        }
    }

    fun openCarrierDialog() {
        _uiState.update {
            it.copy(showManageMenu = false, showCarrierDialog = true, carrierSelection = it.parcel?.carrier, error = null)
        }
    }

    fun onCarrierSelected(carrier: Carrier?) {
        _uiState.update { it.copy(carrierSelection = carrier) }
    }

    fun dismissCarrierDialog() {
        _uiState.update { it.copy(showCarrierDialog = false) }
    }

    fun changeCarrier() {
        val carrier = _uiState.value.carrierSelection ?: return
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            try {
                val parcel = repository.updateParcel(parcelId, carrier = carrier)
                _uiState.update { it.copy(parcel = parcel, isBusy = false, showCarrierDialog = false) }
                refresh()
            } catch (e: ApiException) {
                _uiState.update { it.copy(isBusy = false, error = e.message) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, error = "Could not reach the server: ${e.message}") }
            }
        }
    }

    fun onDeleteClick() {
        _uiState.update { it.copy(showManageMenu = false, showDeleteDialog = true) }
    }

    fun onDeleteDismissed() {
        _uiState.update { it.copy(showDeleteDialog = false) }
    }

    fun delete() {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            try {
                repository.deleteParcel(parcelId)
                _uiState.update { it.copy(isBusy = false, showDeleteDialog = false, deleted = true) }
            } catch (e: ApiException) {
                _uiState.update { it.copy(isBusy = false, showDeleteDialog = false, error = e.message) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, showDeleteDialog = false, error = "Could not reach the server: ${e.message}") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParcelDetailScreen(parcelId: String, viewModel: ParcelDetailViewModel, onNavigateUp: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) {
        if (state.deleted) onNavigateUp()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.parcel?.displayName ?: "Parcel") },
                actions = {
                    TextButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
                        Text(if (state.isRefreshing) "Refreshing…" else "Refresh")
                    }
                    Box {
                        TextButton(onClick = viewModel::onManageClick) { Text("Manage") }
                        DropdownMenu(
                            expanded = state.showManageMenu,
                            onDismissRequest = viewModel::onManageDismissed
                        ) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = viewModel::openRenameDialog)
                            DropdownMenuItem(
                                text = { Text(if (state.parcel?.archivedAt != null) "Unarchive" else "Archive") },
                                onClick = { viewModel.setArchived(state.parcel?.archivedAt == null) }
                            )
                            DropdownMenuItem(text = { Text("Change carrier") }, onClick = viewModel::openCarrierDialog)
                            DropdownMenuItem(text = { Text("Delete") }, onClick = viewModel::onDeleteClick)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                state.parcel?.let { parcel ->
                    Text("Tracking number: ${parcel.trackingNumber}", style = MaterialTheme.typography.bodyMedium)
                    Text("Status: ${parcel.status.name}", style = MaterialTheme.typography.titleMedium)
                    parcel.carrier?.let { carrier ->
                        val method = parcel.detectionMethod?.name?.lowercase()
                        Text(
                            "Carrier: ${carrier.name}" + (method?.let { " ($it)" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    parcel.lastCheckError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    parcel.lastCheckedAt?.let {
                        Text(
                            "Last checked: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                state.missingMetadata?.let { fields ->
                    Text("The carrier needs more details:", style = MaterialTheme.typography.titleMedium)
                    fields.forEach { field ->
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
                    Button(onClick = viewModel::saveMetadata, enabled = !state.isSavingMetadata) {
                        Text(if (state.isSavingMetadata) "Saving…" else "Save and refresh")
                    }
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.events.isEmpty()) {
                    Text(
                        "No events yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Timeline", style = MaterialTheme.typography.titleMedium)
                    state.events.forEachIndexed { index, event ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                )
                                if (index < state.events.lastIndex) {
                                    Box(
                                        modifier = Modifier
                                            .width(2.dp)
                                            .weight(1f)
                                            .background(MaterialTheme.colorScheme.outlineVariant)
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.padding(start = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    event.status.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(event.description, style = MaterialTheme.typography.bodyMedium)
                                event.location?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    event.occurredAt.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.showRenameDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRenameDialog,
            title = { Text("Rename parcel") },
            text = {
                OutlinedTextField(
                    value = state.renameValue,
                    onValueChange = viewModel::onRenameValueChange,
                    label = { Text("Name") },
                    singleLine = true,
                    isError = state.renameError != null,
                    supportingText = { state.renameError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::rename, enabled = !state.isBusy) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRenameDialog) { Text("Cancel") }
            }
        )
    }
    if (state.showCarrierDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissCarrierDialog,
            title = { Text("Change carrier") },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Carrier.entries.forEach { carrier ->
                        FilterChip(
                            selected = state.carrierSelection == carrier,
                            onClick = { viewModel.onCarrierSelected(carrier) },
                            label = { Text(carrier.name) }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::changeCarrier,
                    enabled = state.carrierSelection != null && !state.isBusy
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCarrierDialog) { Text("Cancel") }
            }
        )
    }
    if (state.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = viewModel::onDeleteDismissed,
            title = { Text("Delete parcel?") },
            text = { Text("The parcel and its cached events are removed from the server and this device.") },
            confirmButton = {
                TextButton(onClick = viewModel::delete, enabled = !state.isBusy) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDeleteDismissed) { Text("Cancel") }
            }
        )
    }
}
