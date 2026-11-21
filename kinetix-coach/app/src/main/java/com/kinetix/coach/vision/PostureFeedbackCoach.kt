package com.kinetix.coach.vision

import kotlin.math.abs

/**
 * Candidate posture issue detected on a single frame before temporal persistence & anti-spam gating.
 */
data class RawPostureIssue(
    val key: String,
    val correction: AngleCorrection,
    val scorePenalty: Int,
    val feedbackMsgFr: String? = null,
    val isDepthCue: Boolean = false
)

/**
 * Filtered, flicker-free posture feedback output for a single frame.
 */
data class FilteredPostureFeedback(
    val confirmedCorrections: List<AngleCorrection>,
    val totalScorePenalty: Int,
    val primaryFaultMsgFr: String?,
    val postureVoiceCueFr: String?,
    val newlyConfirmedFaultMessages: List<String>
)

/**
 * Pro anti-spam posture coaching gate ensuring calm, authoritative, zero-flicker feedback:
 *
 * 1. **Temporal Onset Persistence**: A posture issue must persist continuously for
 *    [FAULT_ONSET_MS] (360 ms) for safety FAULTs or [CAUTION_ONSET_MS] (560 ms) for
 *    CAUTIONs before appearing visually or penalizing the form score.
 * 2. **Minimum Display Hold & Dropout Grace**: Once shown, a cue stays steady for at least
 *    [MIN_DISPLAY_HOLD_MS] (950 ms) and tolerates 1-2 frame dropout glitches ([DROPOUT_GRACE_MS])
 *    without blinking on/off.
 * 3. **Post-Clear Quiet Cooldown**: After the athlete corrects a minor CAUTION, suppresses
 *    immediate re-trigger chatter for [CAUTION_RETRIGGER_COOLDOWN_MS] (2200 ms).
 * 4. **Single-Focus Visual Priority**: Displays at most 1 primary correction (or 2 only if
 *    both are severe FAULTs on distinct joints) to keep the AR skeleton crisp and readable.
 * 5. **Strict Voice Anti-Spam Scheduler**:
 *    - Only voices confirmed `FAULT` issues (never nags for minor `CAUTION`s).
 *    - Global cooldown >= 9.5 s between any posture voice cues.
 *    - Per-fault category cooldown >= 20 s AND >= 2 reps elapsed (for dynamic exercises).
 *    - Respects a 1.8 s quiet window after every rep counter announcement.
 *    - Rolling hard cap of max 3 posture voice cues per 60 seconds.
 */
class PostureFeedbackCoach {

    companion object {
        const val FAULT_ONSET_MS = 360L
        const val CAUTION_ONSET_MS = 560L
        const val DROPOUT_GRACE_MS = 140L
        const val MIN_DISPLAY_HOLD_MS = 950L
        const val CAUTION_RETRIGGER_COOLDOWN_MS = 2200L
        const val FAULT_RETRIGGER_COOLDOWN_MS = 800L

        const val VOICE_GLOBAL_COOLDOWN_MS = 9500L
        const val VOICE_SAME_KEY_COOLDOWN_MS = 20000L
        const val VOICE_AFTER_REP_QUIET_MS = 1800L
        const val VOICE_MIN_REPS_SAME_KEY = 2
        const val VOICE_MAX_PER_MINUTE = 3
        const val VOICE_WINDOW_MS = 60000L
    }

    private data class TrackedIssue(
        var firstSeenTsMs: Long,
        var lastSeenTsMs: Long,
        var activatedTsMs: Long = Long.MIN_VALUE,
        var clearedTsMs: Long = Long.MIN_VALUE,
        var isActive: Boolean = false,
        var lastEpisodeRepCount: Int = -1,
        var lastIssue: RawPostureIssue
    )

    private val tracked = linkedMapOf<String, TrackedIssue>()
    private var lastAnyVoiceTsMs: Long = Long.MIN_VALUE
    private var lastRepAnnouncementTsMs: Long = Long.MIN_VALUE
    private val lastVoiceByKeyTsMs = hashMapOf<String, Long>()
    private val lastVoiceByKeyRep = hashMapOf<String, Int>()
    private val recentVoiceTimestamps = ArrayDeque<Long>(8)

    fun reset() {
        tracked.clear()
        lastAnyVoiceTsMs = Long.MIN_VALUE
        lastRepAnnouncementTsMs = Long.MIN_VALUE
        lastVoiceByKeyTsMs.clear()
        lastVoiceByKeyRep.clear()
        recentVoiceTimestamps.clear()
    }

    fun notifyRepCompleted(timestampMs: Long) {
        lastRepAnnouncementTsMs = timestampMs
    }

    fun evaluate(
        rawIssues: List<RawPostureIssue>,
        timestampMs: Long,
        repCount: Int,
        isIsometric: Boolean,
        deterministicPassThrough: Boolean
    ): FilteredPostureFeedback {
        if (deterministicPassThrough) {
            val ordered = sortIssues(rawIssues)
            val totalPen = ordered.sumOf { it.scorePenalty }
            val primaryFault = ordered.firstOrNull { it.feedbackMsgFr != null }?.feedbackMsgFr
            val newlyConfirmed = ordered.mapNotNull { it.feedbackMsgFr }.distinct()
            val voice = ordered.firstOrNull { it.correction.severity == FormQuality.FAULT }?.correction?.voiceCueFr
                ?: primaryFault
            return FilteredPostureFeedback(
                confirmedCorrections = ordered.map { it.correction },
                totalScorePenalty = totalPen,
                primaryFaultMsgFr = primaryFault,
                postureVoiceCueFr = voice,
                newlyConfirmedFaultMessages = newlyConfirmed
            )
        }

        val presentByKey = LinkedHashMap<String, RawPostureIssue>(rawIssues.size)
        for (issue in rawIssues) {
            val existing = presentByKey[issue.key]
            if (existing == null || severityRank(issue.correction.severity) < severityRank(existing.correction.severity)) {
                presentByKey[issue.key] = issue
            }
        }

        val newlyConfirmedMsgs = mutableListOf<String>()
        val activeIssues = mutableListOf<RawPostureIssue>()

        // 1. Update currently present candidate issues
        for ((key, issue) in presentByKey) {
            val entry = tracked[key]
            val isFault = issue.correction.severity == FormQuality.FAULT
            val onsetMs = if (isFault) FAULT_ONSET_MS else CAUTION_ONSET_MS
            val retriggerCooldown = if (isFault) FAULT_RETRIGGER_COOLDOWN_MS else CAUTION_RETRIGGER_COOLDOWN_MS

            if (entry == null) {
                tracked[key] = TrackedIssue(
                    firstSeenTsMs = timestampMs,
                    lastSeenTsMs = timestampMs,
                    lastIssue = issue
                )
            } else {
                entry.lastIssue = issue
                val gap = timestampMs - entry.lastSeenTsMs
                if (!entry.isActive) {
                    // Check quiet cooldown after previous clear
                    val inPostClearQuiet = entry.clearedTsMs != Long.MIN_VALUE &&
                        (timestampMs - entry.clearedTsMs) < retriggerCooldown
                    if (inPostClearQuiet) {
                        entry.firstSeenTsMs = timestampMs
                        entry.lastSeenTsMs = timestampMs
                    } else {
                        if (gap > DROPOUT_GRACE_MS) {
                            entry.firstSeenTsMs = timestampMs
                        }
                        entry.lastSeenTsMs = timestampMs
                        if ((timestampMs - entry.firstSeenTsMs) >= onsetMs) {
                            entry.isActive = true
                            entry.activatedTsMs = timestampMs
                            entry.lastEpisodeRepCount = repCount
                            issue.feedbackMsgFr?.let { newlyConfirmedMsgs.add(it) }
                        }
                    }
                } else {
                    entry.lastSeenTsMs = timestampMs
                    // Count at most one fault episode per rep if sustained across multiple reps
                    if (!isIsometric && repCount > entry.lastEpisodeRepCount) {
                        entry.lastEpisodeRepCount = repCount
                        issue.feedbackMsgFr?.let { newlyConfirmedMsgs.add(it) }
                    }
                }
            }
        }

        // 2. Age out absent issues while respecting dropout grace & minimum display hold
        val keysToRemove = mutableListOf<String>()
        for ((key, entry) in tracked) {
            val present = key in presentByKey
            if (present) {
                if (entry.isActive) {
                    activeIssues.add(entry.lastIssue)
                }
            } else {
                val absentMs = timestampMs - entry.lastSeenTsMs
                if (entry.isActive) {
                    val displayedMs = timestampMs - entry.activatedTsMs
                    if (absentMs <= DROPOUT_GRACE_MS || displayedMs < MIN_DISPLAY_HOLD_MS) {
                        // Hold steadily so HUD / skeleton never flickers
                        activeIssues.add(entry.lastIssue)
                    } else {
                        entry.isActive = false
                        entry.clearedTsMs = timestampMs
                    }
                } else {
                    val sinceClear = if (entry.clearedTsMs != Long.MIN_VALUE) timestampMs - entry.clearedTsMs else absentMs
                    if (absentMs > DROPOUT_GRACE_MS && sinceClear > CAUTION_RETRIGGER_COOLDOWN_MS) {
                        keysToRemove.add(key)
                    }
                }
            }
        }
        keysToRemove.forEach { tracked.remove(it) }

        if (activeIssues.isEmpty()) {
            return FilteredPostureFeedback(
                confirmedCorrections = emptyList(),
                totalScorePenalty = 0,
                primaryFaultMsgFr = null,
                postureVoiceCueFr = null,
                newlyConfirmedFaultMessages = newlyConfirmedMsgs
            )
        }

        val ordered = sortIssues(activeIssues)

        // Visual focus cap: 1 primary cue, or 2 ONLY if both are severe FAULTs on distinct joints
        val visibleIssues = buildList {
            val first = ordered.first()
            add(first)
            if (first.correction.severity == FormQuality.FAULT) {
                val secondSevere = ordered.drop(1).firstOrNull {
                    it.correction.severity == FormQuality.FAULT &&
                        it.correction.jointIndex != first.correction.jointIndex
                }
                if (secondSevere != null) add(secondSevere)
            }
        }

        val totalPenalty = visibleIssues.sumOf { it.scorePenalty }
        val primaryFaultMsg = visibleIssues.firstOrNull { it.feedbackMsgFr != null }?.feedbackMsgFr

        // Voice anti-spam scheduling: ONLY confirmed FAULT issues can trigger voice coaching
        val topFaultIssue = visibleIssues.firstOrNull { it.correction.severity == FormQuality.FAULT }
        val voiceCue = if (topFaultIssue != null && canSpeakPostureFault(topFaultIssue.key, timestampMs, repCount, isIsometric)) {
            markSpoken(topFaultIssue.key, timestampMs, repCount)
            topFaultIssue.correction.voiceCueFr
        } else {
            null
        }

        return FilteredPostureFeedback(
            confirmedCorrections = visibleIssues.map { it.correction },
            totalScorePenalty = totalPenalty,
            primaryFaultMsgFr = primaryFaultMsg,
            postureVoiceCueFr = voiceCue,
            newlyConfirmedFaultMessages = newlyConfirmedMsgs
        )
    }

    private fun canSpeakPostureFault(
        key: String,
        timestampMs: Long,
        repCount: Int,
        isIsometric: Boolean
    ): Boolean {
        // 1. Quiet window immediately after a rep counter announcement
        if (lastRepAnnouncementTsMs != Long.MIN_VALUE &&
            (timestampMs - lastRepAnnouncementTsMs) < VOICE_AFTER_REP_QUIET_MS
        ) {
            return false
        }

        // 2. Global posture coaching cooldown (calm pro cadence)
        if (lastAnyVoiceTsMs != Long.MIN_VALUE &&
            (timestampMs - lastAnyVoiceTsMs) < VOICE_GLOBAL_COOLDOWN_MS
        ) {
            return false
        }

        // 3. Per-minute hard cap (max 3 posture cues / 60s)
        while (recentVoiceTimestamps.isNotEmpty() && (timestampMs - recentVoiceTimestamps.first()) > VOICE_WINDOW_MS) {
            recentVoiceTimestamps.removeFirst()
        }
        if (recentVoiceTimestamps.size >= VOICE_MAX_PER_MINUTE) {
            return false
        }

        // 4. Same fault category cooldown + minimum reps separation
        val prevKeyTs = lastVoiceByKeyTsMs[key]
        if (prevKeyTs != null) {
            if ((timestampMs - prevKeyTs) < VOICE_SAME_KEY_COOLDOWN_MS) {
                return false
            }
            if (!isIsometric) {
                val prevRep = lastVoiceByKeyRep[key] ?: -10
                if ((repCount - prevRep) < VOICE_MIN_REPS_SAME_KEY) {
                    return false
                }
            }
        }
        return true
    }

    private fun markSpoken(key: String, timestampMs: Long, repCount: Int) {
        lastAnyVoiceTsMs = timestampMs
        lastVoiceByKeyTsMs[key] = timestampMs
        lastVoiceByKeyRep[key] = repCount
        recentVoiceTimestamps.addLast(timestampMs)
    }

    private fun sortIssues(issues: List<RawPostureIssue>): List<RawPostureIssue> =
        issues.sortedWith(
            compareBy<RawPostureIssue> { severityRank(it.correction.severity) }
                .thenByDescending { abs(it.correction.deltaDeg) }
        )

    private fun severityRank(q: FormQuality): Int = when (q) {
        FormQuality.FAULT -> 0
        FormQuality.CAUTION -> 1
        FormQuality.GOOD -> 2
    }
}
