package com.postbox.shared.api

import com.postbox.shared.api.endpoints.ParcelsApi
import com.postbox.shared.api.infrastructure.HttpResponse
import com.postbox.shared.api.model.ErrorResponse
import com.postbox.shared.api.model.Parcel
import com.postbox.shared.api.model.ParcelEvent
import com.postbox.shared.storage.TokenStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class PostboxApi(
    baseUrl: String,
    private val tokenStorage: TokenStorage,
    engine: HttpClientEngine
) : ParcelsRemoteDataSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
    }
    private val parcelsApi = ParcelsApi(baseUrl.trimEnd('/'), httpClient)

    override suspend fun listParcels(): List<Parcel> = call { parcelsApi.listParcels() }

    override suspend fun getParcel(id: String): Parcel = call { parcelsApi.getParcel(id) }

    override suspend fun listParcelEvents(id: String): List<ParcelEvent> = call { parcelsApi.listParcelEvents(id) }

    override suspend fun refreshParcel(id: String): Parcel = call { parcelsApi.refreshParcel(id) }

    fun close() {
        httpClient.close()
    }

    private suspend fun <T : Any> call(block: suspend () -> HttpResponse<T>): T {
        tokenStorage.accessToken()?.let { parcelsApi.setBearerToken(it) }
        val response = block()
        if (!response.success) throw response.toApiException()
        return response.body()
    }

    private suspend fun <T : Any> HttpResponse<T>.toApiException(): ApiException {
        val text = response.bodyAsText()
        val envelope = runCatching { json.decodeFromString<ErrorResponse>(text) }.getOrNull()
        val error = envelope?.error
        return ApiException(
            code = error?.code ?: "http_$status",
            message = error?.message ?: text.ifBlank { "HTTP $status" },
            status = status,
            fields = error?.fields
        )
    }
}
