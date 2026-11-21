package com.newww.luminaterrarium.notifications

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
import com.newww.luminaterrarium.MainActivity
import com.newww.luminaterrarium.R
import com.newww.luminaterrarium.data.TerrariumRepository
import com.newww.luminaterrarium.engine.TerrariumSimulationEngine
import com.newww.luminaterrarium.model.BatterySnapshot
import com.newww.luminaterrarium.model.DayPhase
import com.newww.luminaterrarium.widget.TerrariumWidgetProvider
import java.util.concurrent.TimeUnit

class CareReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val repo = TerrariumRepository(applicationContext)
        val state = TerrariumSimulationEngine.applyTimeTick(repo.load(), BatterySnapshot())
        repo.save(state)
        TerrariumWidgetProvider.refreshWidgets(applicationContext)

        if (!state.remindersEnabled) return Result.success()

        val spirit = state.activeSpirit
        val hour = TerrariumSimulationEngine.currentHour()
        val message = when {
            spirit.hunger < 38f -> "${spirit.name} a un petit creux dans son bocal 🍓"
            spirit.hydration < 38f -> "Le bocal de ${spirit.name} a besoin d'une petite brume fraîche 💧"
            hour in 21..22 -> "${spirit.name} allume sa lanterne pour te souhaiter une douce nuit 🌙"
            hour in 8..9 -> "${spirit.name} s'étire au soleil du matin ! Viens lui dire bonjour ✨"
            else -> null
        }

        if (message != null) {
            postNotification(applicationContext, spirit.name, message)
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "lumina_care_reminders"
        private const val WORK_NAME = "lumina_periodic_care_work"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<CareReminderWorker>(3, TimeUnit.HOURS)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        private fun postNotification(context: Context, title: String, text: String) {
            if (Build.VERSION.SDK_INT >= 33) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rappels doux du Terrarium",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications locales quand ton esprit réclame une caresse ou de la brume"
            }
            nm.createNotificationChannel(channel)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                10,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_terrarium)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()

            runCatching {
                NotificationManagerCompat.from(context).notify(1001, notif)
            }
        }
    }
}
