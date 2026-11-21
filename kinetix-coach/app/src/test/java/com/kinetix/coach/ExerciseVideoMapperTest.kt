package com.kinetix.coach

import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.ui.components.CoachMarkdownParser
import com.kinetix.coach.ui.components.CoachTextBlock
import com.kinetix.coach.vision.ExerciseMuscleFilter
import com.kinetix.coach.vision.ExerciseVideoMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExerciseVideoMapperTest {

    private fun resolveVideosDir(): File {
        val candidates = listOf(
            File("src/main/assets/videos"),
            File("app/src/main/assets/videos")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate assets/videos directory from ${File(".").absolutePath}")
    }

    @Test
    fun allSixteenVisionExercises_mapToDistinctExistingVisionMp4sWithCompleteGuides() {
        val videosDir = resolveVideosDir()
        val seenFiles = mutableSetOf<String>()

        for (exercise in ExerciseType.entries) {
            val guide = ExerciseVideoMapper.forVisionExercise(exercise)
            assertTrue(
                "Vision exercise ${exercise.name} should map to vision_*.mp4, got ${guide.videoFileName}",
                guide.videoFileName.startsWith("vision_") && guide.videoFileName.endsWith(".mp4")
            )
            assertTrue(
                "Duplicate video ${guide.videoFileName} for ${exercise.name}",
                seenFiles.add(guide.videoFileName)
            )
            val file = File(videosDir, guide.videoFileName)
            assertTrue("Missing asset file: ${file.absolutePath}", file.exists())
            assertTrue("Video file too small (${file.length()} bytes): ${file.name}", file.length() > 20_000L)
            assertEquals("Expected 4 execution phases for ${exercise.name}", 4, guide.phases.size)
            assertTrue("Expected >= 3 Do/Avoid checklist pairs for ${exercise.name}", guide.checklist.size >= 3)
            assertTrue("Breathing cue blank for ${exercise.name}", guide.breathingFr.isNotBlank())
            assertTrue("Tempo blank for ${exercise.name}", guide.tempo.isNotBlank())
        }

        assertEquals(16, seenFiles.size)
    }

    @Test
    fun allFortyTwoStudioVideoFiles_existOnDiskAndAreValidSize() {
        val videosDir = resolveVideosDir()
        assertEquals(42, ExerciseVideoMapper.ALL_VIDEO_FILES.size)

        for (fileName in ExerciseVideoMapper.ALL_VIDEO_FILES) {
            val f = File(videosDir, fileName)
            assertTrue("Expected studio asset ${f.absolutePath} to exist", f.exists())
            assertTrue("Expected ${f.name} > 20 KB, got ${f.length()}", f.length() > 20_000L)
        }
    }

    @Test
    fun frenchKeywordMappings_resolveExactMovementArchetypes() {
        val cases = listOf(
            Triple("Squat arrière barre haute (High-Bar)", "Quadriceps", "vision_squat.mp4"),
            Triple("Front Squat (Squat avant)", "Quadriceps", "ex_front_squat.mp4"),
            Triple("Goblet Squat haltère / kettlebell", "Quadriceps", "ex_front_squat.mp4"),
            Triple("Squat Bulgare (Bulgarian Split Squat)", "Quadriceps", "vision_bulgarian_squat.mp4"),
            Triple("Presse à cuisses inclinée 45°", "Quadriceps", "ex_leg_press.mp4"),
            Triple("Leg Extension (Extension de jambes)", "Quadriceps", "ex_leg_extension.mp4"),
            Triple("Fentes marchées haltères", "Quadriceps", "vision_lunge.mp4"),
            Triple("Soulevé de terre conventionnel (Deadlift)", "Chaîne postérieure", "vision_deadlift.mp4"),
            Triple("Soulevé de terre Roumain (RDL barre)", "Ischio-jambiers", "ex_romanian_deadlift.mp4"),
            Triple("Leg Curl assis (Seated Leg Curl)", "Ischio-jambiers", "ex_leg_curl.mp4"),
            Triple("Hip Thrust barre", "Fessiers", "ex_hip_thrust.mp4"),
            Triple("Pont fessier au sol (Glute Bridge)", "Fessiers", "vision_glute_bridge.mp4"),
            Triple("Extensions mollets debout haltère", "Mollets", "ex_calf_raise.mp4"),
            Triple("Développé couché barre", "Pectoraux", "ex_bench_press.mp4"),
            Triple("Développé incliné haltères 30°", "Pectoraux", "ex_incline_press.mp4"),
            Triple("Écartés poulie vis-à-vis", "Pectoraux", "ex_chest_fly.mp4"),
            Triple("Pompes classiques au sol", "Pectoraux", "vision_pushup.mp4"),
            Triple("Tractions pronation prise large", "Dos", "vision_pull_up.mp4"),
            Triple("Tirage vertical poulie haute (Lat Pulldown)", "Dos", "ex_lat_pulldown.mp4"),
            Triple("Tirage horizontal poulie basse", "Dos", "ex_seated_cable_row.mp4"),
            Triple("Rowing barre buste penché", "Dos", "vision_bent_row.mp4"),
            Triple("Développé militaire barre debout", "Épaules", "vision_overhead_press.mp4"),
            Triple("Élévations latérales haltères", "Épaules", "vision_lateral_raise.mp4"),
            Triple("Élévations frontales haltères", "Épaules", "ex_front_raise.mp4"),
            Triple("Oiseau buste penché haltères", "Épaules", "ex_rear_delt_fly.mp4"),
            Triple("Face Pull à la poulie haute corde", "Épaules", "ex_face_pull.mp4"),
            Triple("Shrugs haltères lourds", "Épaules", "ex_shrugs.mp4"),
            Triple("Curl biceps barre EZ", "Biceps", "vision_bicep_curl.mp4"),
            Triple("Curl marteau haltères (Hammer Curl)", "Biceps", "ex_hammer_curl.mp4"),
            Triple("Wrist Curl haltères (Flexion poignets)", "Avant-bras", "ex_hammer_curl.mp4"),
            Triple("Extensions triceps poulie haute corde", "Triceps", "ex_tricep_pushdown.mp4"),
            Triple("Barre au front (Skull Crusher)", "Triceps", "ex_skull_crusher.mp4"),
            Triple("Dips barres parallèles", "Triceps", "vision_tricep_dip.mp4"),
            Triple("Gainage planche sur avant-bras", "Gainage", "vision_plank.mp4"),
            Triple("Crunch abdominal contrôlé", "Gainage", "ex_crunch.mp4"),
            Triple("Relevé de jambes au sol", "Gainage", "vision_leg_raise.mp4"),
            Triple("Russian Twist avec disque", "Gainage", "ex_russian_twist.mp4"),
            Triple("Mountain Climbers explosifs", "Cardio / Plyo", "ex_mountain_climber.mp4"),
            Triple("Burpees complets", "Cardio / Plyo", "vision_burpee.mp4"),
            Triple("Jumping Jacks rythme élevé", "Cardio / Plyo", "vision_jumping_jack.mp4"),
            Triple("Kettlebell Swing style Hardstyle Russe", "Kettlebell", "ex_kettlebell_swing.mp4"),
            Triple("Box Jump (Saut sur boîte)", "Plyométrie", "ex_box_jump.mp4"),
            Triple("Farmer's Walk haltères lourds", "Avant-bras", "ex_farmer_walk.mp4"),
            Triple("Mobilité hanches 90/90 et ouverture thoracique", "Mobilité", "ex_mobility_stretch.mp4")
        )

        for ((title, subcat, expectedFile) in cases) {
            val actual = ExerciseVideoMapper.forExerciseMetadata(title, subcat).videoFileName
            assertEquals("Mismatch for '$title' [$subcat]", expectedFile, actual)
        }
    }

    @Test
    fun muscleFilterChips_coverAllNineteenKnowledgeBaseSubcategories() {
        val allSubcategories = listOf(
            "Quadriceps", "Dos", "Pectoraux", "Gainage", "Épaules", "Cardio / Plyo",
            "Mobilité", "Fessiers", "Biceps", "Ischio-jambiers", "Kettlebell",
            "Triceps", "Calisthénie", "Mollets", "Chaîne postérieure", "Plyométrie",
            "Avant-bras", "Haltérophilie", "Poulie double + Banc"
        )
        val specificFilters = ExerciseMuscleFilter.entries.filter { it != ExerciseMuscleFilter.ALL }

        for (sc in allSubcategories) {
            assertTrue(
                "Subcategory '$sc' was not matched by any muscle filter chip",
                specificFilters.any { it.matchesSubcategory(sc) }
            )
            // Also guarantee fallback video resolves to an existing studio MP4
            val fallbackGuide = ExerciseVideoMapper.forExerciseMetadata("Exercice spécialisé inconnu", sc)
            assertTrue(fallbackGuide.videoFileName in ExerciseVideoMapper.ALL_VIDEO_FILES)
        }
    }

    @Test
    fun coachMarkdownParser_parsesHeadingsBulletsNumberedAndInlineBoldWithoutLeakingSyntax() {
        val sample = """
            ### Plan d'action biomécanique
            Voici ton **protocole personnalisé** pour le `Squat` :
            - Garde les **genoux alignés** avec les orteils
            * Maintiens un *tempo 3-1-1* contrôlé
            1. Échauffement **RAMP** 8 minutes
            2. 4 séries de 6 répétitions à **RPE 8**
        """.trimIndent()

        val blocks = CoachMarkdownParser.parseBlocks(sample)
        assertEquals(6, blocks.size)
        assertTrue(blocks[0] is CoachTextBlock.Heading)
        assertEquals("Plan d'action biomécanique", (blocks[0] as CoachTextBlock.Heading).text)
        assertTrue(blocks[1] is CoachTextBlock.Paragraph)
        assertTrue(blocks[2] is CoachTextBlock.Bullet)
        assertTrue(blocks[3] is CoachTextBlock.Bullet)
        assertTrue(blocks[4] is CoachTextBlock.Numbered)
        assertEquals("1.", (blocks[4] as CoachTextBlock.Numbered).index)
        assertTrue(blocks[5] is CoachTextBlock.Numbered)

        val inlineTokens = CoachMarkdownParser.parseInline("Garde les **genoux alignés** sur `3-1-1`")
        assertTrue(inlineTokens.any { it.text == "genoux alignés" && it.bold })
        assertTrue(inlineTokens.any { it.text == "3-1-1" && it.code })
        assertFalse(inlineTokens.any { it.text.contains("**") || it.text.contains("`") })
    }
}
