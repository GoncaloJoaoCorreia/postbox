package com.postbox.mobile.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.postbox.shared.DependenciesProvider
import com.postbox.shared.api.SessionExpiredException
import kotlinx.coroutines.CancellationException

class ParcelSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dependencies = (applicationContext as? DependenciesProvider)?.dependencies ?: return Result.failure()
        if (!dependencies.syncSettings.backgroundPollingEnabled()) return Result.success()
        if (dependencies.sessionStore.baseUrl() == null || !dependencies.authRepository().hasSession()) return Result.success()
        return try {
            dependencies.syncParcelsAndNotify()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            Result.failure()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
