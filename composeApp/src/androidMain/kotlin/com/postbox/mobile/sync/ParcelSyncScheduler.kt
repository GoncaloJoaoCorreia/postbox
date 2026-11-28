package com.postbox.mobile.sync

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.setExpedited
import java.util.concurrent.TimeUnit

object ParcelSyncScheduler {
    private const val PERIODIC_WORK_NAME = "postbox_parcel_sync"
    private const val NOW_WORK_NAME = "postbox_parcel_sync_now"
    private const val PERIODIC_INTERVAL_MINUTES = 15L

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<ParcelSyncWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    fun syncNow(context: Context) {
        val builder = OneTimeWorkRequestBuilder<ParcelSyncWorker>()
            .setConstraints(networkConstraints())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            builder.build()
        )
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
