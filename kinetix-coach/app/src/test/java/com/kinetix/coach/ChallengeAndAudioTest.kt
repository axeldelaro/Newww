package com.kinetix.coach

import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.MovementPhase
import com.kinetix.coach.vision.BeepType
import com.kinetix.coach.vision.BiomechanicsFrame
import com.kinetix.coach.vision.BipGoTempo
import com.kinetix.coach.vision.ChallengeConfig
import com.kinetix.coach.vision.ChallengeController
import com.kinetix.coach.vision.ChallengePhase
import com.kinetix.coach.vision.ChallengeType
import com.kinetix.coach.vision.FormQuality
import com.kinetix.coach.vision.ProceduralAudioSynth
import com.kinetix.coach.vision.WorkoutAudioEngine
import com.kinetix.coach.vision.WorkoutMusicTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class ChallengeAndAudioTest {

    private fun stubFrame(
        exercise: ExerciseType,
        phase: MovementPhase = MovementPhase.READY,
        repCompletedNow: Boolean = false,
        romPct: Int = 85,
        poseDetected: Boolean = true,
        avgFormScore: Int = 92
    ): BiomechanicsFrame = BiomechanicsFrame(
        exercise = exercise,
        landmarks = emptyList(),
        repCount = 0,
        isometricHoldSec = 0f,
        phase = phase,
        romPct = romPct,
        formScore = avgFormScore,
        formQuality = FormQuality.GOOD,
        feedbackFr = "",
        voiceCueFr = null,
        angleBadges = emptyList(),
        avgFormScore = avgFormScore,
        avgRomPct = romPct,
        topFaults = emptyList(),
        poseDetected = poseDetected,
        repCompletedNow = repCompletedNow
    )

    private fun advancePastCountdown(
        controller: ChallengeController,
        config: ChallengeConfig,
        startMs: Long = 1000L
    ): Long {
        controller.start(config, startMs)
        var t = startMs
        repeat(4) {
            t += 1000L
            controller.onFrame(null, t, 0.0)
        }
        return t
    }

    @Test
    fun bipAndGo_cyclesPhasesCountsSyncedRepsAndEndsOnTwoMisses() {
        val controller = ChallengeController()
        val cfg = ChallengeConfig(
            type = ChallengeType.BIP_AND_GO_PUSHUPS,
            exercise = ExerciseType.PUSHUP,
            bipGoTempo = BipGoTempo.BEGINNER, // down=2.0, hold=1.0, up=2.2
            bipGoTargetCycles = 2
        )

        var t = 1000L
        val startOut = controller.start(cfg, t)
        assertEquals(ChallengePhase.COUNTDOWN, startOut.snapshot.phase)
        assertTrue(startOut.beepsToPlay.contains(BeepType.COUNTDOWN))

        // Finish 3s countdown -> enters BIP_DOWN
        t += 1000L; controller.onFrame(null, t)
        t += 1000L; controller.onFrame(null, t)
        t += 1000L
        val goOut = controller.onFrame(stubFrame(ExerciseType.PUSHUP, MovementPhase.READY), t)!!
        assertEquals(ChallengePhase.BIP_DOWN, goOut.snapshot.phase)
        assertTrue(goOut.beepsToPlay.contains(BeepType.DOWN))

        // Cycle 1: descend to BOTTOM -> enters BIP_HOLD -> hold 1.0s -> enters BIP_GO -> complete rep!
        t += 600L
        val holdOut = controller.onFrame(stubFrame(ExerciseType.PUSHUP, MovementPhase.BOTTOM, romPct = 90), t)!!
        assertEquals(ChallengePhase.BIP_HOLD, holdOut.snapshot.phase)
        assertTrue(holdOut.beepsToPlay.contains(BeepType.HOLD))

        t += 1100L
        val pushOut = controller.onFrame(stubFrame(ExerciseType.PUSHUP, MovementPhase.BOTTOM, romPct = 90), t)!!
        assertEquals(ChallengePhase.BIP_GO, pushOut.snapshot.phase)
        assertTrue(pushOut.beepsToPlay.contains(BeepType.GO))

        t += 500L
        val rep1 = controller.onFrame(stubFrame(ExerciseType.PUSHUP, MovementPhase.READY, repCompletedNow = true), t)!!
        assertEquals(1.0, rep1.snapshot.currentScoreValue, 0.01)
        assertTrue(rep1.snapshot.syncStateFr?.contains("SYNCHRO") == true)

        // Transition to Cycle 2 BIP_DOWN -> BIP_HOLD -> BIP_GO -> complete goal!
        t += 300L; controller.onFrame(stubFrame(ExerciseType.PUSHUP, MovementPhase.READY), t)
        t += 600L; controller.onFrame(stubFrame(ExerciseType.PUSHUP, MovementPhase.BOTTOM), t)
        t += 1100L; controller.onFrame(stubFrame(ExerciseType.PUSHUP, MovementPhase.BOTTOM), t)
        t += 400L
        val finishOut = controller.onFrame(stubFrame(ExerciseType.PUSHUP, MovementPhase.READY, repCompletedNow = true), t)!!
        assertNotNull("Expected Bip & Go challenge completion after 2 synced cycles", finishOut.completedResult)
        assertTrue(finishOut.completedResult!!.completedGoal)
        assertEquals(2.0, finishOut.completedResult!!.scoreValue, 0.01)
        assertTrue(finishOut.beepsToPlay.contains(BeepType.COMPLETE))

        // Verify 2 consecutive missed cycles terminate cleanly
        val missCtrl = ChallengeController()
        var mt = advancePastCountdown(missCtrl, cfg.copy(bipGoTargetCycles = 10))
        var ended = false
        for (step in 0 until 40) {
            mt += 500L
            val o = missCtrl.onFrame(stubFrame(ExerciseType.PUSHUP, MovementPhase.READY, repCompletedNow = false), mt)
            if (o?.completedResult != null) {
                ended = true
                assertFalse(o.completedResult!!.completedGoal)
                break
            }
        }
        assertTrue("Bip & Go must end cleanly after 2 missed cycles", ended)
    }

    @Test
    fun lucLeger_progressesStagesEveryFiveSyncedReps() {
        assertEquals(3.60f, ChallengeController.lucLegerCadenceSec(1), 0.01f)
        assertEquals(3.35f, ChallengeController.lucLegerCadenceSec(2), 0.01f)
        assertEquals(1.10f, ChallengeController.lucLegerCadenceSec(12), 0.01f)

        val controller = ChallengeController()
        val cfg = ChallengeConfig(type = ChallengeType.LUC_LEGER_CADENCE, exercise = ExerciseType.SQUAT)
        var t = 1000L
        controller.start(cfg, t)
        repeat(3) { t += 1000L; controller.onFrame(null, t) }

        // Complete 5 synced windows at stage 1 (3.6s each)
        var maxStage = 1
        repeat(5) {
            t += 1000L
            controller.onFrame(stubFrame(ExerciseType.SQUAT, repCompletedNow = true), t)
            t += 1400L
            controller.onFrame(stubFrame(ExerciseType.SQUAT, repCompletedNow = false), t)
            t += 1300L
            val out = controller.onFrame(stubFrame(ExerciseType.SQUAT, repCompletedNow = false), t)!!
            maxStage = maxOf(maxStage, out.snapshot.currentStage)
        }
        assertEquals("After 5 synced reps Luc Léger must advance to Stage 2", 2, maxStage)
    }

    @Test
    fun plankMusicHold_runsOnlyOnValidHoldWithGraceAndSprint() {
        val controller = ChallengeController()
        val cfg = ChallengeConfig(
            type = ChallengeType.PLANK_MUSIC_HOLD,
            exercise = ExerciseType.PLANK,
            plankTargetSec = 20,
            musicTrack = WorkoutMusicTrack.FOCUS_SYNTH
        )
        var t = 1000L
        controller.start(cfg, t)
        repeat(3) { t += 1000L; controller.onFrame(null, t) }

        // Hold cleanly for 6 seconds -> timer advances to 6s, music plays, sprint activates at <= 15s left!
        var lastOut = controller.onFrame(stubFrame(ExerciseType.PLANK, MovementPhase.HOLD), t)!!
        repeat(6) {
            t += 1000L
            lastOut = controller.onFrame(stubFrame(ExerciseType.PLANK, MovementPhase.HOLD), t)!!
        }
        assertEquals(ChallengePhase.HOLDING, lastOut.snapshot.phase)
        assertTrue("Music must play during valid plank hold", lastOut.musicShouldPlay)
        assertTrue("Final 15s sprint must trigger with 14s remaining", lastOut.musicFinalSprint)
        assertEquals(6.0, lastOut.snapshot.currentScoreValue, 0.2)

        // Break posture for 1.0s (within 2.5s grace): timer must NOT increase, music ducked
        t += 1000L
        val graceOut = controller.onFrame(stubFrame(ExerciseType.PLANK, MovementPhase.PAUSE), t)!!
        assertEquals(ChallengePhase.PAUSED_POSTURE, graceOut.snapshot.phase)
        assertTrue(graceOut.musicDucked)
        assertEquals(6.0, graceOut.snapshot.currentScoreValue, 0.2)

        // Continue broken posture past 2.5s grace -> music pauses & warning beep fires
        t += 1000L; controller.onFrame(stubFrame(ExerciseType.PLANK, MovementPhase.PAUSE), t)
        t += 1000L
        val warnOut = controller.onFrame(stubFrame(ExerciseType.PLANK, MovementPhase.PAUSE), t)!!
        assertFalse("Music must pause after grace window expires", warnOut.musicShouldPlay)
        assertTrue("Expected warning beep after grace period", warnOut.beepsToPlay.contains(BeepType.WARNING))

        // Resume valid hold for 14 more seconds -> reaches 20s target & completes!
        var completed = false
        repeat(15) {
            t += 1000L
            val o = controller.onFrame(stubFrame(ExerciseType.PLANK, MovementPhase.HOLD), t)
            if (o?.completedResult != null) {
                completed = true
                assertTrue(o.completedResult!!.completedGoal)
                assertTrue(o.beepsToPlay.contains(BeepType.COMPLETE))
            }
        }
        assertTrue("Plank musical challenge must complete at 20s target", completed)
    }

    @Test
    fun targetRepsAmrapTabataAndEmom_allCompleteAccurately() {
        // 1. TARGET_REPS_RACE (25 reps)
        run {
            val ctrl = ChallengeController()
            val cfg = ChallengeConfig(type = ChallengeType.TARGET_REPS_RACE, exercise = ExerciseType.PUSHUP, targetRepsGoal = 5)
            var t = 1000L
            ctrl.start(cfg, t)
            repeat(3) { t += 1000L; ctrl.onFrame(null, t) }

            var resultScore = 0.0
            repeat(5) {
                t += 1000L
                val o = ctrl.onFrame(stubFrame(ExerciseType.PUSHUP, repCompletedNow = true), t)!!
                o.completedResult?.let { resultScore = it.scoreValue }
            }
            assertTrue("Target reps race should complete with elapsed seconds (~5s)", resultScore in 4.0..6.5)
        }

        // 2. AMRAP_CHRONO (10s test config)
        run {
            val ctrl = ChallengeController()
            val cfg = ChallengeConfig(type = ChallengeType.AMRAP_CHRONO, exercise = ExerciseType.SQUAT, amrapDurationSec = 10)
            var t = 1000L
            ctrl.start(cfg, t)
            repeat(3) { t += 1000L; ctrl.onFrame(null, t) }

            var countdownBeeps = 0
            var finalReps = 0.0
            for (s in 1..11) {
                t += 1000L
                val o = ctrl.onFrame(stubFrame(ExerciseType.SQUAT, repCompletedNow = (s % 2 == 0)), t)
                if (o != null) {
                    if (o.beepsToPlay.contains(BeepType.COUNTDOWN)) countdownBeeps++
                    o.completedResult?.let { finalReps = it.scoreValue }
                }
            }
            assertTrue("Expected countdown beeps in final 10s of AMRAP", countdownBeeps >= 8)
            assertEquals(5.0, finalReps, 0.01)
        }

        // 3. TABATA_HIIT (8 rounds of 20s/10s)
        run {
            val ctrl = ChallengeController()
            val cfg = ChallengeConfig(
                type = ChallengeType.TABATA_HIIT,
                exercise = ExerciseType.BURPEE,
                tabataRounds = 8,
                tabataWorkSec = 20,
                tabataRestSec = 10
            )
            var t = 1000L
            ctrl.start(cfg, t)
            repeat(3) { t += 1000L; ctrl.onFrame(null, t) }

            var sawRestDucked = false
            var completedTabata = false
            var totalTabataReps = 0.0
            // 8 work (160s) + 7 rest (70s) = 230s
            for (sec in 1..235) {
                t += 1000L
                val isRep = sec % 5 == 0
                val o = ctrl.onFrame(stubFrame(ExerciseType.BURPEE, repCompletedNow = isRep), t) ?: break
                if (o.snapshot.phase == ChallengePhase.REST && o.musicDucked) sawRestDucked = true
                if (o.completedResult != null) {
                    completedTabata = true
                    totalTabataReps = o.completedResult!!.scoreValue
                    assertEquals(8, o.snapshot.currentStage)
                    break
                }
            }
            assertTrue("Tabata must duck music during 10s rest phases", sawRestDucked)
            assertTrue("Tabata must complete after 8 rounds", completedTabata)
            assertTrue("Tabata must accumulate camera reps during work rounds", totalTabataReps >= 25.0)
        }

        // 4. EMOM_CHALLENGE (2 min, 5 reps/min -> auto earned rest!)
        run {
            val ctrl = ChallengeController()
            val cfg = ChallengeConfig(
                type = ChallengeType.EMOM_CHALLENGE,
                exercise = ExerciseType.PUSHUP,
                emomMinutes = 2,
                emomRepsPerMin = 5
            )
            var t = 1000L
            ctrl.start(cfg, t)
            repeat(3) { t += 1000L; ctrl.onFrame(null, t) }

            var enteredEarnedRest = false
            var emomDone = false
            for (sec in 1..125) {
                t += 1000L
                val secInMin = ((sec - 1) % 60) + 1
                val doRep = secInMin in 1..5
                val o = ctrl.onFrame(stubFrame(ExerciseType.PUSHUP, repCompletedNow = doRep), t) ?: break
                if (o.snapshot.phase == ChallengePhase.REST && o.musicDucked) {
                    enteredEarnedRest = true
                }
                if (o.completedResult != null) {
                    emomDone = true
                    assertTrue(o.completedResult!!.completedGoal)
                    assertEquals(10.0, o.completedResult!!.scoreValue, 0.01)
                    break
                }
            }
            assertTrue("EMOM must switch to earned REST phase once minute reps are met", enteredEarnedRest)
            assertTrue("EMOM must complete after all minutes finish", emomDone)
        }
    }

    @Test
    fun audioEngine_synthesizesValidNonZeroNonClippingPcmForAllBeepsAndMusicTracks() {
        fun assertValidPcm(label: String, pcm: ShortArray, minSamples: Int) {
            assertTrue("$label sample count too small: ${pcm.size}", pcm.size >= minSamples)
            var sumSq = 0.0
            var maxAbs = 0
            for (s in pcm) {
                val a = abs(s.toInt())
                if (a > maxAbs) maxAbs = a
                sumSq += s.toDouble() * s.toDouble()
            }
            val rms = sqrt(sumSq / pcm.size)
            assertTrue("$label RMS too quiet (silent?): $rms", rms > 500.0)
            assertTrue("$label clipped 16-bit PCM! peak=$maxAbs", maxAbs < Short.MAX_VALUE)
        }

        // Pure tone helper
        val tone = WorkoutAudioEngine.generateTonePcm(440.0, durationMs = 100)
        assertValidPcm("Tone 440Hz", tone, 4400)

        // All 6 cadence/cue beeps
        for (beep in BeepType.entries) {
            val pcm = WorkoutAudioEngine.generateBeepPcm(beep)
            assertValidPcm("Beep ${beep.name}", pcm, 2500)
        }

        // All 4 procedural music tracks (both standard groove & finalSprint boost!)
        val proceduralTracks = listOf(
            WorkoutMusicTrack.TECHNO_PULSE,
            WorkoutMusicTrack.FOCUS_SYNTH,
            WorkoutMusicTrack.HIIT_DRIVE,
            WorkoutMusicTrack.ZEN_BREATH
        )
        for (track in proceduralTracks) {
            val normalBar = WorkoutAudioEngine.renderBeatBarPcm(track, barIndex = 0, finalSprint = false)
            val sprintBar = ProceduralAudioSynth.renderBeatBarPcm(track, barIndex = 3, finalSprint = true)
            val expectedSamples = ((ProceduralAudioSynth.SAMPLE_RATE * 240.0) / track.bpm).toInt()
            assertEquals("Bar length mismatch for ${track.name}", expectedSamples, normalBar.size, 2)
            assertValidPcm("${track.name} normal", normalBar, expectedSamples - 4)
            assertValidPcm("${track.name} sprint", sprintBar, expectedSamples - 4)
        }

        // NONE & CUSTOM_LOCAL return empty procedural buffer cleanly
        assertEquals(0, WorkoutAudioEngine.renderBeatBarPcm(WorkoutMusicTrack.NONE, 0).size)
        assertEquals(0, WorkoutAudioEngine.renderBeatBarPcm(WorkoutMusicTrack.CUSTOM_LOCAL, 0).size)
    }

    private fun assertEquals(message: String, expected: Int, actual: Int, tolerance: Int) {
        assertTrue("$message: expected $expected ± $tolerance, got $actual", abs(expected - actual) <= tolerance)
    }
}
