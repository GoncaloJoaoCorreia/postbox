package com.postbox.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.postbox.shared.api.ApiException
import com.postbox.shared.domain.model.MetadataField
import com.postbox.shared.domain.model.Parcel
import com.postbox.shared.domain.model.ParcelEvent
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
    val missingMetadata: List<MetadataField>? = null
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
                _uiState.update { it.copy(parcel = parcel, events = events, isLoading = false) }
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
                _uiState.update { it.copy(parcel = parcel, events = events, isRefreshing = false) }
            } catch (e: ApiException) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        error = e.message,
                        missingMetadata = if (e.code == "missing-carrier-metadata") e.fields else null
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefreshing = false, error = "Could not reach the server: ${e.message}") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParcelDetailScreen(parcelId: String, viewModel: ParcelDetailViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.parcel?.displayName ?: "Parcel") },
                actions = {
                    TextButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
                        Text(if (state.isRefreshing) "Refreshing…" else "Refresh")
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
                }
                state.missingMetadata?.let { fields ->
                    Text("The carrier needs more details:", style = MaterialTheme.typography.bodyMedium)
                    fields.forEach { field ->
                        Text("• ${field.label} — ${field.hint}", style = MaterialTheme.typography.bodySmall)
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
                    state.events.forEach { event ->
                        Text(
                            "${event.occurredAt} ${event.status.name} — ${event.description}" +
                                (event.location?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
