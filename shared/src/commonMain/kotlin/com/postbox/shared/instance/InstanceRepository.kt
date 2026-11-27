package com.postbox.shared.instance

import com.postbox.shared.api.PostboxApi
import com.postbox.shared.storage.InstanceSessionStore
import kotlinx.coroutines.CancellationException

sealed interface InstanceProbeResult {
    data class Connected(val setupPending: Boolean) : InstanceProbeResult
    data object InvalidUrl : InstanceProbeResult
    data class Unreachable(val message: String) : InstanceProbeResult
}

class InstanceRepository(
    private val sessionStore: InstanceSessionStore,
    private val apiFactory: (String) -> PostboxApi
) {
    fun currentBaseUrl(): String? = sessionStore.baseUrl()

    suspend fun connect(rawUrl: String): InstanceProbeResult {
        val normalized = InstanceUrl.normalize(rawUrl) ?: return InstanceProbeResult.InvalidUrl
        val api = apiFactory(normalized)
        return try {
            api.healthz()
            val setupPending = api.setupStatus()
            sessionStore.save(normalized)
            InstanceProbeResult.Connected(setupPending)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            InstanceProbeResult.Unreachable(e.message ?: "Could not reach the server")
        } finally {
            api.close()
        }
    }
}
