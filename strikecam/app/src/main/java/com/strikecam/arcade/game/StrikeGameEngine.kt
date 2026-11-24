package com.strikecam.arcade.game

import com.strikecam.arcade.data.ArcadeRank
import com.strikecam.arcade.data.BeatTarget
import com.strikecam.arcade.data.Difficulty
import com.strikecam.arcade.data.GameMode
import com.strikecam.arcade.data.HitVerdict
import com.strikecam.arcade.data.PlayerRoundSummary
import com.strikecam.arcade.data.TargetHand
import com.strikecam.arcade.data.TargetType
import com.strikecam.arcade.vision.FistKinematics
import com.strikecam.arcade.vision.StrikeKinematicsEngine
import com.strikecam.arcade.vision.StrikePoseSnapshot
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

data class HitPopup(
    val id: Int,
    val label: String,
    val verdict: HitVerdict,
    val normX: Float,
    val normY: Float,
    val createdMs: Float,
    val bonusTag: String? = null
)

data class SparkParticle(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val colorArgb: Long,
    val lifeFrac: Float,
    val radiusPx: Float
)

data class ActiveRenderTarget(
    val target: BeatTarget,
    val depthZ: Float, // 0.0 = far horizon, 1.0 = strike plane right now, > 1.0 = slightly past
    val screenX: Float,
    val screenY: Float
)

data class GameFrameSnapshot(
    val currentTimeMs: Float = 0f,
    val durationMs: Float = 60_000f,
    val score: Int = 0,
    val combo: Int = 0,
    val maxCombo: Int = 0,
    val multiplier: Int = 1,
    val isFeverMode: Boolean = false,
    val healthLives: Int = 3,
    val maxLives: Int = 3,
    val speedMultiplier: Float = 1.0f,
    val accuracyPct: Float = 100f,
    val caloriesKcal: Float = 0f,
    val activeTargets: List<ActiveRenderTarget> = emptyList(),
    val popups: List<HitPopup> = emptyList(),
    val particles: List<SparkParticle> = emptyList(),
    val shakeIntensity: Float = 0f,
    val pose: StrikePoseSnapshot = StrikePoseSnapshot(),
    val isRoundFinished: Boolean = false,
    val failedOut: Boolean = false,
    val sfxEvents: List<Int> = emptyList()
)

/**
 * Pure-Kotlin 60-120 FPS Beat-Saber / BoxVR Rhythm-Boxing Engine:
 * - Evaluates timing windows (PERFECT ±75ms, GREAT ±140ms, GOOD ±210ms, MISS)
 * - Validates hand color match (Left Crimson / Right Cyan / Both Double Power)
 * - Enforces punch motion + directional slash dot-product (1.5x bonus!)
 * - Validates physical Duck & Left/Right Slip dodge walls
 * - Supports Solo Arcade, Survival (3 lives + progressive speed ramp), 2-Player Duel, and Live Streaming Sync
 */
class StrikeGameEngine(
    private val difficulty: Difficulty = Difficulty.PRO,
    private val mode: GameMode = GameMode.SOLO_ARCADE,
    private val liveSyncMode: Boolean = false,
    private val kinematicsHelper: StrikeKinematicsEngine = StrikeKinematicsEngine()
) {
    companion object {
        const val PERFECT_WINDOW_MS = 75f
        const val GREAT_WINDOW_MS = 140f
        const val GOOD_WINDOW_MS = 210f
        const val LATE_MISS_MS = 230f

        private const val MIN_PUNCH_SPEED = 0.22f
        private const val MIN_FORWARD_THRUST = 0.25f
        private const val SLASH_MIN_DOT = 0.42f

        const val SFX_PERFECT = 0
        const val SFX_GREAT = 1
        const val SFX_GOOD = 2
        const val SFX_DODGE_CLEAR = 3
        const val SFX_MISS = 4
        const val SFX_COMBO_MILESTONE = 5
    }

    private val targets = ArrayList<BeatTarget>()
    private val resolvedIds = HashSet<Int>()
    private val popups = ArrayList<HitPopup>()
    private val particles = ArrayList<SparkParticle>()

    private var durationMs: Float = 60_000f
    private var score: Int = 0
    private var combo: Int = 0
    private var maxCombo: Int = 0
    private var perfectCount: Int = 0
    private var greatCount: Int = 0
    private var goodCount: Int = 0
    private var dodgeCount: Int = 0
    private var missCount: Int = 0
    private var totalPunches: Int = 0
    private var reactionAbsSumMs: Double = 0.0
    private var reactionSamples: Int = 0
    private var caloriesKcal: Float = 0f

    private var lives: Int = 3
    private var speedMultiplier: Float = 1.0f
    private var failedOut: Boolean = false
    private var shakeIntensity: Float = 0f
    private var nextPopupId: Int = 1
    private var nextLiveTargetId: Int = 10000
    private var liveBeatCounter: Int = 0
    private var lastUpdateMs: Float = 0f

    fun loadBeatmap(beatTargets: List<BeatTarget>, trackDurationMs: Float) {
        targets.clear()
        targets.addAll(beatTargets)
        resolvedIds.clear()
        popups.clear()
        particles.clear()
        durationMs = trackDurationMs.coerceAtLeast(10_000f)
        score = 0
        combo = 0
        maxCombo = 0
        perfectCount = 0
        greatCount = 0
        goodCount = 0
        dodgeCount = 0
        missCount = 0
        totalPunches = 0
        reactionAbsSumMs = 0.0
        reactionSamples = 0
        caloriesKcal = 0f
        lives = 3
        speedMultiplier = 1.0f
        failedOut = false
        shakeIntensity = 0f
        liveBeatCounter = 0
        lastUpdateMs = 0f
    }

    /** Spawns a choreographed target dynamically on a live Spotify/Deezer/SoundCloud beat. */
    fun enqueueLiveSyncBeat(hitTimeMs: Float, isDrop: Boolean) {
        liveBeatCounter++
        val id = nextLiveTargetId++
        val target = when {
            liveBeatCounter % 12 == 0 -> BeatTarget(
                id = id,
                timeMs = hitTimeMs,
                type = when ((liveBeatCounter / 12) % 3) {
                    0 -> TargetType.DODGE_DUCK
                    1 -> TargetType.DODGE_SLIP_LEFT
                    else -> TargetType.DODGE_SLIP_RIGHT
                },
                hand = TargetHand.BODY,
                normX = 0.50f,
                normY = 0.30f,
                durationMs = 480f,
                intensity = 0.85f
            )
            isDrop && liveBeatCounter % 4 == 0 -> BeatTarget(
                id = id,
                timeMs = hitTimeMs,
                type = TargetType.DOUBLE_STRIKE,
                hand = TargetHand.BOTH,
                normX = 0.50f,
                normY = 0.38f,
                intensity = 1.0f
            )
            liveBeatCounter % 5 == 0 -> {
                val left = liveBeatCounter % 2 == 0
                BeatTarget(
                    id = id,
                    timeMs = hitTimeMs,
                    type = listOf(TargetType.SLASH_UP, TargetType.SLASH_IN, TargetType.SLASH_OUT)[liveBeatCounter % 3],
                    hand = if (left) TargetHand.LEFT else TargetHand.RIGHT,
                    normX = if (left) 0.34f else 0.66f,
                    normY = 0.42f,
                    intensity = 0.9f
                )
            }
            else -> {
                val left = liveBeatCounter % 2 == 0
                BeatTarget(
                    id = id,
                    timeMs = hitTimeMs,
                    type = if (left) TargetType.JAB_LEFT else TargetType.JAB_RIGHT,
                    hand = if (left) TargetHand.LEFT else TargetHand.RIGHT,
                    normX = if (left) 0.32f else 0.68f,
                    normY = if (liveBeatCounter % 4 < 2) 0.35f else 0.45f,
                    intensity = 0.78f
                )
            }
        }
        targets.add(target)
        // Trim ancient resolved targets in endless live sync
        if (targets.size > 160) {
            val cutoff = hitTimeMs - 4000f
            targets.removeAll { it.timeMs < cutoff && it.id in resolvedIds }
        }
    }

    fun currentMultiplier(): Int = when {
        combo >= 30 -> 8
        combo >= 18 -> 4
        combo >= 8 -> 2
        else -> 1
    }

    fun step(currentTimeMs: Float, pose: StrikePoseSnapshot): GameFrameSnapshot {
        val dtSec = ((currentTimeMs - lastUpdateMs).coerceIn(0f, 100f)) / 1000f
        lastUpdateMs = currentTimeMs

        // Survival mode ramps speed progressively (+0.08x every 15s up to 1.60x!)
        if (mode == GameMode.SURVIVAL && !failedOut) {
            val steps15s = (currentTimeMs / 15_000f).toInt()
            speedMultiplier = (1.0f + steps15s * 0.08f).coerceAtMost(1.60f)
        }

        val approachMs = (difficulty.approachTimeMs / speedMultiplier).coerceIn(480f, 1800f)
        val sfxOut = ArrayList<Int>(4)

        // Update particles & micro-shake
        updateParticles(dtSec)
        shakeIntensity = (shakeIntensity - dtSec * 3.5f).coerceAtLeast(0f)
        popups.removeAll { (currentTimeMs - it.createdMs) > 650f }

        // Dynamic hitbox radius scaled to athlete's calibrated shoulder width
        val hitRadius = (pose.baseline.shoulderWidth * 0.68f).coerceIn(0.14f, 0.28f)

        val visibleTargets = ArrayList<ActiveRenderTarget>(12)

        for (t in targets) {
            if (t.id in resolvedIds) continue

            val deltaMs = t.timeMs - currentTimeMs // positive = approaching, negative = past
            if (deltaMs > approachMs) continue

            // Check Dodge Walls when player passes through the barrier window
            if (t.type.isDodge) {
                val wallEndMs = t.timeMs + t.durationMs.coerceAtLeast(320f)
                if (currentTimeMs >= t.timeMs - 60f && currentTimeMs <= wallEndMs) {
                    val dodged = isDodgingWall(t.type, pose)
                    if (dodged && currentTimeMs >= t.timeMs + t.durationMs * 0.35f) {
                        resolvedIds.add(t.id)
                        registerSuccess(t, HitVerdict.DODGE_OK, 0f, bonusMult = 1.0f, currentTimeMs, sfxOut, null)
                        continue
                    }
                } else if (currentTimeMs > wallEndMs) {
                    resolvedIds.add(t.id)
                    if (isDodgingWall(t.type, pose)) {
                        registerSuccess(t, HitVerdict.DODGE_OK, 0f, bonusMult = 1.0f, currentTimeMs, sfxOut, null)
                    } else {
                        registerMiss(t, currentTimeMs, sfxOut)
                    }
                    continue
                }
            } else {
                // Strike target evaluation within active hit window
                val absDelta = abs(deltaMs)
                if (absDelta <= GOOD_WINDOW_MS && pose.hasPose) {
                    val evaluation = evaluateStrike(t, pose, hitRadius)
                    if (evaluation.hit) {
                        resolvedIds.add(t.id)
                        val verdict = when {
                            absDelta <= PERFECT_WINDOW_MS -> HitVerdict.PERFECT
                            absDelta <= GREAT_WINDOW_MS -> HitVerdict.GREAT
                            else -> HitVerdict.GOOD
                        }
                        registerSuccess(
                            target = t,
                            verdict = verdict,
                            absReactionMs = absDelta,
                            bonusMult = evaluation.bonusMultiplier,
                            nowMs = currentTimeMs,
                            sfxOut = sfxOut,
                            bonusTag = evaluation.bonusTag
                        )
                        continue
                    }
                } else if (deltaMs < -LATE_MISS_MS) {
                    resolvedIds.add(t.id)
                    registerMiss(t, currentTimeMs, sfxOut)
                    continue
                }
            }

            // Compute perspective depthZ in [0..1.18] for 3D tunnel rendering
            val depthZ = (1f - (deltaMs / approachMs)).coerceIn(0f, 1.18f)
            visibleTargets.add(
                ActiveRenderTarget(
                    target = t,
                    depthZ = depthZ,
                    screenX = t.normX,
                    screenY = t.normY
                )
            )
        }

        // Gentle aerobic burn accumulation while active
        if (pose.hasPose && dtSec > 0f && !failedOut) {
            caloriesKcal += dtSec * 0.09f
        }

        val allResolved = !liveSyncMode && mode != GameMode.SURVIVAL &&
            targets.isNotEmpty() &&
            resolvedIds.size >= targets.size &&
            currentTimeMs >= (targets.last().timeMs + 600f)

        val timeFinished = !liveSyncMode && mode != GameMode.SURVIVAL && currentTimeMs >= durationMs + 500f
        val finished = failedOut || allResolved || timeFinished

        return GameFrameSnapshot(
            currentTimeMs = currentTimeMs,
            durationMs = durationMs,
            score = score,
            combo = combo,
            maxCombo = maxCombo,
            multiplier = currentMultiplier(),
            isFeverMode = combo >= 20,
            healthLives = lives,
            maxLives = 3,
            speedMultiplier = speedMultiplier,
            accuracyPct = computeAccuracyPct(),
            caloriesKcal = caloriesKcal,
            activeTargets = visibleTargets,
            popups = popups.toList(),
            particles = particles.toList(),
            shakeIntensity = shakeIntensity,
            pose = pose,
            isRoundFinished = finished,
            failedOut = failedOut,
            sfxEvents = sfxOut
        )
    }

    fun buildSummary(playerName: String = "JOUEUR 1"): PlayerRoundSummary {
        val total = perfectCount + greatCount + goodCount + dodgeCount + missCount
        val acc = computeAccuracyPct()
        val rank = ArcadeRank.compute(acc, missCount, total)
        val avgReact = if (reactionSamples > 0) (reactionAbsSumMs / reactionSamples).toFloat() else 0f
        return PlayerRoundSummary(
            playerName = playerName,
            score = score,
            rank = rank,
            maxCombo = maxCombo,
            accuracyPct = acc,
            perfectCount = perfectCount,
            greatCount = greatCount,
            goodCount = goodCount,
            dodgeCount = dodgeCount,
            missCount = missCount,
            avgReactionMs = avgReact,
            totalPunches = totalPunches,
            caloriesKcal = caloriesKcal,
            survivedSec = lastUpdateMs / 1000f,
            peakSpeedMultiplier = speedMultiplier,
            failedOut = failedOut
        )
    }

    private data class StrikeEval(
        val hit: Boolean,
        val bonusMultiplier: Float = 1.0f,
        val bonusTag: String? = null
    )

    private fun evaluateStrike(t: BeatTarget, pose: StrikePoseSnapshot, hitRadius: Float): StrikeEval {
        if (t.type == TargetType.DOUBLE_STRIKE) {
            val lOk = fistHitsTarget(pose.leftFist, 0.34f, t.normY, hitRadius * 1.15f)
            val rOk = fistHitsTarget(pose.rightFist, 0.66f, t.normY, hitRadius * 1.15f)
            return if (lOk && rOk) StrikeEval(true, 1.5f, "DOUBLE ×1.5") else StrikeEval(false)
        }

        val fist = if (t.hand == TargetHand.LEFT) pose.leftFist else pose.rightFist
        if (!fistHitsTarget(fist, t.normX, t.normY, hitRadius)) {
            return StrikeEval(false)
        }

        if (t.type.isSlash) {
            val (reqDx, reqDy) = requiredSlashVector(t.type, t.hand)
            val dot = kinematicsHelper.computeSlashAlignment(fist.vx, fist.vy, reqDx, reqDy)
            return if (dot >= SLASH_MIN_DOT) {
                StrikeEval(true, 1.5f, "SLASH ×1.5")
            } else if (fist.speed2D >= MIN_PUNCH_SPEED * 1.5f || fist.forwardThrust >= MIN_FORWARD_THRUST * 1.4f) {
                // Accept strong non-aligned hit at base points so gameplay never feels frustrating
                StrikeEval(true, 0.85f, null)
            } else {
                StrikeEval(false)
            }
        }

        return StrikeEval(true, 1.0f, null)
    }

    private fun fistHitsTarget(fist: FistKinematics, targetX: Float, targetY: Float, radius: Float): Boolean {
        if (!fist.visible) return false
        val dist = hypot(fist.x - targetX, fist.y - targetY)
        if (dist > radius) return false
        // Require real punch motion (prevents holding hands statically on target)
        return fist.speed2D >= MIN_PUNCH_SPEED || fist.forwardThrust >= MIN_FORWARD_THRUST
    }

    private fun requiredSlashVector(type: TargetType, hand: TargetHand): Pair<Float, Float> = when (type) {
        TargetType.SLASH_UP -> 0f to -1f
        TargetType.SLASH_DOWN -> 0f to 1f
        TargetType.SLASH_IN -> if (hand == TargetHand.LEFT) (1f to 0f) else (-1f to 0f)
        TargetType.SLASH_OUT -> if (hand == TargetHand.LEFT) (-1f to 0f) else (1f to 0f)
        else -> 0f to 0f
    }

    private fun isDodgingWall(type: TargetType, pose: StrikePoseSnapshot): Boolean {
        if (!pose.hasPose) return false
        return when (type) {
            TargetType.DODGE_DUCK -> pose.isDucking
            TargetType.DODGE_SLIP_LEFT -> pose.isSlippingLeft
            TargetType.DODGE_SLIP_RIGHT -> pose.isSlippingRight
            else -> false
        }
    }

    private fun registerSuccess(
        target: BeatTarget,
        verdict: HitVerdict,
        absReactionMs: Float,
        bonusMult: Float,
        nowMs: Float,
        sfxOut: MutableList<Int>,
        bonusTag: String?
    ) {
        combo++
        if (combo > maxCombo) maxCombo = combo
        val mult = currentMultiplier()
        val gained = (verdict.basePoints * mult * difficulty.scoreMultiplier * bonusMult).roundToInt()
        score += gained

        when (verdict) {
            HitVerdict.PERFECT -> {
                perfectCount++
                totalPunches += if (target.type == TargetType.DOUBLE_STRIKE) 2 else 1
                caloriesKcal += 0.24f
                sfxOut.add(SFX_PERFECT)
            }
            HitVerdict.GREAT -> {
                greatCount++
                totalPunches += if (target.type == TargetType.DOUBLE_STRIKE) 2 else 1
                caloriesKcal += 0.20f
                sfxOut.add(SFX_GREAT)
            }
            HitVerdict.GOOD -> {
                goodCount++
                totalPunches += if (target.type == TargetType.DOUBLE_STRIKE) 2 else 1
                caloriesKcal += 0.16f
                sfxOut.add(SFX_GOOD)
            }
            HitVerdict.DODGE_OK -> {
                dodgeCount++
                caloriesKcal += 0.32f
                sfxOut.add(SFX_DODGE_CLEAR)
            }
            HitVerdict.MISS -> Unit
        }

        if (!target.type.isDodge) {
            reactionAbsSumMs += absReactionMs.toDouble()
            reactionSamples++
        }

        if (combo == 10 || combo == 20 || combo == 30 || (combo > 30 && combo % 25 == 0)) {
            sfxOut.add(SFX_COMBO_MILESTONE)
        }

        shakeIntensity = when {
            target.type == TargetType.DOUBLE_STRIKE -> 1.0f
            verdict == HitVerdict.PERFECT -> 0.55f
            else -> 0.30f
        }

        popups.add(
            HitPopup(
                id = nextPopupId++,
                label = verdict.labelFr,
                verdict = verdict,
                normX = target.normX,
                normY = target.normY,
                createdMs = nowMs,
                bonusTag = bonusTag ?: "+$gained"
            )
        )

        val colorArgb = when (target.hand) {
            TargetHand.LEFT -> 0xFFFF2A4B
            TargetHand.RIGHT -> 0xFF00D4FF
            TargetHand.BOTH -> 0xFFFFB800
            TargetHand.BODY -> 0xFF1ED760
        }
        spawnHitSparks(target.normX, target.normY, colorArgb, count = if (verdict == HitVerdict.PERFECT) 16 else 10)
    }

    private fun registerMiss(target: BeatTarget, nowMs: Float, sfxOut: MutableList<Int>) {
        missCount++
        combo = 0
        sfxOut.add(SFX_MISS)
        shakeIntensity = 0.75f
        popups.add(
            HitPopup(
                id = nextPopupId++,
                label = "MISS",
                verdict = HitVerdict.MISS,
                normX = target.normX,
                normY = target.normY,
                createdMs = nowMs
            )
        )
        if (mode == GameMode.SURVIVAL) {
            lives = (lives - 1).coerceAtLeast(0)
            if (lives <= 0) {
                failedOut = true
            }
        }
    }

    private fun spawnHitSparks(cx: Float, cy: Float, colorArgb: Long, count: Int) {
        for (i in 0 until count) {
            val angle = (i.toFloat() / count) * 6.2831855f + Random.nextFloat() * 0.35f
            val speed = 0.35f + Random.nextFloat() * 0.65f
            particles.add(
                SparkParticle(
                    x = cx,
                    y = cy,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed,
                    colorArgb = colorArgb,
                    lifeFrac = 1.0f,
                    radiusPx = 4f + Random.nextFloat() * 5f
                )
            )
        }
        if (particles.size > 90) {
            particles.subList(0, particles.size - 90).clear()
        }
    }

    private fun updateParticles(dtSec: Float) {
        if (particles.isEmpty() || dtSec <= 0f) return
        val iter = particles.listIterator()
        while (iter.hasNext()) {
            val p = iter.next()
            val nextLife = p.lifeFrac - dtSec * 2.6f
            if (nextLife <= 0f) {
                iter.remove()
            } else {
                iter.set(
                    p.copy(
                        x = p.x + p.vx * dtSec,
                        y = p.y + p.vy * dtSec,
                        vy = p.vy + 0.9f * dtSec,
                        lifeFrac = nextLife
                    )
                )
            }
        }
    }

    private fun computeAccuracyPct(): Float {
        val total = perfectCount + greatCount + goodCount + dodgeCount + missCount
        if (total <= 0) return 100f
        val weighted = perfectCount * 1.0f + dodgeCount * 1.0f + greatCount * 0.80f + goodCount * 0.55f
        return ((weighted / total) * 100f).coerceIn(0f, 100f)
    }
}
