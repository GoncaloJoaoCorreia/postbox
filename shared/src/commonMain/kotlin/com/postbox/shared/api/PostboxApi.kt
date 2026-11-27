package com.postbox.shared.api

import com.postbox.shared.api.endpoints.AuthApi
import com.postbox.shared.api.endpoints.DocsApi
import com.postbox.shared.api.endpoints.MeApi
import com.postbox.shared.api.endpoints.ParcelsApi
import com.postbox.shared.api.endpoints.SetupApi
import com.postbox.shared.api.infrastructure.HttpResponse
import com.postbox.shared.api.model.ChangePasswordRequest
import com.postbox.shared.api.model.ErrorResponse
import com.postbox.shared.api.model.LoginRequest
import com.postbox.shared.api.model.RefreshRequest
import com.postbox.shared.api.model.SetupRequest
import com.postbox.shared.domain.model.DeviceSessionInfo
import com.postbox.shared.domain.model.Parcel
import com.postbox.shared.domain.model.ParcelEvent
import com.postbox.shared.domain.model.Session
import com.postbox.shared.domain.model.UserAccount
import com.postbox.shared.storage.TokenStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class PostboxApi(
    baseUrl: String,
    private val tokenStorage: TokenStorage,
    engine: HttpClientEngine = defaultHttpClientEngine()
) : ParcelsRemoteDataSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
        defaultRequest {
            tokenStorage.accessToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
    }
    private val base = baseUrl.trimEnd('/')
    private val parcelsApi = ParcelsApi(base, httpClient)
    private val authApi = AuthApi(base, httpClient)
    private val setupApi = SetupApi(base, httpClient)
    private val meApi = MeApi(base, httpClient)
    private val docsApi = DocsApi(base, httpClient)
    private val refreshMutex = Mutex()

    override suspend fun listParcels(): List<Parcel> =
        callAuthed { parcelsApi.listParcels() }.map { it.toDomain() }

    override suspend fun getParcel(id: String): Parcel =
        callAuthed { parcelsApi.getParcel(id) }.toDomain()

    override suspend fun listParcelEvents(id: String): List<ParcelEvent> =
        callAuthed { parcelsApi.listParcelEvents(id) }.map { it.toDomain() }

    override suspend fun refreshParcel(id: String): Parcel =
        callAuthed { parcelsApi.refreshParcel(id) }.toDomain()

    suspend fun healthz(): String =
        callPublic { docsApi.getHealthz() }.status

    suspend fun setupStatus(): Boolean =
        callPublic { setupApi.getSetupStatus() }.setupPending

    suspend fun setup(email: String, password: String): UserAccount =
        callPublic { setupApi.postSetup(SetupRequest(email = email, password = password)) }.toDomain()

    suspend fun login(email: String, password: String, deviceName: String? = null): Session =
        callPublic { authApi.postLogin(LoginRequest(email = email, password = password, deviceName = deviceName)) }
            .toSession()

    suspend fun refresh(refreshToken: String): Session =
        callPublic { authApi.postRefresh(RefreshRequest(refreshToken = refreshToken)) }.toSession()

    suspend fun me(): UserAccount =
        callAuthed { meApi.getMe() }.toDomain()

    suspend fun changePassword(newPassword: String): Unit =
        callAuthed { meApi.patchMe(ChangePasswordRequest(newPassword = newPassword)) }

    suspend fun listDevices(): List<DeviceSessionInfo> =
        callAuthed { meApi.listDeviceSessions() }.map { it.toDomain() }

    suspend fun revokeDevice(id: String): Unit =
        callAuthed { meApi.revokeDeviceSession(id) }

    fun close() {
        httpClient.close()
    }

    private suspend fun <T : Any> callPublic(block: suspend () -> HttpResponse<T>): T {
        val response = block()
        if (!response.success) throw response.toApiException()
        return response.body()
    }

    private suspend fun <T : Any> callAuthed(block: suspend () -> HttpResponse<T>): T {
        val response = block()
        if (response.success) return response.body()
        if (response.status == 401) {
            val hadSession = tokenStorage.refreshToken() != null
            if (hadSession && refreshSilently()) {
                val retry = block()
                if (retry.success) return retry.body()
                throw retry.toApiException()
            }
            throw if (hadSession) SessionExpiredException() else response.toApiException()
        }
        throw response.toApiException()
    }

    private suspend fun refreshSilently(): Boolean = refreshMutex.withLock {
        val refreshToken = tokenStorage.refreshToken() ?: return@withLock false
        val response = authApi.postRefresh(RefreshRequest(refreshToken = refreshToken))
        if (!response.success) {
            if (response.status == 401) tokenStorage.clear()
            return@withLock false
        }
        val session = response.body().toSession()
        tokenStorage.save(session.accessToken, session.refreshToken, session.sessionId)
        true
    }

    private suspend fun <T : Any> HttpResponse<T>.toApiException(): ApiException {
        val text = response.bodyAsText()
        val envelope = runCatching { json.decodeFromString<ErrorResponse>(text) }.getOrNull()
        val error = envelope?.error
        return ApiException(
            code = error?.code ?: "http_$status",
            message = error?.message ?: text.ifBlank { "HTTP $status" },
            status = status,
            fields = error?.fields?.map { it.toDomain() }
        )
    }
}
