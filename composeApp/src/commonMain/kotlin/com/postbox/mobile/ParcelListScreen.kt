package com.postbox.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.postbox.shared.api.ApiException
import com.postbox.shared.domain.model.Parcel
import com.postbox.shared.domain.model.TrackingStatus
import com.postbox.shared.notification.StatusChangeNotifier
import com.postbox.shared.repository.ParcelRepository
import com.postbox.shared.storage.SyncSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ParcelListUiState(
    val parcels: List<Parcel> = emptyList(),
    val showArchived: Boolean = false,
    val isSyncing: Boolean = false,
    val error: String? = null
)

class ParcelListViewModel(
    private val repository: ParcelRepository,
    private val syncSettings: SyncSettingsStore,
    private val notifier: StatusChangeNotifier
) : ViewModel() {
    private val _showArchived = MutableStateFlow(false)
    private val _isSyncing = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ParcelListUiState> = combine(
        repository.parcelsFlow(includeArchived = true),
        _showArchived,
        _isSyncing,
        _error
    ) { all, showArchived, isSyncing, error ->
        ParcelListUiState(
            parcels = if (showArchived) all else all.filter { it.archivedAt == null },
            showArchived = showArchived,
            isSyncing = isSyncing,
            error = error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ParcelListUiState())

    init {
        sync()
    }

    fun sync() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            _error.value = null
            try {
                val changes = repository.syncParcels()
                if (syncSettings.notificationsEnabled()) notifier.notifyStatusChanges(changes)
            } catch (e: ApiException) {
                _error.value = e.message
            } catch (e: Exception) {
                _error.value = "Could not reach the server: ${e.message}"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun onShowArchivedChange(show: Boolean) {
        _showArchived.value = show
    }
}

@Composable
private fun StatusChip(status: TrackingStatus) {
    val colors = when (status) {
        TrackingStatus.DELIVERED -> AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
        TrackingStatus.EXCEPTION -> AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            labelColor = MaterialTheme.colorScheme.onErrorContainer
        )
        TrackingStatus.OUT_FOR_DELIVERY -> AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            labelColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
        else -> AssistChipDefaults.assistChipColors()
    }
    AssistChip(onClick = {}, label = { Text(status.name) }, colors = colors)
}

@Composable
private fun ParcelListItem(parcel: Parcel, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(parcel.displayName, style = MaterialTheme.typography.titleMedium)
                StatusChip(status = parcel.status)
            }
            Text(
                parcel.trackingNumber,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            parcel.carrier?.let {
                Text(
                    "Carrier: ${it.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            parcel.latestEvent?.let { event ->
                Text(
                    event.description + (event.location?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            parcel.lastCheckError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            parcel.lastCheckedAt?.let {
                Text(
                    "Last checked: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParcelListScreen(
    viewModel: ParcelListViewModel,
    onParcelClick: (String) -> Unit,
    onAccountClick: () -> Unit,
    onAddClick: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Parcels") },
                actions = {
                    TextButton(onClick = onAddClick) { Text("Add") }
                    TextButton(onClick = onAccountClick) { Text("Account") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.showArchived,
                    onClick = { viewModel.onShowArchivedChange(!state.showArchived) },
                    label = { Text("Show archived") }
                )
            }
            state.error?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            PullToRefreshBox(
                isRefreshing = state.isSyncing,
                onRefresh = viewModel::sync,
                modifier = Modifier.fillMaxSize()
            ) {
                if (state.parcels.isEmpty()) {
                    if (state.isSyncing) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                    } else {
                        EmptyState(
                            title = if (state.showArchived) "No archived parcels" else "No parcels yet",
                            message = if (state.showArchived) "Archived parcels show up here" else "Add a tracking number to start following a parcel"
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.parcels, key = { it.id }) { parcel ->
                            ParcelListItem(parcel = parcel, onClick = { onParcelClick(parcel.id) })
                        }
                    }
                }
            }
        }
    }
}
