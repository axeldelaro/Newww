package com.kinetix.coach.vision

import com.kinetix.coach.data.MovementPhase
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Pure-Kotlin deterministic state machine driving all 7 camera-verified KINETIX Challenges:
 * 1. Pompes « Bip & Go » (cadence Descends / Tiens / GO synced to camera)
 * 2. Test Luc Léger (12 progressive stages 3.6s -> 1.1s/rep)
 * 3. Gainage Chrono Musical (timer + music active only on valid plank hold + grace window)
 * 4. Défi 50 / 100 Reps (fastest-time race with live pace)
 * 5. AMRAP Chrono (1 / 2 / 3 min max reps)
 * 6. Tabata Officiel (20s Work / 10s Rest x 8 with auto music ducking)
 * 7. EMOM Cadence (5 / 10 min with auto earned-rest on minute target completion)
 */
class ChallengeController {

    companion object {
        const val COUNTDOWN_SECONDS = 3
        const val PLANK_GRACE_SEC = 2.5f
        const val MAX_CONSECUTIVE_MISSES = 2

        /** Luc Léger stage cadence in seconds per rep: Stage 1 = 3.60s -> Stage 12 = 1.10s. */
        fun lucLegerCadenceSec(stage: Int): Float {
            val s = stage.coerceIn(1, 12)
            return (3.6f - (s - 1) * 0.25f).coerceAtLeast(1.1f)
        }
    }

    private var config: ChallengeConfig? = null
    private var phase: ChallengePhase = ChallengePhase.IDLE
    private var lastUpdateMs: Long = 0L

    private var countdownElapsedSec: Float = 0f
    private var lastCountdownBeepInt: Int = 4

    private var activeElapsedSec: Float = 0f
    private var phaseElapsedSec: Float = 0f
    private var validHoldSec: Float = 0f
    private var postureBreakSec: Float = 0f
    private var lastPostureWarnAtSec: Float = -10f
    private var sprintAnnounced: Boolean = false
    private var lastSecBeepMark: Int = -1

    private var totalReps: Int = 0
    private var syncedCycles: Int = 0
    private var roundReps: Int = 0
    private var completedEmomMinutes: Int = 0
    private var currentStage: Int = 1
    private var missedCount: Int = 0
    private var consecutiveMissed: Int = 0
    private var syncStateFr: String? = null

    private var cycleDescended: Boolean = false
    private var cycleRepValidated: Boolean = false
    private var lastHoldBeepSec: Int = 0

    private var lastAvgFormScore: Int = 100
    private var lastCaloriesKcal: Double = 0.0

    val isActive: Boolean
        get() = config != null &&
            phase != ChallengePhase.IDLE &&
            phase != ChallengePhase.COMPLETED &&
            phase != ChallengePhase.FAILED

    fun activeConfig(): ChallengeConfig? = config

    fun start(newConfig: ChallengeConfig, nowMs: Long): ChallengeOutput {
        config = newConfig
        phase = ChallengePhase.COUNTDOWN
        lastUpdateMs = nowMs

        countdownElapsedSec = 0f
        lastCountdownBeepInt = COUNTDOWN_SECONDS

        activeElapsedSec = 0f
        phaseElapsedSec = 0f
        validHoldSec = 0f
        postureBreakSec = 0f
        lastPostureWarnAtSec = -10f
        sprintAnnounced = false
        lastSecBeepMark = -1

        totalReps = 0
        syncedCycles = 0
        roundReps = 0
        completedEmomMinutes = 0
        currentStage = 1
        missedCount = 0
        consecutiveMissed = 0
        syncStateFr = "PRÉPAREZ-VOUS"
        cycleDescended = false
        cycleRepValidated = false
        lastHoldBeepSec = 0
        lastAvgFormScore = 100
        lastCaloriesKcal = 0.0

        return ChallengeOutput(
            snapshot = buildSnapshot(newConfig),
            beepsToPlay = listOf(BeepType.COUNTDOWN),
            voiceAnnouncementFr = "Préparez-vous, 3",
            musicShouldPlay = false,
            musicDucked = false,
            musicFinalSprint = false
        )
    }

    fun stopAndReset() {
        config = null
        phase = ChallengePhase.IDLE
        lastUpdateMs = 0L
    }

    fun finishEarly(nowMs: Long, sessionCaloriesKcal: Double = lastCaloriesKcal): ChallengeOutput? {
        val cfg = config ?: return null
        if (phase == ChallengePhase.IDLE || phase == ChallengePhase.COMPLETED || phase == ChallengePhase.FAILED) {
            return null
        }
        lastCaloriesKcal = sessionCaloriesKcal
        val completedGoal = when (cfg.type) {
            ChallengeType.BIP_AND_GO_PUSHUPS -> cfg.bipGoTargetCycles <= 0 && syncedCycles > 0
            ChallengeType.PLANK_MUSIC_HOLD -> cfg.plankTargetSec <= 0 && validHoldSec >= 3f
            ChallengeType.TARGET_REPS_RACE -> totalReps >= cfg.targetRepsGoal
            else -> false
        }
        phase = if (completedGoal || currentScoreValue(cfg) > 0.0) ChallengePhase.COMPLETED else ChallengePhase.FAILED
        val res = buildResult(cfg, completedGoal)
        return ChallengeOutput(
            snapshot = buildSnapshot(cfg),
            beepsToPlay = if (completedGoal) listOf(BeepType.COMPLETE) else emptyList(),
            voiceAnnouncementFr = null,
            musicShouldPlay = false,
            completedResult = res
        )
    }

    /**
     * Advances the active challenge state machine using the latest camera [frame] (or null on timer tick)
     * at monotonic timestamp [nowMs].
     */
    fun onFrame(
        frame: BiomechanicsFrame?,
        nowMs: Long,
        sessionCaloriesKcal: Double = lastCaloriesKcal
    ): ChallengeOutput? {
        val cfg = config ?: return null
        if (phase == ChallengePhase.IDLE || phase == ChallengePhase.COMPLETED || phase == ChallengePhase.FAILED) {
            return null
        }

        val dtSec = if (lastUpdateMs > 0L && nowMs > lastUpdateMs) {
            ((nowMs - lastUpdateMs) / 1000f).coerceIn(0f, 1.5f)
        } else {
            0f
        }
        lastUpdateMs = nowMs
        lastCaloriesKcal = sessionCaloriesKcal
        if (frame != null && frame.poseDetected) {
            lastAvgFormScore = frame.avgFormScore
        }

        val beeps = mutableListOf<BeepType>()
        var voice: String? = null
        var musicShouldPlay = false
        var musicDucked = false
        var musicSprint = false
        var result: ChallengeResult? = null

        // 1. Universal 3-2-1-GO Countdown
        if (phase == ChallengePhase.COUNTDOWN) {
            countdownElapsedSec += dtSec
            val remSec = ceil((COUNTDOWN_SECONDS - countdownElapsedSec).toDouble()).toInt().coerceAtLeast(0)
            if (remSec in 1 until lastCountdownBeepInt) {
                lastCountdownBeepInt = remSec
                beeps.add(BeepType.COUNTDOWN)
                voice = "$remSec"
            }
            if (countdownElapsedSec >= COUNTDOWN_SECONDS) {
                lastSecBeepMark = -1
                when (cfg.type) {
                    ChallengeType.BIP_AND_GO_PUSHUPS -> {
                        phase = ChallengePhase.BIP_DOWN
                        phaseElapsedSec = 0f
                        cycleDescended = false
                        cycleRepValidated = false
                        syncStateFr = "DESCENDS !"
                        beeps.add(BeepType.DOWN)
                        voice = "Descends !"
                    }
                    ChallengeType.LUC_LEGER_CADENCE -> {
                        phase = ChallengePhase.ACTIVE
                        phaseElapsedSec = 0f
                        currentStage = 1
                        roundReps = 0
                        syncStateFr = "PALIER 1"
                        beeps.add(BeepType.GO)
                        voice = "Palier 1, c'est parti !"
                    }
                    ChallengeType.PLANK_MUSIC_HOLD -> {
                        phase = ChallengePhase.HOLDING
                        phaseElapsedSec = 0f
                        validHoldSec = 0f
                        postureBreakSec = 0f
                        syncStateFr = "MAINTIEN"
                        beeps.add(BeepType.GO)
                        voice = "C'est parti, gainez !"
                    }
                    ChallengeType.TABATA_HIIT -> {
                        phase = ChallengePhase.WORK
                        phaseElapsedSec = 0f
                        currentStage = 1
                        roundReps = 0
                        syncStateFr = "EFFORT"
                        beeps.add(BeepType.GO)
                        voice = "Tour 1 sur ${cfg.tabataRounds}, GO !"
                    }
                    ChallengeType.EMOM_CHALLENGE -> {
                        phase = ChallengePhase.WORK
                        phaseElapsedSec = 0f
                        currentStage = 1
                        roundReps = 0
                        syncStateFr = "0/${cfg.emomRepsPerMin}"
                        beeps.add(BeepType.GO)
                        voice = "Minute 1, ${cfg.emomRepsPerMin} reps !"
                    }
                    ChallengeType.TARGET_REPS_RACE, ChallengeType.AMRAP_CHRONO -> {
                        phase = ChallengePhase.ACTIVE
                        phaseElapsedSec = 0f
                        syncStateFr = "GO !"
                        beeps.add(BeepType.GO)
                        voice = "C'est parti !"
                    }
                }
                musicShouldPlay = cfg.musicTrack != WorkoutMusicTrack.NONE
            }
            return ChallengeOutput(
                snapshot = buildSnapshot(cfg),
                beepsToPlay = beeps,
                voiceAnnouncementFr = voice,
                musicShouldPlay = musicShouldPlay,
                musicDucked = musicDucked,
                musicFinalSprint = musicSprint
            )
        }

        activeElapsedSec += dtSec
        phaseElapsedSec += dtSec
        val repNow = frame?.repCompletedNow == true

        when (cfg.type) {
            ChallengeType.BIP_AND_GO_PUSHUPS -> {
                musicShouldPlay = cfg.musicTrack != WorkoutMusicTrack.NONE
                val tempo = cfg.bipGoTempo
                val inLowerPos = frame != null && frame.poseDetected &&
                    (frame.phase == MovementPhase.BOTTOM || (frame.phase == MovementPhase.ECCENTRIC && frame.romPct >= 45))

                when (phase) {
                    ChallengePhase.BIP_DOWN -> {
                        if (inLowerPos) cycleDescended = true
                        val earlyBottom = frame != null && frame.poseDetected &&
                            frame.phase == MovementPhase.BOTTOM && phaseElapsedSec >= 0.45f
                        if (earlyBottom || phaseElapsedSec >= tempo.downSec) {
                            phase = ChallengePhase.BIP_HOLD
                            phaseElapsedSec = 0f
                            lastHoldBeepSec = 0
                            beeps.add(BeepType.HOLD)
                            syncStateFr = if (cycleDescended) "TIENS BAS ✓" else "DESCENDS !"
                        }
                    }

                    ChallengePhase.BIP_HOLD -> {
                        if (inLowerPos || (frame != null && frame.poseDetected && frame.romPct >= 50)) {
                            cycleDescended = true
                        }
                        val heldSecInt = phaseElapsedSec.toInt()
                        if (heldSecInt > lastHoldBeepSec && phaseElapsedSec < tempo.holdSec) {
                            lastHoldBeepSec = heldSecInt
                            beeps.add(BeepType.HOLD)
                        }
                        if (phaseElapsedSec >= tempo.holdSec) {
                            phase = ChallengePhase.BIP_GO
                            phaseElapsedSec = 0f
                            cycleRepValidated = false
                            beeps.add(BeepType.GO)
                            voice = "GO ! Pousse !"
                            syncStateFr = "POUSSE !"
                        }
                    }

                    ChallengePhase.BIP_GO -> {
                        if (repNow && !cycleRepValidated) {
                            cycleRepValidated = true
                            syncedCycles++
                            totalReps++
                            consecutiveMissed = 0
                            syncStateFr = "SYNCHRO ✓"
                            if (cfg.bipGoTargetCycles > 0 && syncedCycles >= cfg.bipGoTargetCycles) {
                                phase = ChallengePhase.COMPLETED
                                beeps.add(BeepType.COMPLETE)
                                voice = "Défi Bip et Go réussi ! $syncedCycles cycles validés !"
                                result = buildResult(cfg, completedGoal = true)
                            }
                        } else if (cycleRepValidated && phaseElapsedSec >= 0.65f) {
                            phase = ChallengePhase.BIP_DOWN
                            phaseElapsedSec = 0f
                            cycleDescended = false
                            cycleRepValidated = false
                            beeps.add(BeepType.DOWN)
                            syncStateFr = "DESCENDS !"
                        } else if (!cycleRepValidated && phaseElapsedSec >= tempo.upSec) {
                            missedCount++
                            consecutiveMissed++
                            beeps.add(BeepType.WARNING)
                            syncStateFr = "MANQUÉ $consecutiveMissed/$MAX_CONSECUTIVE_MISSES"
                            if (consecutiveMissed >= MAX_CONSECUTIVE_MISSES) {
                                phase = if (syncedCycles > 0) ChallengePhase.COMPLETED else ChallengePhase.FAILED
                                voice = "Cadence perdue. $syncedCycles cycles synchronisés."
                                result = buildResult(cfg, completedGoal = false)
                            } else {
                                phase = ChallengePhase.BIP_DOWN
                                phaseElapsedSec = 0f
                                cycleDescended = false
                                cycleRepValidated = false
                                beeps.add(BeepType.DOWN)
                            }
                        }
                    }

                    else -> Unit
                }
            }

            ChallengeType.LUC_LEGER_CADENCE -> {
                musicShouldPlay = cfg.musicTrack != WorkoutMusicTrack.NONE
                val cadence = lucLegerCadenceSec(currentStage)
                if (repNow) {
                    totalReps++
                    roundReps++
                    syncStateFr = "SYNCHRO ✓"
                }
                musicSprint = currentStage >= 9

                if (phaseElapsedSec >= cadence) {
                    phaseElapsedSec -= cadence
                    if (roundReps >= 1) {
                        syncedCycles++
                        consecutiveMissed = 0
                        roundReps = 0
                        val nextStage = 1 + (syncedCycles / 5).coerceAtMost(11)
                        if (syncedCycles >= 60) {
                            currentStage = 12
                            phase = ChallengePhase.COMPLETED
                            beeps.add(BeepType.COMPLETE)
                            voice = "Test Luc Léger terminé au palier 12 ! $totalReps reps !"
                            result = buildResult(cfg, completedGoal = true)
                        } else if (nextStage > currentStage) {
                            currentStage = nextStage
                            beeps.add(BeepType.GO)
                            voice = "Palier $currentStage !"
                            syncStateFr = "PALIER $currentStage"
                        } else {
                            beeps.add(BeepType.GO)
                        }
                    } else {
                        missedCount++
                        consecutiveMissed++
                        beeps.add(BeepType.WARNING)
                        syncStateFr = "RETARD $consecutiveMissed/$MAX_CONSECUTIVE_MISSES"
                        if (consecutiveMissed >= MAX_CONSECUTIVE_MISSES) {
                            phase = if (totalReps > 0) ChallengePhase.COMPLETED else ChallengePhase.FAILED
                            voice = "Fin du test Luc Léger au palier $currentStage."
                            result = buildResult(cfg, completedGoal = currentStage >= 3)
                        }
                    }
                }
            }

            ChallengeType.PLANK_MUSIC_HOLD -> {
                val isHoldValid = frame != null && frame.poseDetected && frame.phase == MovementPhase.HOLD
                if (isHoldValid) {
                    validHoldSec += dtSec
                    postureBreakSec = 0f
                    lastPostureWarnAtSec = -10f
                    phase = ChallengePhase.HOLDING
                    syncStateFr = "PLANCHE VALIDÉE ✓"
                    musicShouldPlay = cfg.musicTrack != WorkoutMusicTrack.NONE
                    musicDucked = false

                    if (cfg.plankTargetSec > 0) {
                        val rem = (cfg.plankTargetSec - validHoldSec).coerceAtLeast(0f)
                        if (rem <= 15f) {
                            musicSprint = true
                            if (!sprintAnnounced) {
                                sprintAnnounced = true
                                voice = "Dernières 15 secondes, ne lâchez rien !"
                            }
                            val remInt = ceil(rem.toDouble()).toInt()
                            if ((remInt == 10 || remInt == 5 || remInt in 1..3) && remInt != lastSecBeepMark) {
                                lastSecBeepMark = remInt
                                beeps.add(BeepType.COUNTDOWN)
                            }
                        }
                        if (validHoldSec >= cfg.plankTargetSec) {
                            phase = ChallengePhase.COMPLETED
                            beeps.add(BeepType.COMPLETE)
                            voice = "Objectif gainage validé ! ${cfg.plankTargetSec} secondes !"
                            musicShouldPlay = false
                            result = buildResult(cfg, completedGoal = true)
                        }
                    }
                } else {
                    postureBreakSec += dtSec
                    phase = ChallengePhase.PAUSED_POSTURE
                    if (postureBreakSec < PLANK_GRACE_SEC) {
                        musicShouldPlay = cfg.musicTrack != WorkoutMusicTrack.NONE
                        musicDucked = true
                        syncStateFr = "RÉALIGNEZ LES HANCHES…"
                    } else {
                        musicShouldPlay = false
                        musicDucked = true
                        syncStateFr = "CHRONO EN PAUSE"
                        if (postureBreakSec - lastPostureWarnAtSec >= 5.0f) {
                            lastPostureWarnAtSec = postureBreakSec
                            beeps.add(BeepType.WARNING)
                            voice = "Reprenez l'alignement planche"
                        }
                    }
                }
            }

            ChallengeType.TARGET_REPS_RACE -> {
                musicShouldPlay = cfg.musicTrack != WorkoutMusicTrack.NONE
                if (repNow) {
                    totalReps++
                    val left = (cfg.targetRepsGoal - totalReps).coerceAtLeast(0)
                    if (left == 0) {
                        phase = ChallengePhase.COMPLETED
                        beeps.add(BeepType.COMPLETE)
                        voice = "Objectif ${cfg.targetRepsGoal} reps atteint en ${formatChallengeClock(activeElapsedSec.roundToInt())} !"
                        musicShouldPlay = false
                        result = buildResult(cfg, completedGoal = true)
                    } else if (left in 1..3) {
                        beeps.add(BeepType.COUNTDOWN)
                        syncStateFr = "PLUS QUE $left !"
                    } else if (totalReps % 10 == 0) {
                        beeps.add(BeepType.HOLD)
                        voice = "$totalReps, encore $left !"
                        syncStateFr = "$left RESTANTES"
                    } else {
                        syncStateFr = "$left RESTANTES"
                    }
                }
                val remReps = (cfg.targetRepsGoal - totalReps).coerceAtLeast(0)
                musicSprint = remReps <= maxOf(5, cfg.targetRepsGoal / 6) && phase == ChallengePhase.ACTIVE
            }

            ChallengeType.AMRAP_CHRONO -> {
                musicShouldPlay = cfg.musicTrack != WorkoutMusicTrack.NONE
                if (repNow) {
                    totalReps++
                    syncStateFr = "$totalReps REPS"
                }
                val rem = (cfg.amrapDurationSec - activeElapsedSec).coerceAtLeast(0f)
                if (rem <= 15f && rem > 0f) {
                    musicSprint = true
                    if (!sprintAnnounced) {
                        sprintAnnounced = true
                        voice = "Dernières 15 secondes, accélérez !"
                    }
                }
                val remInt = ceil(rem.toDouble()).toInt()
                if (remInt in 1..10 && remInt != lastSecBeepMark) {
                    lastSecBeepMark = remInt
                    beeps.add(BeepType.COUNTDOWN)
                }
                if (rem <= 0f) {
                    phase = ChallengePhase.COMPLETED
                    beeps.add(BeepType.COMPLETE)
                    voice = "Temps écoulé ! $totalReps répétitions validées !"
                    musicShouldPlay = false
                    result = buildResult(cfg, completedGoal = true)
                }
            }

            ChallengeType.TABATA_HIIT -> {
                musicShouldPlay = cfg.musicTrack != WorkoutMusicTrack.NONE
                when (phase) {
                    ChallengePhase.WORK -> {
                        musicDucked = false
                        musicSprint = currentStage == cfg.tabataRounds
                        if (repNow) {
                            totalReps++
                            roundReps++
                            syncStateFr = "TOUR : $roundReps REPS"
                        }
                        val rem = (cfg.tabataWorkSec - phaseElapsedSec).coerceAtLeast(0f)
                        val remInt = ceil(rem.toDouble()).toInt()
                        if (remInt in 1..3 && remInt != lastSecBeepMark) {
                            lastSecBeepMark = remInt
                            beeps.add(BeepType.COUNTDOWN)
                        }
                        if (phaseElapsedSec >= cfg.tabataWorkSec) {
                            lastSecBeepMark = -1
                            if (currentStage >= cfg.tabataRounds) {
                                phase = ChallengePhase.COMPLETED
                                beeps.add(BeepType.COMPLETE)
                                voice = "Tabata terminé ! $totalReps répétitions au total !"
                                musicShouldPlay = false
                                result = buildResult(cfg, completedGoal = true)
                            } else {
                                phase = ChallengePhase.REST
                                phaseElapsedSec = 0f
                                beeps.add(BeepType.DOWN)
                                voice = "Repos ${cfg.tabataRestSec} secondes"
                                syncStateFr = "REPOS"
                                musicDucked = true
                            }
                        }
                    }

                    ChallengePhase.REST -> {
                        musicDucked = true
                        val rem = (cfg.tabataRestSec - phaseElapsedSec).coerceAtLeast(0f)
                        val remInt = ceil(rem.toDouble()).toInt()
                        if (remInt in 1..3 && remInt != lastSecBeepMark) {
                            lastSecBeepMark = remInt
                            beeps.add(BeepType.COUNTDOWN)
                        }
                        if (phaseElapsedSec >= cfg.tabataRestSec) {
                            lastSecBeepMark = -1
                            currentStage++
                            roundReps = 0
                            phase = ChallengePhase.WORK
                            phaseElapsedSec = 0f
                            beeps.add(BeepType.GO)
                            voice = "Tour $currentStage sur ${cfg.tabataRounds}, GO !"
                            syncStateFr = "EFFORT"
                            musicDucked = false
                        }
                    }

                    else -> Unit
                }
            }

            ChallengeType.EMOM_CHALLENGE -> {
                musicShouldPlay = cfg.musicTrack != WorkoutMusicTrack.NONE
                if (repNow) {
                    totalReps++
                    roundReps++
                    if (phase == ChallengePhase.WORK && roundReps >= cfg.emomRepsPerMin) {
                        completedEmomMinutes++
                        phase = ChallengePhase.REST
                        val earnedRestSec = (60f - phaseElapsedSec).roundToInt().coerceAtLeast(0)
                        beeps.add(BeepType.HOLD)
                        voice = "Objectif atteint ! Repos $earnedRestSec secondes."
                        syncStateFr = "REPOS ACQUIS ✓"
                    } else if (phase == ChallengePhase.WORK) {
                        syncStateFr = "$roundReps/${cfg.emomRepsPerMin} REPS"
                    }
                }
                musicDucked = (phase == ChallengePhase.REST)
                musicSprint = (phase == ChallengePhase.WORK && phaseElapsedSec >= 45f)

                val rem = (60f - phaseElapsedSec).coerceAtLeast(0f)
                val remInt = ceil(rem.toDouble()).toInt()
                if (remInt in 1..3 && remInt != lastSecBeepMark) {
                    lastSecBeepMark = remInt
                    beeps.add(BeepType.COUNTDOWN)
                }
                if (phaseElapsedSec >= 60f) {
                    lastSecBeepMark = -1
                    if (currentStage >= cfg.emomMinutes) {
                        phase = ChallengePhase.COMPLETED
                        beeps.add(BeepType.COMPLETE)
                        voice = "EMOM terminé ! $completedEmomMinutes sur ${cfg.emomMinutes} minutes validées !"
                        musicShouldPlay = false
                        result = buildResult(cfg, completedGoal = completedEmomMinutes >= cfg.emomMinutes)
                    } else {
                        currentStage++
                        roundReps = 0
                        phaseElapsedSec = 0f
                        phase = ChallengePhase.WORK
                        beeps.add(BeepType.GO)
                        voice = "Minute $currentStage sur ${cfg.emomMinutes}, ${cfg.emomRepsPerMin} reps !"
                        syncStateFr = "0/${cfg.emomRepsPerMin} REPS"
                        musicDucked = false
                    }
                }
            }
        }

        return ChallengeOutput(
            snapshot = buildSnapshot(cfg),
            beepsToPlay = beeps,
            voiceAnnouncementFr = voice,
            musicShouldPlay = musicShouldPlay && result == null,
            musicDucked = musicDucked,
            musicFinalSprint = musicSprint,
            completedResult = result
        )
    }

    private fun currentScoreValue(cfg: ChallengeConfig): Double = when (cfg.type) {
        ChallengeType.BIP_AND_GO_PUSHUPS -> syncedCycles.toDouble()
        ChallengeType.LUC_LEGER_CADENCE -> totalReps.toDouble()
        ChallengeType.PLANK_MUSIC_HOLD -> (validHoldSec * 10f).roundToInt() / 10.0
        ChallengeType.TARGET_REPS_RACE -> activeElapsedSec.roundToInt().coerceAtLeast(1).toDouble()
        ChallengeType.AMRAP_CHRONO -> totalReps.toDouble()
        ChallengeType.TABATA_HIIT -> totalReps.toDouble()
        ChallengeType.EMOM_CHALLENGE -> totalReps.toDouble()
    }

    private fun currentScoreLabel(cfg: ChallengeConfig): String = when (cfg.type) {
        ChallengeType.BIP_AND_GO_PUSHUPS -> "$syncedCycles cycles"
        ChallengeType.LUC_LEGER_CADENCE -> "Palier $currentStage · $totalReps reps"
        ChallengeType.PLANK_MUSIC_HOLD -> String.format(Locale.US, "%.1f s", validHoldSec)
        ChallengeType.TARGET_REPS_RACE -> {
            if (totalReps >= cfg.targetRepsGoal) {
                "${cfg.targetRepsGoal} reps en ${formatChallengeClock(activeElapsedSec.roundToInt())}"
            } else {
                "$totalReps/${cfg.targetRepsGoal} reps"
            }
        }
        ChallengeType.AMRAP_CHRONO -> "$totalReps reps (${cfg.amrapDurationSec / 60} min)"
        ChallengeType.TABATA_HIIT -> "$totalReps reps (${cfg.tabataRounds} tours)"
        ChallengeType.EMOM_CHALLENGE -> "$completedEmomMinutes/${cfg.emomMinutes} min · $totalReps reps"
    }

    private fun buildResult(cfg: ChallengeConfig, completedGoal: Boolean): ChallengeResult = ChallengeResult(
        config = cfg,
        completedGoal = completedGoal,
        scoreValue = currentScoreValue(cfg),
        scoreLabel = currentScoreLabel(cfg),
        durationSec = if (cfg.type == ChallengeType.PLANK_MUSIC_HOLD) {
            validHoldSec.roundToInt().coerceAtLeast(1)
        } else {
            activeElapsedSec.roundToInt().coerceAtLeast(1)
        },
        totalReps = totalReps,
        avgFormScore = lastAvgFormScore,
        caloriesKcal = lastCaloriesKcal
    )

    private fun buildSnapshot(cfg: ChallengeConfig): ChallengeSnapshot {
        val running = phase != ChallengePhase.IDLE &&
            phase != ChallengePhase.COMPLETED &&
            phase != ChallengePhase.FAILED

        if (phase == ChallengePhase.COUNTDOWN) {
            val rem = ceil((COUNTDOWN_SECONDS - countdownElapsedSec).toDouble()).toInt().coerceIn(1, COUNTDOWN_SECONDS)
            return ChallengeSnapshot(
                config = cfg,
                isRunning = true,
                phase = ChallengePhase.COUNTDOWN,
                phaseLabelFr = "DÉPART DANS $rem…",
                subtitleFr = cfg.summarySubtitleFr(),
                primaryMetricLabelFr = "COMPTE À REBOURS",
                primaryMetricValue = "$rem",
                progressFraction = (countdownElapsedSec / COUNTDOWN_SECONDS).coerceIn(0f, 1f),
                countdownSec = rem,
                syncStateFr = "EN POSITION",
                isUrgent = true
            )
        }

        return when (cfg.type) {
            ChallengeType.BIP_AND_GO_PUSHUPS -> {
                val goal = cfg.bipGoTargetCycles
                val goalStr = if (goal <= 0) "∞" else "$goal"
                val phaseLabel = when (phase) {
                    ChallengePhase.BIP_DOWN -> "DESCENDS"
                    ChallengePhase.BIP_HOLD -> "TIENS EN BAS"
                    ChallengePhase.BIP_GO -> "BIP & GO ! POUSSE !"
                    ChallengePhase.COMPLETED -> "DÉFI TERMINÉ"
                    else -> "FIN DE CADENCE"
                }
                val prog = if (goal > 0) (syncedCycles.toFloat() / goal).coerceIn(0f, 1f) else {
                    (phaseElapsedSec / cfg.bipGoTempo.downSec.coerceAtLeast(1f)).coerceIn(0f, 1f)
                }
                ChallengeSnapshot(
                    config = cfg,
                    isRunning = running,
                    phase = phase,
                    phaseLabelFr = phaseLabel,
                    subtitleFr = "${cfg.bipGoTempo.labelFr} · Fautes $consecutiveMissed/$MAX_CONSECUTIVE_MISSES",
                    primaryMetricLabelFr = "CYCLES SYNCHRO",
                    primaryMetricValue = "$syncedCycles / $goalStr",
                    progressFraction = prog,
                    elapsedSec = activeElapsedSec.toInt(),
                    currentScoreValue = currentScoreValue(cfg),
                    currentScoreLabel = currentScoreLabel(cfg),
                    syncStateFr = syncStateFr,
                    currentStage = syncedCycles + 1,
                    totalStages = goal.coerceAtLeast(1),
                    missedCount = missedCount,
                    isUrgent = phase == ChallengePhase.BIP_GO
                )
            }

            ChallengeType.LUC_LEGER_CADENCE -> {
                val cad = lucLegerCadenceSec(currentStage)
                ChallengeSnapshot(
                    config = cfg,
                    isRunning = running,
                    phase = phase,
                    phaseLabelFr = "PALIER $currentStage / 12",
                    subtitleFr = String.format(Locale.US, "%.2fs / rep · %s", cad, cfg.effectiveExercise().labelFr),
                    primaryMetricLabelFr = "REPS VALIDÉES",
                    primaryMetricValue = "$totalReps",
                    progressFraction = (phaseElapsedSec / cad).coerceIn(0f, 1f),
                    elapsedSec = activeElapsedSec.toInt(),
                    remainingSec = ceil((cad - phaseElapsedSec).toDouble()).toInt().coerceAtLeast(0),
                    currentScoreValue = currentScoreValue(cfg),
                    currentScoreLabel = currentScoreLabel(cfg),
                    syncStateFr = syncStateFr,
                    currentStage = currentStage,
                    totalStages = 12,
                    missedCount = missedCount,
                    isUrgent = currentStage >= 9
                )
            }

            ChallengeType.PLANK_MUSIC_HOLD -> {
                val target = cfg.plankTargetSec
                val remSec = if (target > 0) ceil((target - validHoldSec).toDouble()).toInt().coerceAtLeast(0) else 0
                val prog = if (target > 0) (validHoldSec / target).coerceIn(0f, 1f) else 1f
                val phaseLabel = when (phase) {
                    ChallengePhase.HOLDING -> if (target > 0 && remSec <= 15) "SPRINT FINAL !" else "MAINTIEN ACTIF"
                    ChallengePhase.PAUSED_POSTURE -> if (postureBreakSec < PLANK_GRACE_SEC) "RÉALIGNEZ-VOUS" else "CHRONO EN PAUSE"
                    ChallengePhase.COMPLETED -> "OBJECTIF VALIDÉ !"
                    else -> "TERMINÉ"
                }
                val metricVal = if (target > 0) {
                    "${String.format(Locale.US, "%.1f", validHoldSec)}s / ${target}s"
                } else {
                    String.format(Locale.US, "%.1fs", validHoldSec)
                }
                ChallengeSnapshot(
                    config = cfg,
                    isRunning = running,
                    phase = phase,
                    phaseLabelFr = phaseLabel,
                    subtitleFr = if (target > 0) "Reste ${formatChallengeClock(remSec)} · ${cfg.musicTrack.labelFr}" else "Mode Infini · ${cfg.musicTrack.labelFr}",
                    primaryMetricLabelFr = "GAINAGE VALIDÉ",
                    primaryMetricValue = metricVal,
                    progressFraction = prog,
                    elapsedSec = validHoldSec.toInt(),
                    remainingSec = remSec,
                    currentScoreValue = currentScoreValue(cfg),
                    currentScoreLabel = currentScoreLabel(cfg),
                    syncStateFr = syncStateFr,
                    isUrgent = target > 0 && remSec <= 15
                )
            }

            ChallengeType.TARGET_REPS_RACE -> {
                val left = (cfg.targetRepsGoal - totalReps).coerceAtLeast(0)
                val pacePerMin = if (activeElapsedSec >= 2f) ((totalReps / activeElapsedSec) * 60f).roundToInt() else 0
                ChallengeSnapshot(
                    config = cfg,
                    isRunning = running,
                    phase = phase,
                    phaseLabelFr = if (left == 0) "TERMINÉ !" else "ENCORE $left REPS",
                    subtitleFr = "Chrono ${formatChallengeClock(activeElapsedSec.toInt())} · Rythme $pacePerMin /min",
                    primaryMetricLabelFr = "RESTANTES",
                    primaryMetricValue = "$left",
                    progressFraction = (totalReps.toFloat() / cfg.targetRepsGoal.coerceAtLeast(1)).coerceIn(0f, 1f),
                    elapsedSec = activeElapsedSec.toInt(),
                    currentScoreValue = currentScoreValue(cfg),
                    currentScoreLabel = currentScoreLabel(cfg),
                    syncStateFr = syncStateFr,
                    isUrgent = left in 1..5
                )
            }

            ChallengeType.AMRAP_CHRONO -> {
                val remSec = ceil((cfg.amrapDurationSec - activeElapsedSec).toDouble()).toInt().coerceAtLeast(0)
                ChallengeSnapshot(
                    config = cfg,
                    isRunning = running,
                    phase = phase,
                    phaseLabelFr = if (remSec <= 15 && running) "SPRINT FINAL !" else "AMRAP · MAX REPS",
                    subtitleFr = "Reste ${formatChallengeClock(remSec)} · ${cfg.effectiveExercise().labelFr}",
                    primaryMetricLabelFr = "CHRONO",
                    primaryMetricValue = formatChallengeClock(remSec),
                    progressFraction = (activeElapsedSec / cfg.amrapDurationSec.coerceAtLeast(1)).coerceIn(0f, 1f),
                    elapsedSec = activeElapsedSec.toInt(),
                    remainingSec = remSec,
                    currentScoreValue = currentScoreValue(cfg),
                    currentScoreLabel = currentScoreLabel(cfg),
                    syncStateFr = "$totalReps REPS",
                    isUrgent = remSec <= 10
                )
            }

            ChallengeType.TABATA_HIIT -> {
                val isWork = phase == ChallengePhase.WORK
                val segLen = if (isWork) cfg.tabataWorkSec else cfg.tabataRestSec
                val remSec = ceil((segLen - phaseElapsedSec).toDouble()).toInt().coerceAtLeast(0)
                ChallengeSnapshot(
                    config = cfg,
                    isRunning = running,
                    phase = phase,
                    phaseLabelFr = if (isWork) "TOUR $currentStage/${cfg.tabataRounds} · EFFORT" else "REPOS · TOUR $currentStage/${cfg.tabataRounds}",
                    subtitleFr = "Total $totalReps reps · Tour : $roundReps reps",
                    primaryMetricLabelFr = if (isWork) "EFFORT" else "REPOS",
                    primaryMetricValue = "${remSec}s",
                    progressFraction = (phaseElapsedSec / segLen.coerceAtLeast(1)).coerceIn(0f, 1f),
                    elapsedSec = activeElapsedSec.toInt(),
                    remainingSec = remSec,
                    currentScoreValue = currentScoreValue(cfg),
                    currentScoreLabel = currentScoreLabel(cfg),
                    syncStateFr = syncStateFr,
                    currentStage = currentStage,
                    totalStages = cfg.tabataRounds,
                    isUrgent = remSec <= 3
                )
            }

            ChallengeType.EMOM_CHALLENGE -> {
                val remSec = ceil((60f - phaseElapsedSec).toDouble()).toInt().coerceAtLeast(0)
                val earnedRest = phase == ChallengePhase.REST
                ChallengeSnapshot(
                    config = cfg,
                    isRunning = running,
                    phase = phase,
                    phaseLabelFr = if (earnedRest) "REPOS ACQUIS !" else "MIN $currentStage/${cfg.emomMinutes} · $roundReps/${cfg.emomRepsPerMin}",
                    subtitleFr = if (earnedRest) "Prochaine minute dans ${remSec}s" else "Encore ${(cfg.emomRepsPerMin - roundReps).coerceAtLeast(0)} reps · ${remSec}s",
                    primaryMetricLabelFr = if (earnedRest) "REPOS" else "MINUTE",
                    primaryMetricValue = "${remSec}s",
                    progressFraction = if (earnedRest) (phaseElapsedSec / 60f).coerceIn(0f, 1f) else (roundReps.toFloat() / cfg.emomRepsPerMin.coerceAtLeast(1)).coerceIn(0f, 1f),
                    elapsedSec = activeElapsedSec.toInt(),
                    remainingSec = remSec,
                    currentScoreValue = currentScoreValue(cfg),
                    currentScoreLabel = currentScoreLabel(cfg),
                    syncStateFr = syncStateFr,
                    currentStage = currentStage,
                    totalStages = cfg.emomMinutes,
                    isUrgent = !earnedRest && remSec <= 10
                )
            }
        }
    }
}
