package com.newww.stepquest.notifications

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
import com.newww.stepquest.MainActivity
import com.newww.stepquest.R
import com.newww.stepquest.data.StepQuestRepository
import com.newww.stepquest.engine.ExpeditionEngine
import java.util.concurrent.TimeUnit

class ExpeditionReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val repo = StepQuestRepository(applicationContext)
        val state = repo.load()
        if (!state.remindersEnabled) return Result.success()

        val hour = ExpeditionEngine.currentHour()
        val today = ExpeditionEngine.dayKey()
        val msg = when {
            hour in 7..10 && state.morningPackDoneDayKey != today ->
                "${state.adventurerName} attend que tu prépares son sac pour l'expédition du jour 🎒"
            hour in 12..14 && state.middayMealDoneDayKey != today ->
                "Pause de midi ! Offre des provisions à ${state.adventurerName} sur le sentier 🥖"
            hour in 19..21 && state.eveningLetterDayKey != today ->
                "Le feu de camp crépite… viens lire la lettre d'expédition de ${state.adventurerName} 📜"
            else -> null
        }

        if (msg != null) {
            postNotification(applicationContext, "StepQuest • ${state.adventurerName}", msg)
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "stepquest_reminders"
        private const val WORK_NAME = "stepquest_daily_reminders"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<ExpeditionReminderWorker>(3, TimeUnit.HOURS)
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
                val ok = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!ok) return
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Journal d'Expédition", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                21,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_stepquest)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(2001, notif) }
        }
    }
}
