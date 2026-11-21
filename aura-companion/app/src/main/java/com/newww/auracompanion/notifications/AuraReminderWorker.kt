package com.newww.auracompanion.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.newww.auracompanion.MainActivity
import com.newww.auracompanion.R
import com.newww.auracompanion.data.AuraRepository
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class AuraReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val repo = AuraRepository(applicationContext)
        val state = repo.load()
        if (!state.remindersEnabled) return Result.success()

        val ch = state.activeCharacter
        val hour = LocalTime.now().hour
        val msg = when (hour) {
            in 8..10 -> "Bonjour ${ch.userNickname} ! Je pense à toi, passe une belle matinée ✨"
            in 12..14 -> "Coucou ${ch.userNickname}, tu as pensé à faire une vraie pause ? ☕"
            in 19..21 -> "Je suis là si tu veux me raconter ta journée en appel vocal 💖"
            else -> null
        }

        if (msg != null) {
            postNotification(applicationContext, ch.name, msg)
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "aura_daily_messages"
        private const val WORK_NAME = "aura_periodic_reminders"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<AuraReminderWorker>(4, TimeUnit.HOURS)
                .setInitialDelay(2, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        private fun postNotification(context: Context, title: String, text: String) {
            if (Build.VERSION.SDK_INT >= 33) {
                val ok = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!ok) return
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Messages doux d'Aura", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                31,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_aura)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(3001, notif) }
        }
    }
}
