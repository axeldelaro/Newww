package com.strikecam.arcade

import com.strikecam.arcade.data.ArcadeRank
import com.strikecam.arcade.data.BeatTarget
import com.strikecam.arcade.data.Difficulty
import com.strikecam.arcade.data.DuelMatchState
import com.strikecam.arcade.data.GameMode
import com.strikecam.arcade.data.HitVerdict
import com.strikecam.arcade.data.PlayerRoundSummary
import com.strikecam.arcade.data.StreamingPlatform
import com.strikecam.arcade.data.TargetHand
import com.strikecam.arcade.data.TargetType
import com.strikecam.arcade.game.StrikeGameEngine
import com.strikecam.arcade.music.TrackDiscoveryRepository
import com.strikecam.arcade.nativeengine.StrikeCamNative
import com.strikecam.arcade.vision.CalibrationBaseline
import com.strikecam.arcade.vision.FistKinematics
import com.strikecam.arcade.vision.StrikePoseSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrikeCamUnitTest {

    private fun poseWithFists(
        left: FistKinematics = FistKinematics(x = 0.30f, y = 0.50f, visible = true),
        right: FistKinematics = FistKinematics(x = 0.70f, y = 0.50f, visible = true),
        ducking: Boolean = false,
        slipLeft: Boolean = false,
        slipRight: Boolean = false
    ) = StrikePoseSnapshot(
        timestampMs = 1000L,
        hasPose = true,
        leftFist = left,
        rightFist = right,
        baseline = CalibrationBaseline(shoulderWidth = 0.28f, sampleCount = 10),
        isDucking = ducking,
        isSlippingLeft = slipLeft,
        isSlippingRight = slipRight
    )

    @Test
    fun strikeEngine_awardsPerfectGreatGoodAndRejectsStaticHand() {
        val engine = StrikeGameEngine(Difficulty.CHILL, GameMode.SOLO_ARCADE)
        engine.loadBeatmap(
            listOf(
                BeatTarget(1, 2000f, TargetType.JAB_LEFT, TargetHand.LEFT, 0.32f, 0.40f),
                BeatTarget(2, 3000f, TargetType.JAB_RIGHT, TargetHand.RIGHT, 0.68f, 0.40f),
                BeatTarget(3, 4000f, TargetType.JAB_LEFT, TargetHand.LEFT, 0.32f, 0.40f)
            ),
            10_000f
        )

        // Static hand parked on target (speed2D = 0, forwardThrust = 0) must NOT trigger hit!
        val staticSnap = engine.step(
            2000f,
            poseWithFists(left = FistKinematics(x = 0.32f, y = 0.40f, speed2D = 0.02f, forwardThrust = 0.01f, visible = true))
        )
        assertEquals(0, staticSnap.score)

        // Real jab punch within ±75ms -> PERFECT! (300 pts)
        val perfectSnap = engine.step(
            2030f,
            poseWithFists(left = FistKinematics(x = 0.32f, y = 0.40f, speed2D = 0.85f, visible = true))
        )
        assertEquals(300, perfectSnap.score)
        assertEquals(1, perfectSnap.combo)
        assertEquals(HitVerdict.PERFECT, perfectSnap.popups.last().verdict)

        // Right jab at +110ms -> GREAT (200 pts)
        val greatSnap = engine.step(
            3110f,
            poseWithFists(right = FistKinematics(x = 0.68f, y = 0.40f, forwardThrust = 0.65f, visible = true))
        )
        assertEquals(500, greatSnap.score)
        assertEquals(HitVerdict.GREAT, greatSnap.popups.last().verdict)

        // Left jab at +185ms -> GOOD (100 pts)
        val goodSnap = engine.step(
            4185f,
            poseWithFists(left = FistKinematics(x = 0.32f, y = 0.40f, speed2D = 0.55f, visible = true))
        )
        assertEquals(600, goodSnap.score)
        assertEquals(HitVerdict.GOOD, goodSnap.popups.last().verdict)
    }

    @Test
    fun strikeEngine_enforcesHandColorMatchAndDirectionalSlashBonus() {
        val engine = StrikeGameEngine(Difficulty.CHILL, GameMode.SOLO_ARCADE)
        engine.loadBeatmap(
            listOf(BeatTarget(1, 2000f, TargetType.SLASH_UP, TargetHand.LEFT, 0.34f, 0.42f)),
            10_000f
        )

        // Right hand punching a LEFT target must be ignored
        val wrongHand = engine.step(
            2000f,
            poseWithFists(right = FistKinematics(x = 0.34f, y = 0.42f, vx = 0f, vy = -1.2f, speed2D = 1.2f, visible = true))
        )
        assertEquals(0, wrongHand.score)

        // Left hand slashing UP (vy = -1.2) earns PERFECT (300) * 1.5x slash bonus = 450 pts!
        val slashHit = engine.step(
            2010f,
            poseWithFists(left = FistKinematics(x = 0.34f, y = 0.42f, vx = 0f, vy = -1.2f, speed2D = 1.2f, visible = true))
        )
        assertEquals(450, slashHit.score)
        assertEquals("SLASH ×1.5", slashHit.popups.last().bonusTag)
    }

    @Test
    fun strikeEngine_doubleStrikeRequiresBothFistsSimultaneously() {
        val engine = StrikeGameEngine(Difficulty.CHILL, GameMode.SOLO_ARCADE)
        engine.loadBeatmap(
            listOf(BeatTarget(1, 2000f, TargetType.DOUBLE_STRIKE, TargetHand.BOTH, 0.50f, 0.38f)),
            10_000f
        )

        // Only left fist hits -> should NOT resolve yet
        val oneFist = engine.step(
            2000f,
            poseWithFists(
                left = FistKinematics(x = 0.34f, y = 0.38f, speed2D = 0.9f, visible = true),
                right = FistKinematics(x = 0.90f, y = 0.80f, speed2D = 0.0f, visible = true)
            )
        )
        assertEquals(0, oneFist.score)

        // Both fists strike together -> 300 * 1.5 = 450 pts!
        val bothFists = engine.step(
            2020f,
            poseWithFists(
                left = FistKinematics(x = 0.34f, y = 0.38f, speed2D = 0.9f, visible = true),
                right = FistKinematics(x = 0.66f, y = 0.38f, speed2D = 0.9f, visible = true)
            )
        )
        assertEquals(450, bothFists.score)
        assertEquals("DOUBLE ×1.5", bothFists.popups.last().bonusTag)
    }

    @Test
    fun strikeEngine_dodgeWallsClearWhenDuckingOrSlippingAndMissOtherwise() {
        val engine = StrikeGameEngine(Difficulty.CHILL, GameMode.SOLO_ARCADE)
        engine.loadBeatmap(
            listOf(
                BeatTarget(1, 2000f, TargetType.DODGE_DUCK, TargetHand.BODY, 0.50f, 0.30f, durationMs = 400f),
                BeatTarget(2, 4000f, TargetType.DODGE_SLIP_LEFT, TargetHand.BODY, 0.50f, 0.45f, durationMs = 400f),
                BeatTarget(3, 6000f, TargetType.DODGE_SLIP_RIGHT, TargetHand.BODY, 0.50f, 0.45f, durationMs = 400f)
            ),
            10_000f
        )

        // Duck wall cleared
        val s1 = engine.step(2200f, poseWithFists(ducking = true))
        assertEquals(250, s1.score)
        assertEquals(1, s1.combo)

        // Slip left wall cleared
        val s2 = engine.step(4200f, poseWithFists(slipLeft = true))
        assertEquals(500, s2.score)
        assertEquals(2, s2.combo)

        // Slip right wall NOT dodged past wallEndMs -> MISS resets combo
        val s3 = engine.step(6500f, poseWithFists(slipRight = false))
        assertEquals(0, s3.combo)
        assertEquals(HitVerdict.MISS, s3.popups.last().verdict)
    }

    @Test
    fun strikeEngine_comboMultiplierAndFeverModeScaleProperly() {
        val engine = StrikeGameEngine(Difficulty.CHILL, GameMode.SOLO_ARCADE)
        val targets = (1..32).map { i ->
            BeatTarget(i, i * 500f, TargetType.JAB_LEFT, TargetHand.LEFT, 0.32f, 0.40f)
        }
        engine.loadBeatmap(targets, 20_000f)

        val punchPose = poseWithFists(left = FistKinematics(x = 0.32f, y = 0.40f, speed2D = 1.0f, visible = true))
        var snap = engine.step(0f, punchPose)
        for (i in 1..30) {
            snap = engine.step(i * 500f, punchPose)
        }
        assertEquals(30, snap.combo)
        assertEquals(8, snap.multiplier)
        assertTrue(snap.isFeverMode)
    }

    @Test
    fun strikeEngine_survivalModeRampsSpeedAndFailsOutAfterThreeMisses() {
        val engine = StrikeGameEngine(Difficulty.PRO, GameMode.SURVIVAL)
        engine.loadBeatmap(
            listOf(
                BeatTarget(1, 16_000f, TargetType.JAB_LEFT, TargetHand.LEFT, 0.32f, 0.40f),
                BeatTarget(2, 17_000f, TargetType.JAB_LEFT, TargetHand.LEFT, 0.32f, 0.40f),
                BeatTarget(3, 18_000f, TargetType.JAB_LEFT, TargetHand.LEFT, 0.32f, 0.40f)
            ),
            60_000f
        )

        val idlePose = poseWithFists()
        val s1 = engine.step(16_300f, idlePose)
        assertEquals(2, s1.healthLives)
        assertEquals(1.08f, s1.speedMultiplier, 0.005f)
        assertFalse(s1.failedOut)

        val s2 = engine.step(17_300f, idlePose)
        assertEquals(1, s2.healthLives)
        assertFalse(s2.failedOut)

        val s3 = engine.step(18_300f, idlePose)
        assertEquals(0, s3.healthLives)
        assertTrue(s3.failedOut)
        assertTrue(s3.isRoundFinished)
    }

    @Test
    fun rankAndDuelWinnerComputation_handlesEdgeCasesAccurately() {
        assertEquals(ArcadeRank.S_PLUS, ArcadeRank.compute(98f, 0, 50))
        assertEquals(ArcadeRank.S, ArcadeRank.compute(98f, 1, 50)) // 1 miss caps at S
        assertEquals(ArcadeRank.A, ArcadeRank.compute(85f, 3, 50))
        assertEquals(ArcadeRank.D, ArcadeRank.compute(40f, 30, 50))

        val p1 = PlayerRoundSummary("JOUEUR 1", 12000, ArcadeRank.S, 24, 92f, 20, 4, 0, 2, 1, 42f, 24, 12f)
        val p2 = PlayerRoundSummary("JOUEUR 2", 12000, ArcadeRank.S_PLUS, 27, 96f, 25, 2, 0, 2, 0, 35f, 27, 13f)
        val duel = DuelMatchState(player1Summary = p1, player2Summary = p2)
        assertTrue(duel.winnerLabel.contains("JOUEUR 2"))
    }

    @Test
    fun trackDiscovery_parsesStreamingUrlsAndZaagForgeBpmFilenames() {
        val sp = TrackDiscoveryRepository.parseStreamingUrl("Check this https://open.spotify.com/track/7ouMYWpwJ422jRcDASZB7P?si=abc")
        assertNotNull(sp)
        assertEquals(StreamingPlatform.SPOTIFY, sp!!.platform)
        assertEquals("7ouMYWpwJ422jRcDASZB7P", sp.trackId)

        val dz = TrackDiscoveryRepository.parseStreamingUrl("https://www.deezer.com/fr/track/3135556")
        assertEquals(StreamingPlatform.DEEZER, dz!!.platform)
        assertEquals("3135556", dz.trackId)

        val sc = TrackDiscoveryRepository.parseStreamingUrl("https://soundcloud.com/noiseflow/krach-anthem")
        assertEquals(StreamingPlatform.SOUNDCLOUD, sc!!.platform)

        assertNull(TrackDiscoveryRepository.parseStreamingUrl("no url here"))

        assertEquals(210f, TrackDiscoveryRepository.extractBpmFromFileName("Song_UOK-Piep_210BPM.m4a")!!, 0.01f)
        assertEquals(195f, TrackDiscoveryRepository.extractBpmFromFileName("ZaagForge_KRACH_195 bpm.wav")!!, 0.01f)
        assertNull(TrackDiscoveryRepository.extractBpmFromFileName("RegularSong.mp3"))
    }

    @Test
    fun nativeBeatmapUnpacker_deserializesPackedFloatArrayAccurately() {
        val packed = floatArrayOf(
            200f, 250f, 64000f, 2f, 3f,
            // Target 0: JAB_LEFT
            0f, 1500f, 0f, 0f, 0.32f, 0.38f, 0f, 0.85f,
            // Target 1: DODGE_DUCK
            1f, 3000f, 7f, 3f, 0.50f, 0.30f, 450f, 0.90f
        )
        val map = StrikeCamNative.unpackBeatmap(packed)
        assertEquals(200f, map.bpm, 0.01f)
        assertEquals(3, map.dropCount)
        assertEquals(2, map.targets.size)
        assertEquals(TargetType.JAB_LEFT, map.targets[0].type)
        assertEquals(TargetHand.LEFT, map.targets[0].hand)
        assertEquals(TargetType.DODGE_DUCK, map.targets[1].type)
        assertEquals(450f, map.targets[1].durationMs, 0.01f)
    }
}
