package com.newww.luminaterrarium.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.widget.RemoteViews
import com.newww.luminaterrarium.MainActivity
import com.newww.luminaterrarium.R
import com.newww.luminaterrarium.data.TerrariumRepository
import com.newww.luminaterrarium.engine.TerrariumSimulationEngine
import com.newww.luminaterrarium.model.BatterySnapshot
import com.newww.luminaterrarium.model.DayPhase
import com.newww.luminaterrarium.model.FoodType
import com.newww.luminaterrarium.model.SpiritElement
import com.newww.luminaterrarium.model.SpiritProfile

class TerrariumWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAllWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action == ACTION_PET || action == ACTION_FEED || action == ACTION_MIST) {
            val repo = TerrariumRepository(context)
            var state = TerrariumSimulationEngine.applyTimeTick(repo.load(), BatterySnapshot())
            state = when (action) {
                ACTION_PET -> TerrariumSimulationEngine.petSpirit(state, BatterySnapshot())
                ACTION_FEED -> TerrariumSimulationEngine.feedSpirit(
                    state,
                    state.activeSpirit.element.favoriteFood
                )
                ACTION_MIST -> TerrariumSimulationEngine.mistTerrarium(state)
                else -> state
            }
            repo.save(state)
            refreshWidgets(context)
        }
    }

    companion object {
        const val ACTION_PET = "com.newww.luminaterrarium.ACTION_WIDGET_PET"
        const val ACTION_FEED = "com.newww.luminaterrarium.ACTION_WIDGET_FEED"
        const val ACTION_MIST = "com.newww.luminaterrarium.ACTION_WIDGET_MIST"

        fun refreshWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, TerrariumWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) {
                updateAllWidgets(context, manager, ids)
            }
        }

        private fun updateAllWidgets(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray
        ) {
            val repo = TerrariumRepository(context)
            val state = TerrariumSimulationEngine.applyTimeTick(repo.load(), BatterySnapshot())
            repo.save(state)
            val spirit = state.activeSpirit
            val dayPhase = DayPhase.fromHour(TerrariumSimulationEngine.currentHour())
            val mood = TerrariumSimulationEngine.computeMood(spirit, BatterySnapshot(), dayPhase)
            val bitmap = renderMiniTerrariumBitmap(spirit, dayPhase)

            for (widgetId in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_terrarium)
                views.setImageViewBitmap(R.id.widget_terrarium_art, bitmap)
                views.setTextViewText(
                    R.id.widget_spirit_name,
                    "${spirit.name} • ${spirit.stage.labelFr}"
                )
                views.setTextViewText(
                    R.id.widget_spirit_status,
                    "${mood.emoji} ${mood.labelFr} — ${spirit.lastSpeech}"
                )
                views.setTextViewText(
                    R.id.widget_vitals,
                    "Faim ${spirit.hunger.toInt()}% • Eau ${spirit.hydration.toInt()}% • Joie ${spirit.happiness.toInt()}%"
                )

                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val openPi = PendingIntent.getActivity(
                    context,
                    0,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, openPi)
                views.setOnClickPendingIntent(R.id.widget_btn_pet, actionPendingIntent(context, ACTION_PET, 1))
                views.setOnClickPendingIntent(R.id.widget_btn_feed, actionPendingIntent(context, ACTION_FEED, 2))
                views.setOnClickPendingIntent(R.id.widget_btn_mist, actionPendingIntent(context, ACTION_MIST, 3))

                manager.updateAppWidget(widgetId, views)
            }
        }

        private fun actionPendingIntent(context: Context, action: String, reqCode: Int): PendingIntent {
            val intent = Intent(context, TerrariumWidgetProvider::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context,
                reqCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun renderMiniTerrariumBitmap(spirit: SpiritProfile, dayPhase: DayPhase): Bitmap {
            val size = 220
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Subtle halo
            paint.color = (spirit.customAuraHex.toInt() and 0x00FFFFFF) or 0x38000000
            c.drawCircle(size / 2f, size * 0.56f, size * 0.42f, paint)

            // Cork lid
            paint.color = Color.parseColor("#A46D43")
            c.drawRoundRect(RectF(74f, 18f, 146f, 44f), 10f, 10f, paint)

            // Glass body
            paint.color = if (dayPhase == DayPhase.NIGHT) Color.parseColor("#4414283A") else Color.parseColor("#4480EED6")
            c.drawRoundRect(RectF(30f, 42f, 190f, 202f), 64f, 64f, paint)

            // Ground
            paint.color = spirit.element.groundColorHex.toInt()
            c.drawArc(RectF(36f, 134f, 184f, 198f), 0f, 180f, true, paint)

            // Spirit body
            val cx = size / 2f
            val cy = size * 0.58f
            paint.color = spirit.customAuraHex.toInt()
            when (spirit.element) {
                SpiritElement.CLOUD -> {
                    c.drawCircle(cx - 20f, cy + 4f, 22f, paint)
                    c.drawCircle(cx + 20f, cy + 4f, 22f, paint)
                    c.drawCircle(cx, cy - 8f, 28f, paint)
                }
                SpiritElement.EMBER -> {
                    val path = Path().apply {
                        moveTo(cx, cy - 42f)
                        quadTo(cx + 42f, cy - 2f, cx + 24f, cy + 28f)
                        quadTo(cx, cy + 42f, cx - 24f, cy + 28f)
                        quadTo(cx - 42f, cy - 2f, cx, cy - 42f)
                    }
                    c.drawPath(path, paint)
                }
                else -> {
                    c.drawOval(RectF(cx - 34f, cy - 28f, cx + 34f, cy + 30f), paint)
                }
            }

            // Eyes
            paint.color = Color.parseColor("#112220")
            if (spirit.isSleeping) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                c.drawArc(RectF(cx - 20f, cy - 6f, cx - 6f, cy + 6f), 0f, 180f, false, paint)
                c.drawArc(RectF(cx + 6f, cy - 6f, cx + 20f, cy + 6f), 0f, 180f, false, paint)
                paint.style = Paint.Style.FILL
            } else {
                c.drawCircle(cx - 13f, cy - 2f, 5.5f, paint)
                c.drawCircle(cx + 13f, cy - 2f, 5.5f, paint)
                paint.color = Color.WHITE
                c.drawCircle(cx - 15f, cy - 4f, 2f, paint)
                c.drawCircle(cx + 11f, cy - 4f, 2f, paint)
            }

            // Glass outline & shine
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            paint.color = Color.parseColor("#A8E6FFF5")
            c.drawRoundRect(RectF(30f, 42f, 190f, 202f), 64f, 64f, paint)

            return bmp
        }
    }
}
