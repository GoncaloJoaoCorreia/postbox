package com.postbox.shared.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.postbox.shared.R
import com.postbox.shared.storage.PlatformContext
import com.postbox.shared.sync.ParcelStatusChange

private const val CHANNEL_ID = "parcel_status_updates"

actual fun createStatusChangeNotifier(context: PlatformContext): StatusChangeNotifier =
    object : StatusChangeNotifier {
        override fun notifyStatusChanges(changes: List<ParcelStatusChange>) {
            if (changes.isEmpty()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_parcel_updates),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
            changes.forEach { change ->
                val intent = Intent(launchIntent).apply {
                    putExtra(EXTRA_PARCEL_ID, change.parcel.id)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    notificationId(change),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val statusText = change.newStatus.name.replace('_', ' ')
                val notification = Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle(context.getString(R.string.notification_status_changed, change.parcel.displayName, statusText))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()
                manager.notify(notificationId(change), notification)
            }
        }

        private fun notificationId(change: ParcelStatusChange): Int =
            (change.parcel.id + ":" + change.newStatus.name).hashCode()
    }
