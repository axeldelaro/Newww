package com.kinetix.coach.ui.tracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kinetix.coach.KinetixApp
import com.kinetix.coach.data.AthleteProfile
import com.kinetix.coach.data.WorkoutSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

data class OneRmBreakdown(
    val epleyKg: Float,
    val brzyckiKg: Float,
    val estimated1RmKg: Float,
    val loadTable: List<LoadZoneRow>
)

data class LoadZoneRow(
    val pct: Int,
    val targetReps: String,
    val weightKg: Float,
    val zoneFr: String
)

data class MacroPlan(
    val labelFr: String,
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int
)

data class TdeeBreakdown(
    val bmrKcal: Int,
    val tdeeKcal: Int,
    val cutPlan: MacroPlan,
    val maintenancePlan: MacroPlan,
    val bulkPlan: MacroPlan
)

object AthleticCalculators {

    fun calculateOneRm(weightKg: Float, reps: Int): OneRmBreakdown {
        val w = weightKg.coerceIn(1f, 500f)
        val r = reps.coerceIn(1, 20)
        val epley = if (r == 1) w else w * (1f + r / 30f)
        val brzycki = if (r == 1) w else w * (36f / (37f - r).coerceAtLeast(1f))
        val avg = if (r == 1) w else (epley + brzycki) / 2f

        val zones = listOf(
            Triple(95, "2 reps", "Force max"),
            Triple(90, "3–4 reps", "Force"),
            Triple(85, "5–6 reps", "Force / Hypertrophie"),
            Triple(80, "7–8 reps", "Hypertrophie lourde"),
            Triple(75, "9–10 reps", "Hypertrophie standard"),
            Triple(70, "11–12 reps", "Hypertrophie volume"),
            Triple(65, "14–16 reps", "Endurance musculaire")
        ).map { (pct, repStr, zone) ->
            val raw = avg * (pct / 100f)
            val roundedHalfKg = (raw * 2f).roundToInt() / 2f
            LoadZoneRow(pct = pct, targetReps = repStr, weightKg = roundedHalfKg, zoneFr = zone)
        }

        return OneRmBreakdown(
            epleyKg = (epley * 10f).roundToInt() / 10f,
            brzyckiKg = (brzycki * 10f).roundToInt() / 10f,
            estimated1RmKg = (avg * 10f).roundToInt() / 10f,
            loadTable = zones
        )
    }

    fun calculateTdee(profile: AthleteProfile): TdeeBreakdown {
        val w = profile.weightKg.coerceIn(35f, 220f)
        val h = profile.heightCm.coerceIn(120, 230)
        val a = profile.age.coerceIn(13, 95)
        val sexOffset = if (profile.sex.equals("Femme", ignoreCase = true)) -161f else 5f
        val bmr = (10f * w + 6.25f * h - 5f * a + sexOffset).roundToInt().coerceAtLeast(1000)
        val tdee = (bmr * profile.activityFactor.coerceIn(1.2f, 2.0f)).roundToInt()

        fun buildPlan(label: String, kcalTarget: Int, proteinPerKg: Float, fatPerKg: Float): MacroPlan {
            val safeKcal = kcalTarget.coerceAtLeast(1200)
            val pG = (w * proteinPerKg).roundToInt()
            val fG = (w * fatPerKg).roundToInt()
            val remainingKcal = (safeKcal - pG * 4 - fG * 9).coerceAtLeast(120)
            val cG = (remainingKcal / 4f).roundToInt()
            return MacroPlan(labelFr = label, kcal = safeKcal, proteinG = pG, carbsG = cG, fatG = fG)
        }

        return TdeeBreakdown(
            bmrKcal = bmr,
            tdeeKcal = tdee,
            cutPlan = buildPlan("Sèche (-15%)", (tdee * 0.85f).roundToInt(), 2.2f, 0.8f),
            maintenancePlan = buildPlan("Maintien", tdee, 1.9f, 0.9f),
            bulkPlan = buildPlan("Prise de masse (+12%)", (tdee * 1.12f).roundToInt(), 1.8f, 0.95f)
        )
    }
}

class TrackerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KinetixApp

    val sessions: StateFlow<List<WorkoutSession>> = app.userDb.sessions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val profile: StateFlow<AthleteProfile> = app.userDb.profile
        .stateIn(viewModelScope, SharingStarted.Eagerly, AthleteProfile())

    private val _profileSavedFeedback = MutableStateFlow(false)
    val profileSavedFeedback: StateFlow<Boolean> = _profileSavedFeedback.asStateFlow()

    fun saveProfile(updated: AthleteProfile) {
        app.userDb.saveProfile(updated)
        _profileSavedFeedback.value = true
    }

    fun clearProfileSavedFeedback() {
        _profileSavedFeedback.update { false }
    }

    fun deleteSession(sessionId: Long) {
        app.userDb.deleteSession(sessionId)
    }
}
