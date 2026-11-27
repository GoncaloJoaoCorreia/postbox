package com.postbox.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.runtime.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.postbox.shared.api.ApiException
import com.postbox.shared.api.PostboxApi
import com.postbox.shared.auth.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false
)

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val deviceName: String
) : ViewModel() {
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onEmailChange(email: String) {
        _uiState.update { it.copy(email = email, error = null) }
    }

    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password, error = null) }
    }

    fun onSubmit() {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank() || state.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                authRepository.login(state.email.trim(), state.password, deviceName)
                _uiState.update { it.copy(isLoading = false, loggedIn = true) }
            } catch (e: ApiException) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Could not reach the server: ${e.message}") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(viewModel: LoginViewModel, onLoggedIn: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) onLoggedIn()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Log in") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = viewModel::onSubmit,
                enabled = state.email.isNotBlank() && state.password.isNotBlank() && !state.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isLoading) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text(if (state.isLoading) "Logging in…" else "Log in")
            }
        }
    }
}

data class FirstRunSetupUiState(
    val email: String = "",
    val password: String = "",
    val confirm: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val setupDone: Boolean = false
)

class FirstRunSetupViewModel(private val api: PostboxApi) : ViewModel() {
    private val _uiState = MutableStateFlow(FirstRunSetupUiState())
    val uiState: StateFlow<FirstRunSetupUiState> = _uiState.asStateFlow()

    fun onEmailChange(email: String) {
        _uiState.update { it.copy(email = email, error = null) }
    }

    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password, error = null) }
    }

    fun onConfirmChange(confirm: String) {
        _uiState.update { it.copy(confirm = confirm, error = null) }
    }

    fun onSubmit() {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.length < 8 || state.password != state.confirm || state.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                api.setup(state.email.trim(), state.password)
                _uiState.update { it.copy(isLoading = false, setupDone = true) }
            } catch (e: ApiException) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Could not reach the server: ${e.message}") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstRunSetupScreen(viewModel: FirstRunSetupViewModel, onSetupDone: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.setupDone) {
        if (state.setupDone) onSetupDone()
    }

    val passwordsMatch = state.password == state.confirm
    val canSubmit = state.email.isNotBlank() && state.password.length >= 8 && passwordsMatch && !state.isLoading

    Scaffold(
        topBar = { TopAppBar(title = { Text("Set up your server") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "This server has no account yet. Create the admin account to finish the first-run setup.",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                label = { Text("Admin email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("Password (min. 8 characters)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.confirm,
                onValueChange = viewModel::onConfirmChange,
                label = { Text("Repeat password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            if (state.password.isNotEmpty() && !passwordsMatch) {
                Text("Passwords do not match", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = viewModel::onSubmit,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isLoading) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text(if (state.isLoading) "Creating account…" else "Create admin account")
            }
        }
    }
}
