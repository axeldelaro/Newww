package com.kinetix.coach.vision

import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.KnowledgeEntry
import java.text.Normalizer

data class ExecutionPhase(
    val number: Int,
    val title: String,
    val detail: String
)

data class FormCheckPair(
    val doCue: String,
    val avoidCue: String
)

data class ExerciseVideoGuide(
    val videoFileName: String,
    val patternTitleFr: String,
    val tempo: String,
    val breathingFr: String,
    val targetAnglesFr: String,
    val phases: List<ExecutionPhase>,
    val checklist: List<FormCheckPair>
)

enum class ExerciseMuscleFilter(val labelFr: String, val subcategories: Set<String>) {
    ALL("Tout", emptySet()),
    LEGS("Jambes", setOf("quadriceps", "fessiers", "ischio-jambiers", "mollets", "chaine posterieure")),
    CHEST("Pectoraux", setOf("pectoraux", "poulie double + banc")),
    BACK("Dos", setOf("dos")),
    SHOULDERS("Épaules", setOf("epaules")),
    ARMS("Bras", setOf("biceps", "triceps", "avant-bras")),
    ABS("Abdos", setOf("gainage")),
    CARDIO_MOBILITY(
        "Cardio / Mobilité",
        setOf("cardio / plyo", "mobilite", "kettlebell", "calisthenie", "plyometrie", "halterophilie")
    );

    fun matchesSubcategory(subcategory: String): Boolean {
        if (this == ALL) return true
        val norm = ExerciseVideoMapper.normalizeText(subcategory)
        return norm in subcategories
    }
}

/**
 * Deterministic 100% offline mapper connecting:
 *  - All 16 Vision AI exercises -> dedicated `vision_*.mp4` studio video & cues
 *  - All 911 Encyclopedia `exercice` entries -> most accurate video among all 42 studio MP4s
 */
object ExerciseVideoMapper {

    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9+\\s/-]+")

    fun normalizeText(raw: String): String {
        val decomposed = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "")
            .replace("œ", "oe")
            .replace("æ", "ae")
            .replace(NON_ALNUM_REGEX, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // -----------------------------------------------------------------------
    // Master Guides for ALL 42 Studio MP4 Videos
    // -----------------------------------------------------------------------
    private val GUIDE_BY_VIDEO: Map<String, ExerciseVideoGuide> = linkedMapOf(
        // === 16 VISION AI EXERCISES ===
        "vision_squat.mp4" to ExerciseVideoGuide(
            videoFileName = "vision_squat.mp4",
            patternTitleFr = "Squat arrière",
            tempo = "3-1-1",
            breathingFr = "Inspirez profondément avant la descente (Valsalva / gainage abdominal), expirez sur le dernier tiers de la remontée.",
            targetAnglesFr = "Genou 175° → 84° · Tronc neutre < 38°",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Pieds largeur d'épaules, pointes ouvertes à 15–30°, barre calée sur les trapèzes, regard fixe devant."),
                ExecutionPhase(2, "Phase excentrique", "Initiez simultanément par les hanches et les genoux sur 3 secondes en gardant la barre à la verticale du médio-pied."),
                ExecutionPhase(3, "Point bas / Contraction", "Cassez la parallèle (< 95° au genou), talons ancrés au sol, buste gainé sans arrondir les lombaires."),
                ExecutionPhase(4, "Phase concentrique & Respiration", "Poussez fort dans tout le pied, alignez genoux et orteils, expirez fort en passant le point critique.")
            ),
            checklist = listOf(
                FormCheckPair("Genoux suivent l'axe des orteils", "Valgus (genoux qui rentrent vers l'intérieur)"),
                FormCheckPair("Talons ancrés au sol sur toute la rep", "Décoller les talons ou basculer sur l'avant-pied"),
                FormCheckPair("Colonne neutre et caisson abdominal gainé", "Buste effondré vers l'avant ou dos rond en bas")
            )
        ),
        "vision_pushup.mp4" to ExerciseVideoGuide(
            videoFileName = "vision_pushup.mp4",
            patternTitleFr = "Pompes strictes",
            tempo = "2-1-1",
            breathingFr = "Inspirez en contrôlant la descente vers le sol, expirez en repoussant puissamment le sol.",
            targetAnglesFr = "Coude 172° → 82° · Alignement corps 180°",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Mains légèrement plus larges que les épaules, doigts ancrés, fessiers et abdos contractés pour un corps aligné à 180°."),
                ExecutionPhase(2, "Phase excentrique", "Descendez la poitrine en bloc sur 2 secondes avec les coudes orientés à ~45° du buste."),
                ExecutionPhase(3, "Point bas / Contraction", "Poitrine à 2 cm du sol, coudes < 95°, omoplates rétractées sans laisser tomber les hanches."),
                ExecutionPhase(4, "Phase concentrique & Respiration", "Repoussez le sol en expirant jusqu'à extension complète et légère protraction scapulaire en haut.")
            ),
            checklist = listOf(
                FormCheckPair("Coudes à 45° du buste (flèche)", "Coudes écartés à 90° en T (stress épaules)"),
                FormCheckPair("Ligne droite épaule-hanche-cheville (180°)", "Creuser les lombaires ou lever les fesses"),
                FormCheckPair("Amplitude complète poitrine proche du sol", "Demi-répétitions rapides sans contrôle")
            )
        ),
        "vision_lunge.mp4" to ExerciseVideoGuide(
            videoFileName = "vision_lunge.mp4",
            patternTitleFr = "Fentes contrôlées",
            tempo = "2-1-1",
            breathingFr = "Inspirez en descendant verticalement, expirez en poussant dans le talon avant pour remonter.",
            targetAnglesFr = "Genou avant 170° → 88° · Buste vertical",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Pas ample dans l'axe des hanches (rails parallèles), buste fier et regard droit devant."),
                ExecutionPhase(2, "Phase excentrique", "Descendez verticalement en fléchissant les deux genoux vers 90°, poids centré sur le pied avant."),
                ExecutionPhase(3, "Point bas / Contraction", "Genou arrière effleure le sol sans claquer, tibia avant quasi vertical ou légèrement avancé."),
                ExecutionPhase(4, "Phase concentrique & Respiration", "Poussez fort dans le pied avant en expirant pour revenir en extension complète.")
            ),
            checklist = listOf(
                FormCheckPair("Écart latéral largeur de bassin (stabilité)", "Marcher sur une seule ligne (perte d'équilibre)"),
                FormCheckPair("Genou avant aligné avec le 2e orteil", "Genou qui s'effondre vers l'intérieur"),
                FormCheckPair("Descente verticale et buste droit", "Taper le genou arrière brutalement au sol")
            )
        ),
        "vision_deadlift.mp4" to ExerciseVideoGuide(
            videoFileName = "vision_deadlift.mp4",
            patternTitleFr = "Soulevé de terre",
            tempo = "2-1-1",
            breathingFr = "Inspirez et bloquez le caisson abdominal avant d'arracher la barre, expirez au verrouillage ou en contrôle.",
            targetAnglesFr = "Hanche 82° → 176° · Barre collée aux tibias",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Barre au-dessus du milieu du pied, tibias en contact, dorsaux engagés (« casser la barre »), colonne neutre."),
                ExecutionPhase(2, "Phase excentrique / Mise en tension", "Mettez la barre en tension (enlevez le jeu), hanches hautes en charnière, épaules légèrement devant la barre."),
                ExecutionPhase(3, "Décollage & Passage des genoux", "Poussez le sol avec les jambes en gardant la barre collée aux tibias puis aux cuisses."),
                ExecutionPhase(4, "Verrouillage & Retour", "Contractez fort les fessiers pour verrouiller debout sans cambrer, puis redescendez par les hanches.")
            ),
            checklist = listOf(
                FormCheckPair("Barre glisse au contact des tibias et cuisses", "Barre éloignée devant le centre de gravité"),
                FormCheckPair("Dos parfaitement plat et dorsaux verrouillés", "Arrondir les lombaires dès le décollage"),
                FormCheckPair("Verrouillage neutre fessiers contractés", "Hyperextension lombaire exagérée en haut")
            )
        ),
        "vision_overhead_press.mp4" to ExerciseVideoGuide(
            videoFileName = "vision_overhead_press.mp4",
            patternTitleFr = "Développé militaire",
            tempo = "2-0-1",
            breathingFr = "Inspirez en rack claviculaire, gainage fessiers/abdos, expirez en poussant la barre au-dessus de la tête.",
            targetAnglesFr = "Coude 68° → 172° · Trajectoire verticale",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Barre reposant sur les deltoïdes antérieurs, avant-bras verticaux, fessiers et abdos contractés."),
                ExecutionPhase(2, "Phase concentrique", "Reculez légèrement le menton et poussez verticalement le long du visage."),
                ExecutionPhase(3, "Verrouillage overhead", "Avancez la tête « à travers la fenêtre », barre alignée au-dessus des oreilles et du médio-pied, coudes verrouillés."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Redescendez sous contrôle jusqu'aux clavicules en inspirant pour préparer la rep suivante.")
            ),
            checklist = listOf(
                FormCheckPair("Fessiers et abdos serrés (bassin neutre)", "Cambrer fortement le bas du dos sous la charge"),
                FormCheckPair("Trajectoire verticale au plus près du visage", "Pousser la barre vers l'avant en arc"),
                FormCheckPair("Poignets empilés sur les avant-bras", "Casser les poignets en hyperextension")
            )
        ),
        "vision_bicep_curl.mp4" to ExerciseVideoGuide(
            videoFileName = "vision_bicep_curl.mp4",
            patternTitleFr = "Curl biceps",
            tempo = "3-1-1",
            breathingFr = "Expirez lors de la flexion vers les épaules, inspirez sur la phase excentrique lente de 3 secondes.",
            targetAnglesFr = "Coude 168° → 38° · Humérus fixe",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Debout gainé, épaules basses et en arrière, bras tendus, paumes en supination."),
                ExecutionPhase(2, "Phase concentrique", "Fléchissez strictement les coudes en gardant les bras collés près des côtes."),
                ExecutionPhase(3, "Contraction maximale", "Serrez fort les biceps pendant 1 seconde en haut (< 45°) sans laisser les coudes partir trop devant."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Redescendez lentement sur 3 secondes jusqu'à quasi-extension complète en inspirant.")
            ),
            checklist = listOf(
                FormCheckPair("Coudes fixes le long du buste", "Balancer le dos pour donner de l'élan"),
                FormCheckPair("Poignets neutres et solides", "Enrouler excessivement les poignets en haut"),
                FormCheckPair("Descente lente et maîtrisée (3s)", "Lâcher la charge en chute libre")
            )
        ),
        "vision_tricep_dip.mp4" to ExerciseVideoGuide(
            videoFileName = "vision_tricep_dip.mp4",
            patternTitleFr = "Dips barres parallèles",
            tempo = "2-1-1",
            breathingFr = "Inspirez en contrôlant la descente, expirez en verrouillant les triceps vers le haut.",
            targetAnglesFr = "Coude 172° → 86° · Épaules abaissées",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "En appui bras tendus, épaules activement abaissées loin des oreilles, jambes gainées."),
                ExecutionPhase(2, "Phase excentrique", "Descendez sur 2 secondes en gardant les coudes proches du corps et les épaules stables."),
                ExecutionPhase(3, "Point bas / Contraction", "Épaules au niveau des coudes (~90°), sans plonger au-delà de votre mobilité articulaire."),
                ExecutionPhase(4, "Phase concentrique & Respiration", "Poussez fort vers le haut en expirant jusqu'à extension contrôlée des triceps.")
            ),
            checklist = listOf(
                FormCheckPair("Épaules abaissées et scapulas stables", "Laisser les épaules remonter vers les oreilles"),
                FormCheckPair("Descente maîtrisée jusqu'à ~90° au coude", "Plonger trop bas en roulant l'épaule en avant"),
                FormCheckPair("Buste stable sans balancement", "Donner des coups de genoux pour remonter")
            )
        ),
        "vision_plank.mp4" to ExerciseVideoGuide(
            videoFileName = "vision_plank.mp4",
            patternTitleFr = "Gainage planche",
            tempo = "ISOMÉTRIE 45S",
            breathingFr = "Respiration thoracique fluide et régulière (4s inspiration / 4s expiration) tout en maintenant le caisson abdominal serré.",
            targetAnglesFr = "Alignement 180° · Bassin rétroversion légère",
            phases = listOf(
                ExecutionPhase(1, "Mise en place", "Coudes exactement sous les épaules, avant-bras parallèles, pointes de pieds ancrées."),
                ExecutionPhase(2, "Verrouillage du bassin", "Contractez fessiers et quadriceps avec une légère rétroversion du bassin pour effacer la cambrure."),
                ExecutionPhase(3, "Maintien actif", "Repoussez activement le sol avec les avant-bras pour stabiliser les omoplates et maintenir 180°."),
                ExecutionPhase(4, "Respiration continue", "Respirez calmement sans jamais bloquer l'air ni relâcher la sangle abdominale.")
            ),
            checklist = listOf(
                FormCheckPair("Ligne droite oreille-épaule-hanche-cheville", "Hanches affaissées (douleur lombaire)"),
                FormCheckPair("Fessiers et quadriceps engagés activement", "Fesses pointées trop haut en pyramide"),
                FormCheckPair("Regard vers le sol (nuque alignée)", "Lever la tête vers l'avant (tension cervicale)")
            )
        ),
        "vision_burpee.mp4" to ExerciseVideoGuide(
            videoFileName = "vision_burpee.mp4",
            patternTitleFr = "Burpee complet",
            tempo = "DYNAMIQUE",
            breathingFr = "Expirez en passant en planche, inspirez au regroupement, expirez sur le saut vertical.",
            targetAnglesFr = "Transition fluide Squat ↔ Planche 180° ↔ Extension",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Debout pieds largeur de hanches, abdos prêts à absorber la descente."),
                ExecutionPhase(2, "Descente & Planche", "Fléchissez les jambes, posez les mains à plat et projetez les pieds en planche gainée sans creuser."),
                ExecutionPhase(3, "Regroupement athlétique", "Ramenez les pieds dynamiquement près des mains, talons prêts à pousser."),
                ExecutionPhase(4, "Extension explosive", "Sautez verticalement bras tendus au-dessus de la tête et amortissez souplement.")
            ),
            checklist = listOf(
                FormCheckPair("Gainage ferme en position planche", "Laisser le bassin s'effondrer vers le sol"),
                FormCheckPair("Réception souple genoux fléchis", "Atterrir jambes raides sur les talons"),
                FormCheckPair("Rythme respiratoire régulier", "Bloquer sa respiration sur toute la série")
            )
        ),
        "vision_jumping_jack.mp4" to ExerciseVideoGuide(
            videoFileName = "vision_jumping_jack.mp4",
            patternTitleFr = "Jumping Jacks",
            tempo = "RYTHMÉ",
            breathingFr = "Inspirez en ouvrant bras et jambes, expirez en refermant au centre sur un tempo constant.",
            targetAnglesFr = "Épaule 18° → 162° · Appuis avant-pied souples",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Debout pieds joints, bras le long du corps, buste droit et épaules relâchées."),
                ExecutionPhase(2, "Ouverture synchronisée", "Sautez en écartant les pieds au-delà de la largeur d'épaules tout en levant les bras en arc overhead."),
                ExecutionPhase(3, "Amplitude haute", "Mains se rapprochent au-dessus de la tête, genoux légèrement déverrouillés à la réception."),
                ExecutionPhase(4, "Retour fluide", "Ramenez simultanément pieds et bras en amortissant sur l'avant-pied.")
            ),
            checklist = listOf(
                FormCheckPair("Amorti léger et silencieux sur l'avant-pied", "Impacts lourds talons au sol"),
                FormCheckPair("Amplitude complète des bras overhead", "Demi-mouvements saccadés des bras"),
                FormCheckPair("Genoux alignés dans l'axe des pieds", "Genoux rentrant vers l'intérieur")
            )
        ),
        "vision_pull_up.mp4" to ExerciseVideoGuide(
            videoFileName = "vision_pull_up.mp4",
            patternTitleFr = "Tractions pronation",
            tempo = "2-1-1",
            breathingFr = "Expirez en tirant la poitrine vers la barre, inspirez en redescendant jusqu'en suspension contrôlée.",
            targetAnglesFr = "Coude 172° → 48° · Dépression scapulaire",
            phases = listOf(
                ExecutionPhase(1, "Suspension active", "Prise légèrement plus large que les épaules, bras tendus, abaissez d'abord les omoplates."),
                ExecutionPhase(2, "Phase concentrique", "Tirez les coudes vers les hanches en ouvrant la cage thoracique vers la barre."),
                ExecutionPhase(3, "Pic de contraction", "Menton nettement au-dessus de la barre, omoplates serrées pendant 1 seconde."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Redescendez sous contrôle complet sur 2 secondes sans lâcher les épaules.")
            ),
            checklist = listOf(
                FormCheckPair("Initier en abaissant les omoplates", "Tirer uniquement avec les bras épaules hautes"),
                FormCheckPair("Corps gainé en hollow body", "Balancer les jambes (kipping involontaire)"),
                FormCheckPair("Amplitude complète de bras tendus à menton haut", "S'arrêter à mi-descente")
            )
        ),
        "vision_bent_row.mp4" to ExerciseVideoGuide(
            videoFileName = "vision_bent_row.mp4",
            patternTitleFr = "Rowing penché barre",
            tempo = "2-1-1",
            breathingFr = "Expirez en tirant la barre vers le bas des côtes, inspirez en contrôlant le retour bras tendus.",
            targetAnglesFr = "Coude 170° → 72° · Buste fixe 45°",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Charnière de hanche fixe à ~45°, genoux souples, dos parfaitement plat et barre sous les épaules."),
                ExecutionPhase(2, "Phase concentrique", "Tirez les coudes vers l'arrière et les hanches en ramenant la barre vers le nombril / bas sternum."),
                ExecutionPhase(3, "Pic scapulaire", "Serrez les omoplates 1 seconde au contact du buste sans redresser le tronc."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Tendez lentement les bras sur 2 secondes tout en gardant la tension dans les dorsaux.")
            ),
            checklist = listOf(
                FormCheckPair("Angle du buste constant durant toute la série", "Se redresser à chaque rep avec les lombaires"),
                FormCheckPair("Tirer par les coudes vers les hanches", "Hausser les épaules vers les oreilles"),
                FormCheckPair("Colonne neutre et nuque alignée", "Arrondir le bas du dos sous la fatigue")
            )
        ),
        "vision_glute_bridge.mp4" to ExerciseVideoGuide(
            videoFileName = "vision_glute_bridge.mp4",
            patternTitleFr = "Pont fessier",
            tempo = "2-1-1",
            breathingFr = "Expirez en poussant les hanches vers le plafond, inspirez en redescendant vertèbre par vertèbre.",
            targetAnglesFr = "Hanche 112° → 176° · Rétroversion en haut",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Allongé sur le dos, pieds à plat largeur de hanches proches des fessiers, côtes basses."),
                ExecutionPhase(2, "Phase concentrique", "Poussez fort dans les talons pour élever le bassin jusqu'à aligner genoux, hanches et épaules."),
                ExecutionPhase(3, "Contraction fessière", "Maintenez 1 seconde en haut en serrant fort les fessiers sans cambrer les lombaires."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Redescendez lentement en 2 secondes jusqu'à effleurer le tapis puis repartez.")
            ),
            checklist = listOf(
                FormCheckPair("Poussée dans les talons et tibias verticaux", "Pieds trop loin (crampes ischios) ou sur pointes"),
                FormCheckPair("Bassin en légère rétroversion au sommet", "Hyperextension lombaire pour monter plus haut"),
                FormCheckPair("Genoux stables alignés aux hanches", "Genoux qui s'affaissent l'un vers l'autre")
            )
        ),
        "vision_leg_raise.mp4" to ExerciseVideoGuide(
            videoFileName = "vision_leg_raise.mp4",
            patternTitleFr = "Relevé de jambes",
            tempo = "3-1-1",
            breathingFr = "Expirez en montant les jambes et en enroulant légèrement le bassin, inspirez sur la descente lente.",
            targetAnglesFr = "Hanche 170° → 90° · Lombaires collées",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Allongé dos plat, lombaires activement plaquées au tapis, bras le long du corps."),
                ExecutionPhase(2, "Phase concentrique", "Montez les jambes quasi tendues vers 90° en engageant le bas des abdominaux."),
                ExecutionPhase(3, "Point haut", "Enroulez légèrement le coccyx hors du sol pour recruter pleinement le grand droit."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Descendez lentement sur 3 secondes et stoppez dès que les lombaires menacent de se décoller.")
            ),
            checklist = listOf(
                FormCheckPair("Bas du dos constamment plaqué au sol", "Cambrer fortement le dos à la descente"),
                FormCheckPair("Descente lente et contrôlée sans élan", "Laisser tomber les talons au sol"),
                FormCheckPair("Jambes tendues ou micro-fléchies constantes", "Plier excessivement les genoux par facilité")
            )
        ),
        "vision_lateral_raise.mp4" to ExerciseVideoGuide(
            videoFileName = "vision_lateral_raise.mp4",
            patternTitleFr = "Élévations latérales",
            tempo = "2-1-1",
            breathingFr = "Expirez en élevant les haltères dans le plan scapulaire, inspirez sur la descente freinée.",
            targetAnglesFr = "Épaule 16° → 90° · Plan scapulaire 30°",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Debout gainé, haltères sur les côtés, épaules basses, coudes légèrement déverrouillés (~15°)."),
                ExecutionPhase(2, "Phase concentrique", "Élevez les bras dans le plan de l'omoplate (30° devant la ligne frontale) en menant par les coudes."),
                ExecutionPhase(3, "Pic horizontal", "Marquez une micro-pause à 90° (hauteur d'épaules), poignets neutres sans tourner le pouce vers le bas."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Redescendez lentement en 2–3 secondes sans reposer complètement la tension en bas.")
            ),
            checklist = listOf(
                FormCheckPair("Épaules basses et trapèzes relâchés", "Hausser les épaules vers les oreilles"),
                FormCheckPair("Stopper à hauteur d'épaules (~90°)", "Monter trop haut en balançant le buste"),
                FormCheckPair("Plan scapulaire (30° vers l'avant)", "Rotation interne forcée (« vider une carafe »)")
            )
        ),
        "vision_bulgarian_squat.mp4" to ExerciseVideoGuide(
            videoFileName = "vision_bulgarian_squat.mp4",
            patternTitleFr = "Squat Bulgare",
            tempo = "3-1-1",
            breathingFr = "Inspirez en descendant en contrôle sur la jambe avant, expirez sur la poussée concentrique.",
            targetAnglesFr = "Genou avant 168° → 85° · Charge 85% jambe avant",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Pied arrière posé sur le banc (cou-de-pied), pied avant avancé pour un tibia stable, léger penchant du buste."),
                ExecutionPhase(2, "Phase excentrique", "Descendez les hanches vers l'arrière et le bas sur 3 secondes en chargeant la jambe avant."),
                ExecutionPhase(3, "Point bas / Contraction", "Cuisse avant sous la parallèle (< 90°), genou aligné aux orteils, fessier étiré sous tension."),
                ExecutionPhase(4, "Phase concentrique & Respiration", "Poussez fort dans tout le pied avant en expirant sans vous aider de la jambe arrière.")
            ),
            checklist = listOf(
                FormCheckPair("85 % du poids sur la jambe avant", "Pousser excessivement avec le pied arrière"),
                FormCheckPair("Genou avant stable dans l'axe du pied", "Genou avant qui vrille vers l'intérieur"),
                FormCheckPair("Hauteur de banc adaptée (sous le genou)", "Banc trop haut provoquant une cambrure lombaire")
            )
        ),

        // === 26 ENCYCLOPEDIA MOVEMENT ARCHETYPES ===
        "ex_front_squat.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_front_squat.mp4",
            patternTitleFr = "Front Squat / Squat avant",
            tempo = "3-1-1",
            breathingFr = "Inspirez profondément cage haute avant la descente, expirez sur la fin de la poussée verticale.",
            targetAnglesFr = "Genou 175° → 78° · Buste très vertical (< 20°)",
            phases = listOf(
                ExecutionPhase(1, "Position rack avant", "Barre calée sur les deltoïdes antérieurs, coudes pointés haut vers l'avant, buste droit."),
                ExecutionPhase(2, "Phase excentrique", "Descendez droit entre les hanches sur 3 secondes en maintenant les coudes très hauts."),
                ExecutionPhase(3, "Point bas profond", "Amplitude maximale quadriceps, talons ancrés, dos thoracique verrouillé."),
                ExecutionPhase(4, "Phase concentrique & Respiration", "Poussez verticalement coudes vers le plafond en expirant fort.")
            ),
            checklist = listOf(
                FormCheckPair("Coudes hauts et parallèles au sol", "Laisser les coudes tomber (barre qui glisse)"),
                FormCheckPair("Buste très droit et gainage thoracique", "Arrondir le haut du dos en bas"),
                FormCheckPair("Genoux avancent dans l'axe des orteils", "Décoller les talons en fin de descente")
            )
        ),
        "ex_romanian_deadlift.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_romanian_deadlift.mp4",
            patternTitleFr = "Soulevé de terre Roumain (RDL)",
            tempo = "3-1-1",
            breathingFr = "Inspirez avant la charnière, maintenez le gainage à l'étirement maximal, expirez en verrouillant les hanches.",
            targetAnglesFr = "Hanche 176° → 82° · Genoux fixes ~158°",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Debout barre en mains contre les cuisses, genoux très légèrement déverrouillés (~15–20°)."),
                ExecutionPhase(2, "Phase excentrique", "Poussez les fesses loin vers l'arrière sur 3 secondes en faisant raser la barre le long des cuisses et tibias."),
                ExecutionPhase(3, "Étirement maximal ischios", "Stoppez dès que les hanches ne reculent plus (mi-tibias), dos parfaitement plat."),
                ExecutionPhase(4, "Phase concentrique & Respiration", "Contractez fort fessiers et ischios pour ramener les hanches vers l'avant en expirant.")
            ),
            checklist = listOf(
                FormCheckPair("Recul horizontal pur des hanches", "Fléchir les genoux comme un squat"),
                FormCheckPair("Barre collée aux jambes sur toute l'amplitude", "Laisser la barre s'éloigner des tibias"),
                FormCheckPair("Colonne neutre jusqu'à mi-tibias", "Forcer la descente en arrondissant les lombaires")
            )
        ),
        "ex_hip_thrust.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_hip_thrust.mp4",
            patternTitleFr = "Hip Thrust barre",
            tempo = "2-1-1",
            breathingFr = "Inspirez en descendant le bassin, expirez fort en verrouillant l'extension de hanche au sommet.",
            targetAnglesFr = "Hanche 102° → 178° · Tibias verticaux 90°",
            phases = listOf(
                ExecutionPhase(1, "Installation", "Pointe des omoplates appuyée contre le banc, barre matelassée dans le pli des hanches, menton rentré."),
                ExecutionPhase(2, "Phase concentrique", "Poussez dans les talons pour amener les hanches en alignement horizontal complet."),
                ExecutionPhase(3, "Pic fessier 1s", "Rétroversion du bassin, côtes rentrées, tibias parfaitement verticaux à 90°."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Redescendez le bassin sous contrôle en pivotant le tronc en bloc (regard toujours devant).")
            ),
            checklist = listOf(
                FormCheckPair("Menton rentré et côtes verrouillées en haut", "Basculer la tête en arrière et cambrer les lombaires"),
                FormCheckPair("Tibias verticaux à 90° au sommet", "Pieds trop avancés (dominance ischios) ou trop proches"),
                FormCheckPair("Pause franche de 1 seconde en haut", "Rebondir vite sans contraction complète")
            )
        ),
        "ex_leg_press.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_leg_press.mp4",
            patternTitleFr = "Presse à cuisses 45°",
            tempo = "3-1-1",
            breathingFr = "Inspirez en contrôlant la descente du plateau, expirez en poussant sans verrouiller brutalement les genoux.",
            targetAnglesFr = "Genou 166° → 78° · Bassin collé au dossier",
            phases = listOf(
                ExecutionPhase(1, "Installation", "Dos et sacrum fermement plaqués au siège, pieds à plat largeur d'épaules sur le plateau."),
                ExecutionPhase(2, "Phase excentrique", "Descendez le chariot lentement sur 3 secondes en ouvrant les genoux dans l'axe des pieds."),
                ExecutionPhase(3, "Point bas sécurisé", "Descendez au maximum de flexion tout en gardant les fesses collées au siège (sans enrouler le bas du dos)."),
                ExecutionPhase(4, "Phase concentrique & Respiration", "Repoussez puissamment dans tout le pied en stoppant juste avant l'hyperextension des genoux.")
            ),
            checklist = listOf(
                FormCheckPair("Bas du dos et fessiers collés au siège", "Décoller le sacrum en bas de course"),
                FormCheckPair("Genoux légèrement déverrouillés en haut", "Claquer les genoux en hyperextension"),
                FormCheckPair("Talons constamment en contact du plateau", "Pousser uniquement sur la pointe des pieds")
            )
        ),
        "ex_leg_extension.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_leg_extension.mp4",
            patternTitleFr = "Leg Extension",
            tempo = "2-1-1",
            breathingFr = "Expirez en tendant les jambes vers la contraction maximale, inspirez sur le retour contrôlé.",
            targetAnglesFr = "Genou 88° → 175° · Isolation quadriceps",
            phases = listOf(
                ExecutionPhase(1, "Réglage machine", "Axe du genou aligné avec l'axe de rotation de la machine, coussin placé au-dessus des chevilles."),
                ExecutionPhase(2, "Phase concentrique", "Tendez les genoux de façon fluide en vous agrippant aux poignées pour plaquer les fesses."),
                ExecutionPhase(3, "Pic isométrique", "Maintenez 1 seconde jambes tendues en contractant volontairement les quadriceps."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Freinez le retour sur 2–3 secondes jusqu'à flexion complète sans laisser claquer les poids.")
            ),
            checklist = listOf(
                FormCheckPair("Genou aligné exactement sur le pivot machine", "Coussin trop haut sur le tibia ou trop bas sur les pieds"),
                FormCheckPair("Montée fluide avec pause de 1s en haut", "Donner des à-coups explosifs en décollant les fesses"),
                FormCheckPair("Retour lent et contrôlé", "Laisser retomber la charge brutalement")
            )
        ),
        "ex_leg_curl.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_leg_curl.mp4",
            patternTitleFr = "Leg Curl (Ischio-jambiers)",
            tempo = "3-1-1",
            breathingFr = "Expirez en fléchissant les genoux, inspirez en freinant l'extension sur 3 secondes.",
            targetAnglesFr = "Genou 170° → 62° · Hanches plaquées",
            phases = listOf(
                ExecutionPhase(1, "Installation", "Genoux alignés avec le pivot de la machine, boudin calé juste au-dessus des talons."),
                ExecutionPhase(2, "Phase concentrique", "Fléchissez les genoux en contractant les ischios tout en plaquant les hanches contre le banc."),
                ExecutionPhase(3, "Pic de contraction", "Marquez 1 seconde de contraction maximale sans décoller le bassin."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Freinez la descente sur 3 secondes jusqu'à quasi-extension complète.")
            ),
            checklist = listOf(
                FormCheckPair("Hanches collées au banc en permanence", "Décoller les fesses en cambrant les lombaires"),
                FormCheckPair("Phase excentrique lente (3s)", "Lâcher la charge en retour rapide"),
                FormCheckPair("Chevilles neutres ou en légère dorsiflexion", "Tirer avec élan du bas du dos")
            )
        ),
        "ex_calf_raise.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_calf_raise.mp4",
            patternTitleFr = "Extensions mollets",
            tempo = "2-2-1",
            breathingFr = "Inspirez en étirant pleinement les talons vers le bas, expirez en montant sur les pointes.",
            targetAnglesFr = "Cheville 76° → 124° · Pause 2s en bas",
            phases = listOf(
                ExecutionPhase(1, "Position sur cale", "Avant-pied stable sur la cale, genoux droits (ou fléchis pour le soléaire), buste gainé."),
                ExecutionPhase(2, "Étirement complet", "Descendez lentement les talons sous l'horizontale et marquez 2 secondes d'étirement pour annuler l'effet rebond."),
                ExecutionPhase(3, "Extension maximale", "Montez le plus haut possible sur le gros et le deuxième orteil, pic de contraction 1 seconde."),
                ExecutionPhase(4, "Descente contrôlée", "Freinez chaque répétition sur 2 secondes sans jamais rebondir sur le tendon d'Achille.")
            ),
            checklist = listOf(
                FormCheckPair("Pause complète de 1–2s en étirement bas", "Rebondir vite en utilisant l'élasticité du tendon"),
                FormCheckPair("Poussée centrée sur la base du gros orteil", "Chevilles qui basculent vers l'extérieur"),
                FormCheckPair("Amplitude totale de bas en haut", "Petits battements partiels trop lourds")
            )
        ),
        "ex_bench_press.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_bench_press.mp4",
            patternTitleFr = "Développé couché",
            tempo = "3-1-1",
            breathingFr = "Inspirez avant de descendre la barre, bloquez brièvement au contact poitrine, expirez sur la remontée.",
            targetAnglesFr = "Coude 172° → 82° · Trajectoire en J",
            phases = listOf(
                ExecutionPhase(1, "Mise en place", "Omoplates serrées et abaissées sur le banc, légère cambrure thoracique naturelle, pieds ancrés au sol."),
                ExecutionPhase(2, "Phase excentrique", "Descendez la barre sous contrôle (3s) vers le bas des pectoraux, coudes rentrés à ~45–60°."),
                ExecutionPhase(3, "Contact contrôlé", "Effleurez le sternum sans rebondir, avant-bras parfaitement verticaux sous la barre."),
                ExecutionPhase(4, "Phase concentrique & Respiration", "Poussez fort vers le haut et légèrement vers l'arrière (ligne des épaules) en expirant.")
            ),
            checklist = listOf(
                FormCheckPair("Scapulas rétractées et abaissées (épaules protégées)", "Épaules enroulées en avant et coudes à 90°"),
                FormCheckPair("Avant-bras verticaux sous la barre au point bas", "Rebondir la barre sur la cage thoracique"),
                FormCheckPair("Fessiers collés au banc et pieds stables", "Décoller les fesses pendant la poussée")
            )
        ),
        "ex_incline_press.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_incline_press.mp4",
            patternTitleFr = "Développé incliné 30°",
            tempo = "3-1-1",
            breathingFr = "Inspirez sur la descente contrôlée vers le haut des pectoraux, expirez en poussant verticalement.",
            targetAnglesFr = "Coude 170° → 82° · Banc incliné 30°",
            phases = listOf(
                ExecutionPhase(1, "Installation", "Banc réglé à 30° (optimal haut des pecs), omoplates serrées contre le dossier, pieds stables."),
                ExecutionPhase(2, "Phase excentrique", "Descendez les haltères ou la barre vers le haut de la poitrine (sous les clavicules), coudes à ~45°."),
                ExecutionPhase(3, "Étirement contrôlé", "Marquez une courte pause en étirement sans laisser les épaules rouler vers l'avant."),
                ExecutionPhase(4, "Phase concentrique & Respiration", "Pressez verticalement en convergeant légèrement vers le haut en expirant.")
            ),
            checklist = listOf(
                FormCheckPair("Inclinaison modérée 25–35° (faisceau claviculaire)", "Banc trop vertical (> 45° transfert épaules)"),
                FormCheckPair("Coudes sous la ligne des épaules", "Coudes écartés à 90°"),
                FormCheckPair("Contrôle complet sans cogner les haltères", "Entrechoquer bruyamment les charges en haut")
            )
        ),
        "ex_chest_fly.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_chest_fly.mp4",
            patternTitleFr = "Écartés pectoraux",
            tempo = "3-1-1",
            breathingFr = "Inspirez en ouvrant les bras en étirement thoracique, expirez en rapprochant les mains devant le sternum.",
            targetAnglesFr = "Ouverture épaule 78° → 18° · Coudes semi-fléchis",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Cage ouverte, omoplates serrées, bras écartés avec un angle fixe de ~15–20° aux coudes."),
                ExecutionPhase(2, "Phase concentrique", "Décrivez un grand arc circulaire comme pour enlacer un arbre, en menant par l'intérieur des coudes."),
                ExecutionPhase(3, "Pic pectoral", "Serrez fortement les pectoraux 1 seconde lorsque les mains se rejoignent devant le sternum."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Ouvrez lentement sur 3 secondes jusqu'à un étirement pectoral maîtrisé sans stress d'épaule.")
            ),
            checklist = listOf(
                FormCheckPair("Angle des coudes constant durant tout l'arc", "Transformer l'écarté en développé couché"),
                FormCheckPair("Épaules basses et cage thoracique sortie", "Enrouler les épaules vers l'avant au pic"),
                FormCheckPair("Étirement contrôlé dans votre mobilité", "Descendre trop loin et tirer sur la capsule antérieure")
            )
        ),
        "ex_lat_pulldown.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_lat_pulldown.mp4",
            patternTitleFr = "Tirage vertical poulie",
            tempo = "2-1-1",
            breathingFr = "Expirez en tirant la barre vers les clavicules, inspirez en contrôlant l'étirement vers le haut.",
            targetAnglesFr = "Coude 168° → 62° · Coudes vers les hanches",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Cuisses calées sous le boudin, prise 1,2–1,5× largeur d'épaules, buste légèrement incliné (~10–15°)."),
                ExecutionPhase(2, "Phase concentrique", "Abaissez d'abord les épaules puis tirez les coudes verticalement vers les hanches."),
                ExecutionPhase(3, "Pic dorsal", "Barre au niveau des clavicules / haut sternum, omoplates serrées et basses pendant 1 seconde."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Laissez remonter la barre lentement sur 2–3 secondes jusqu'à étirement complet des dorsaux.")
            ),
            checklist = listOf(
                FormCheckPair("Tirer la barre devant vers les clavicules", "Tirage nuque (stress cervical et coiffe)"),
                FormCheckPair("Buste quasi fixe avec léger gainage", "Se coucher complètement en arrière pour tirer"),
                FormCheckPair("Initier le mouvement par les omoplates", "Tirer uniquement avec les biceps épaules hautes")
            )
        ),
        "ex_seated_cable_row.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_seated_cable_row.mp4",
            patternTitleFr = "Tirage horizontal poulie",
            tempo = "2-1-1",
            breathingFr = "Expirez en ramenant la poignée au bas des côtes, inspirez en contrôlant l'étirement scapulaire.",
            targetAnglesFr = "Coude 168° → 72° · Colonne neutre",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Assis buste droit, genoux semi-fléchis, bras tendus devant en protraction scapulaire contrôlée."),
                ExecutionPhase(2, "Phase concentrique", "Tirez les coudes près du corps vers l'arrière en rétractant fermement les omoplates."),
                ExecutionPhase(3, "Contraction dorsale", "Poignée au contact du bas sternum / nombril, poitrine fière pendant 1 seconde."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Allongez lentement les bras vers l'avant en gardant le bas du dos neutre.")
            ),
            checklist = listOf(
                FormCheckPair("Buste droit et stable sans balancement excessif", "Basculer d'avant en arrière avec les lombaires"),
                FormCheckPair("Épaules abaissées loin des oreilles", "Contracter les trapèzes supérieurs"),
                FormCheckPair("Rétraction complète des omoplates", "Tirer uniquement avec les poignets et avant-bras")
            )
        ),
        "ex_face_pull.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_face_pull.mp4",
            patternTitleFr = "Face Pull poulie",
            tempo = "2-1-1",
            breathingFr = "Expirez en tirant la corde vers le front en rotation externe, inspirez sur le retour contrôlé.",
            targetAnglesFr = "Coude 168° → 72° · Rotation externe épaule",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Poulie hauteur visage, corde tenue pouces vers vous, posture stable légèrement en fente."),
                ExecutionPhase(2, "Phase concentrique", "Tirez la corde vers le front tout en écartant les mains et en gardant les coudes hauts."),
                ExecutionPhase(3, "Rotation externe 1s", "Amenez les mains à côté des oreilles (position double biceps), omoplates serrées."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Revenez lentement bras tendus sans laisser les épaules partir trop en avant.")
            ),
            checklist = listOf(
                FormCheckPair("Mains finissent derrière ou au niveau des coudes", "Tirer vers la poitrine en rotation interne"),
                FormCheckPair("Charge modérée avec contrôle chirurgical", "Mettre trop lourd et se pencher en arrière"),
                FormCheckPair("Pause de 1s en contraction arrière", "Mouvements rapides sans contrôle scapulaire")
            )
        ),
        "ex_shrugs.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_shrugs.mp4",
            patternTitleFr = "Shrugs trapèzes",
            tempo = "2-1-1",
            breathingFr = "Expirez en élevant strictement les épaules vers les oreilles, inspirez sur la descente complète.",
            targetAnglesFr = "Élévation verticale scapulaire · Bras tendus",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Debout gainé, haltères ou barre tenus bras tendus, épaules complètement relâchées vers le bas."),
                ExecutionPhase(2, "Phase concentrique", "Élevez les épaules verticalement vers les oreilles sans fléchir les coudes."),
                ExecutionPhase(3, "Contraction 1–2s", "Maintenez le pic de contraction en haut des trapèzes pendant 1 à 2 secondes."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Redescendez lentement jusqu'à l'étirement complet en bas.")
            ),
            checklist = listOf(
                FormCheckPair("Trajectoire strictement verticale haut-bas", "Faire des rotations circulaires des épaules"),
                FormCheckPair("Nuque neutre et regard droit devant", "Avancer la tête vers l'avant à chaque rep"),
                FormCheckPair("Bras tendus comme des crochets", "Fléchir les biceps pour tricher")
            )
        ),
        "ex_front_raise.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_front_raise.mp4",
            patternTitleFr = "Élévations frontales",
            tempo = "2-1-1",
            breathingFr = "Expirez en élevant les haltères devant vous à hauteur d'épaules, inspirez en freinant la descente.",
            targetAnglesFr = "Épaule 14° → 92° · Buste immobile",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Debout abdos gainés, haltères devant les cuisses, coudes légèrement souples."),
                ExecutionPhase(2, "Phase concentrique", "Montez les bras devant vous jusqu'à l'horizontale (~90°) sans basculer le buste."),
                ExecutionPhase(3, "Contraction haute", "Pause contrôlée à hauteur d'épaules, épaules basses et poignets neutres."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Freinez la descente sur 2–3 secondes jusqu'aux cuisses.")
            ),
            checklist = listOf(
                FormCheckPair("Buste fixe grâce au gainage abdominal", "Cambrer les lombaires pour lancer la charge"),
                FormCheckPair("Stopper à hauteur d'yeux / épaules (~90°)", "Monter trop haut avec élan"),
                FormCheckPair("Descente maîtrisée sans balancement", "Laisser tomber les bras brutalement")
            )
        ),
        "ex_rear_delt_fly.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_rear_delt_fly.mp4",
            patternTitleFr = "Oiseau / Deltoïde postérieur",
            tempo = "2-1-1",
            breathingFr = "Expirez en ouvrant les bras sur les côtés, inspirez en contrôlant la fermeture.",
            targetAnglesFr = "Épaule 18° → 88° · Buste penché ~70°",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Buste penché quasi horizontal dos plat (ou contre banc incliné), haltères sous les épaules."),
                ExecutionPhase(2, "Phase concentrique", "Balayez les bras vers l'extérieur et légèrement vers l'arrière en gardant les coudes semi-fléchis."),
                ExecutionPhase(3, "Pic postérieur", "Contractez l'arrière d'épaule à l'horizontale sans écraser les trapèzes supérieurs."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Redescendez lentement en gardant la tension continue sur le deltoïde postérieur.")
            ),
            checklist = listOf(
                FormCheckPair("Charge légère et exécution chirurgicale", "Utiliser de l'élan du buste"),
                FormCheckPair("Pousser les mains loin vers l'extérieur", "Tirer vers les oreilles (trapèzes)"),
                FormCheckPair("Dos plat et nuque alignée au tronc", "Arrondir les lombaires pendant la série")
            )
        ),
        "ex_hammer_curl.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_hammer_curl.mp4",
            patternTitleFr = "Curl marteau / Avant-bras",
            tempo = "2-1-1",
            breathingFr = "Expirez en fléchissant en prise neutre, inspirez sur l'extension contrôlée.",
            targetAnglesFr = "Coude 168° → 42° · Prise neutre (pouces haut)",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Debout gainé, haltères tenus en prise neutre (paumes face à face, pouces vers l'avant)."),
                ExecutionPhase(2, "Phase concentrique", "Fléchissez strictement les coudes en maintenant les poignets parfaitement alignés."),
                ExecutionPhase(3, "Contraction brachial", "Serrez 1 seconde en haut en recrutant brachial et brachio-radial."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Descendez lentement jusqu'à extension complète sans balancer les épaules.")
            ),
            checklist = listOf(
                FormCheckPair("Poignets neutres et alignés toute la rep", "Casser les poignets ou tourner en supination"),
                FormCheckPair("Coudes ancrés près des côtes", "Projeter les coudes vers l'avant"),
                FormCheckPair("Contrôle excentrique complet", "Utiliser l'élan du bas du dos")
            )
        ),
        "ex_tricep_pushdown.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_tricep_pushdown.mp4",
            patternTitleFr = "Extensions triceps poulie",
            tempo = "2-1-1",
            breathingFr = "Expirez en verrouillant l'extension vers le bas, inspirez en contrôlant la remontée des avant-bras.",
            targetAnglesFr = "Coude 72° → 172° · Humérus fixe",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Face à la poulie haute, buste très légèrement penché, coudes verrouillés contre les côtes."),
                ExecutionPhase(2, "Phase concentrique", "Tendez uniquement les avant-bras vers le bas (en écartant la corde en fin de course)."),
                ExecutionPhase(3, "Extension complète", "Contractez fort les 3 chefs du triceps 1 seconde en bas, poignets droits."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Remontez sous contrôle jusqu'à ~70° sans décoller les coudes du buste.")
            ),
            checklist = listOf(
                FormCheckPair("Coudes immobiles collés aux côtes", "Laisser les coudes monter et avancer"),
                FormCheckPair("Épaules basses et poitrine sortie", "S'écraser sur la corde avec le poids du corps"),
                FormCheckPair("Extension complète maîtrisée", "Mouvements saccadés partiels")
            )
        ),
        "ex_skull_crusher.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_skull_crusher.mp4",
            patternTitleFr = "Barre au front (Skull Crusher)",
            tempo = "3-1-1",
            breathingFr = "Inspirez en fléchissant vers l'arrière du front, expirez en verrouillant les triceps vers le haut.",
            targetAnglesFr = "Coude 168° → 62° · Bras inclinés ~105°",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Allongé sur banc plat, barre EZ tenue bras inclinés légèrement vers l'arrière (~105°) pour garder la tension."),
                ExecutionPhase(2, "Phase excentrique", "Fléchissez strictement les coudes sur 3 secondes en amenant la barre derrière le front / sommet du crâne."),
                ExecutionPhase(3, "Étirement longue portion", "Profitez de l'étirement maximal de la longue portion du triceps sans écarter les coudes."),
                ExecutionPhase(4, "Phase concentrique & Respiration", "Tendez les coudes sur le même axe en expirant jusqu'à contraction ferme.")
            ),
            checklist = listOf(
                FormCheckPair("Coudes pointés vers le plafond, écart constant", "Laisser les coudes s'ouvrir largement sur les côtés"),
                FormCheckPair("Descente contrôlée derrière le front", "Charge trop lourde générant une douleur au coude"),
                FormCheckPair("Humérus légèrement incliné vers la tête", "Ramener les bras verticaux (perte de tension)")
            )
        ),
        "ex_crunch.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_crunch.mp4",
            patternTitleFr = "Crunch abdominal",
            tempo = "2-1-1",
            breathingFr = "Expirez longuement en enroulant le thorax vers le bassin, inspirez en redescendant sans relâcher totalement.",
            targetAnglesFr = "Flexion thoracique 175° → 130°",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Allongé genoux fléchis, pieds à plat, mains légères sur les tempes, menton décollé du sternum."),
                ExecutionPhase(2, "Phase concentrique", "Enroulez la colonne thoracique en rapprochant les côtes du pubis tout en vidant l'air."),
                ExecutionPhase(3, "Pic abdominal", "Omoplates décollées du sol, contraction maximale du grand droit pendant 1 seconde."),
                ExecutionPhase(4, "Phase excentrique & Respiration", "Déroulez lentement le haut du dos sans poser brutalement la tête au sol.")
            ),
            checklist = listOf(
                FormCheckPair("Enroulement thoracique pur (côtes vers bassin)", "Tirer sur la nuque avec les mains"),
                FormCheckPair("Expiration active sur chaque montée", "Monter d'un bloc raide avec les fléchisseurs de hanche"),
                FormCheckPair("Tension continue sans repos total au sol", "Élan rapide de la tête et des coudes")
            )
        ),
        "ex_russian_twist.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_russian_twist.mp4",
            patternTitleFr = "Russian Twist (Obliques)",
            tempo = "RYTHMÉ",
            breathingFr = "Expirez sèchement sur chaque rotation latérale, inspirez brièvement au passage central.",
            targetAnglesFr = "Rotation thoracique ±45° · Dos plat",
            phases = listOf(
                ExecutionPhase(1, "Position initiale", "Assis en V équilibré, buste incliné à ~45° dos plat, talons posés ou légèrement décollés."),
                ExecutionPhase(2, "Rotation droite", "Pivotez la cage thoracique (pas seulement les bras) vers la droite en expirant."),
                ExecutionPhase(3, "Passage central gainé", "Contrôlez le retour au centre en maintenant les hanches et les genoux immobiles."),
                ExecutionPhase(4, "Rotation gauche", "Enchaînez la rotation thoracique symétrique vers la gauche avec un rythme maîtrisé.")
            ),
            checklist = listOf(
                FormCheckPair("Rotation réelle des épaules et du thorax", "Bouger seulement les mains de gauche à droite"),
                FormCheckPair("Colonne allongée et dos neutre", "S'affaisser dos rond sur les lombaires"),
                FormCheckPair("Genoux stables pendant toute la série", "Balancer fortement les jambes en contrepoids")
            )
        ),
        "ex_mountain_climber.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_mountain_climber.mp4",
            patternTitleFr = "Mountain Climbers",
            tempo = "DYNAMIQUE",
            breathingFr = "Respiration rythmée continue (expiration à chaque montée de genou dynamique).",
            targetAnglesFr = "Planche 180° · Drive genou vers poitrine",
            phases = listOf(
                ExecutionPhase(1, "Planche haute", "Mains exactement sous les épaules, bras tendus, corps aligné et épaules stables."),
                ExecutionPhase(2, "Drive genou droit", "Ramenez dynamiquement le genou vers la poitrine en contractant fort les abdos."),
                ExecutionPhase(3, "Transition rapide", "Alternez rapidement avec le genou gauche en gardant les hanches basses et stables."),
                ExecutionPhase(4, "Cadence soutenue", "Maintenez la poussée constante dans les mains sans laisser les fesses monter.")
            ),
            checklist = listOf(
                FormCheckPair("Épaules empilées au-dessus des poignets", "Reculer le corps loin derrière les mains"),
                FormCheckPair("Hanches stables et basses", "Rebondir les fesses très haut à chaque pas"),
                FormCheckPair("Respiration continue et fluide", "Bloquer la respiration sous l'effort cardio")
            )
        ),
        "ex_kettlebell_swing.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_kettlebell_swing.mp4",
            patternTitleFr = "Kettlebell Swing",
            tempo = "EXPLOSIF",
            breathingFr = "Inspirez sur le recul entre les jambes, expirez sèchement (« tss ! ») sur l'extension explosive des hanches.",
            targetAnglesFr = "Hanche 88° → 178° · Bras = cordes passives",
            phases = listOf(
                ExecutionPhase(1, "Hike arrière", "Charnière de hanche franche, dos plat, kettlebell tiré haut entre les cuisses."),
                ExecutionPhase(2, "Extension explosive", "Claquez les hanches vers l'avant en contractant fessiers et quadriceps comme une planche debout."),
                ExecutionPhase(3, "Flottement poitrine", "Le kettlebell flotte naturellement à hauteur d'épaules grâce à l'impulsion des hanches, bras tendus."),
                ExecutionPhase(4, "Réception tardive", "Attendez la dernière seconde avant de renvoyer les hanches en arrière (pas de squat).")
            ),
            checklist = listOf(
                FormCheckPair("Puissance issue à 100 % des hanches", "Soulever le kettlebell avec les épaules/bras"),
                FormCheckPair("Verrouillage droit (planche verticale) en haut", "Cambrer en arrière au sommet du swing"),
                FormCheckPair("Charnière de hanche pure (tibias quasi verticaux)", "Transformer le swing en squat profond")
            )
        ),
        "ex_box_jump.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_box_jump.mp4",
            patternTitleFr = "Box Jump pliométrique",
            tempo = "EXPLOSIF",
            breathingFr = "Inspirez dans le quart de squat préparatoire, expirez sur l'impulsion explosive.",
            targetAnglesFr = "Impulsion triple extension · Réception souple",
            phases = listOf(
                ExecutionPhase(1, "Armement", "Pieds largeur de hanches devant la box, quart de squat rapide et bras projetés en arrière."),
                ExecutionPhase(2, "Impulsion explosive", "Triple extension cheville-genou-hanche en lançant vigoureusement les bras vers le haut."),
                ExecutionPhase(3, "Réception silencieuse", "Atterrissez en douceur en milieu de box, pieds à plat et genoux fléchis dans l'axe."),
                ExecutionPhase(4, "Extension & Descente", "Redressez-vous complètement sur la box puis descendez en pas contrôlé (step-down).")
            ),
            checklist = listOf(
                FormCheckPair("Atterrissage silencieux en demi-squat athlétique", "Atterrir bruyamment en squat écrasé"),
                FormCheckPair("Descendre de la box un pied après l'autre", "Sauter en arrière depuis la box (risque Achille)"),
                FormCheckPair("Extension complète debout sur la box", "Enchaîner courbé sans stabilisation")
            )
        ),
        "ex_farmer_walk.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_farmer_walk.mp4",
            patternTitleFr = "Farmer's Walk (Marche lestée)",
            tempo = "CONTINU",
            breathingFr = "Respiration abdominale courte et rythmée sous gainage continu pendant toute la marche.",
            targetAnglesFr = "Posture verticale 180° · Épaules packées",
            phases = listOf(
                ExecutionPhase(1, "Prise & Décollage", "Soulevez les charges comme un deadlift, colonne neutre, grip écrasant les poignées."),
                ExecutionPhase(2, "Verrouillage postural", "Grandissez-vous au maximum, épaules basses et en arrière, abdos et obliques verrouillés."),
                ExecutionPhase(3, "Marche rapide & stable", "Faites des pas courts et rapides en ligne droite sans aucun balancement latéral du buste."),
                ExecutionPhase(4, "Repose sécurisée", "Déposez les charges au sol en fléchissant hanches et genoux, dos plat.")
            ),
            checklist = listOf(
                FormCheckPair("Épaules basses, omoplates serrées, regard loin", "Laisser les épaules s'effondrer vers l'avant"),
                FormCheckPair("Pas courts, cadence fluide et buste immobile", "Se dandiner latéralement sous la charge"),
                FormCheckPair("Poser les charges en position deadlift propre", "Lâcher le dos rond en fin de parcours")
            )
        ),
        "ex_mobility_stretch.mp4" to ExerciseVideoGuide(
            videoFileName = "ex_mobility_stretch.mp4",
            patternTitleFr = "Mobilité & Étirement actif",
            tempo = "FLUIDE 4-4",
            breathingFr = "Respiration nasale profonde (4s inspiration / 4–6s expiration lente) pour relâcher le tonus musculaire.",
            targetAnglesFr = "Ouverture hanches & rotation thoracique progressive",
            phases = listOf(
                ExecutionPhase(1, "Installation stable", "Placez-vous dans l'amplitude confortable sans douleur articulaire aiguë, appuis ancrés."),
                ExecutionPhase(2, "Ouverture active", "Engagez la rotation thoracique ou la mobilisation de hanche de façon lente et contrôlée."),
                ExecutionPhase(3, "Respiration en amplitude", "Maintenez 2–4 secondes au pic en expirant profondément pour gagner en relâchement."),
                ExecutionPhase(4, "Retour fluide", "Revenez sans à-coup et répétez symétriquement de chaque côté.")
            ),
            checklist = listOf(
                FormCheckPair("Amplitude progressive sans douleur aiguë", "Forcer par à-coups balistiques brusques"),
                FormCheckPair("Expiration longue dans la zone d'étirement", "Bloquer sa respiration en crispant les épaules"),
                FormCheckPair("Symétrie bilatérale gauche / droite", "Négliger le côté le plus raide")
            )
        )
    )

    val ALL_VIDEO_FILES: Set<String> = GUIDE_BY_VIDEO.keys

    fun forVisionExercise(exercise: ExerciseType): ExerciseVideoGuide {
        val fileName = when (exercise) {
            ExerciseType.SQUAT -> "vision_squat.mp4"
            ExerciseType.PUSHUP -> "vision_pushup.mp4"
            ExerciseType.LUNGE -> "vision_lunge.mp4"
            ExerciseType.DEADLIFT -> "vision_deadlift.mp4"
            ExerciseType.OVERHEAD_PRESS -> "vision_overhead_press.mp4"
            ExerciseType.BICEP_CURL -> "vision_bicep_curl.mp4"
            ExerciseType.TRICEP_DIP -> "vision_tricep_dip.mp4"
            ExerciseType.PLANK -> "vision_plank.mp4"
            ExerciseType.BURPEE -> "vision_burpee.mp4"
            ExerciseType.JUMPING_JACK -> "vision_jumping_jack.mp4"
            ExerciseType.PULL_UP -> "vision_pull_up.mp4"
            ExerciseType.BENT_ROW -> "vision_bent_row.mp4"
            ExerciseType.GLUTE_BRIDGE -> "vision_glute_bridge.mp4"
            ExerciseType.LEG_RAISE -> "vision_leg_raise.mp4"
            ExerciseType.LATERAL_RAISE -> "vision_lateral_raise.mp4"
            ExerciseType.BULGARIAN_SQUAT -> "vision_bulgarian_squat.mp4"
        }
        return GUIDE_BY_VIDEO.getValue(fileName)
    }

    fun forKnowledgeEntry(entry: KnowledgeEntry): ExerciseVideoGuide =
        forExerciseMetadata(entry.title, entry.subcategory, entry.tags)

    /**
     * Deterministically maps ANY French exercise title + subcategory + tags
     * (all 911 Encyclopedia `exercice` entries) to the most accurate studio MP4 video.
     */
    fun forExerciseMetadata(
        title: String,
        subcategory: String,
        tags: String = ""
    ): ExerciseVideoGuide {
        val t = normalizeText(title)
        val sc = normalizeText(subcategory)
        val all = "$t $sc ${normalizeText(tags)}"

        val videoFile = resolveVideoFile(t, sc, all)
        return GUIDE_BY_VIDEO.getValue(videoFile)
    }

    private fun resolveVideoFile(t: String, sc: String, all: String): String {
        // 1. High-specificity compound / distinctive exercises FIRST (prevents false substring matches)
        if (t.contains("bulgar") || t.contains("split squat")) return "vision_bulgarian_squat.mp4"
        if (t.contains("front squat") || t.contains("squat avant") || t.contains("goblet") || t.contains("zercher") || t.contains("thruster")) {
            return "ex_front_squat.mp4"
        }
        if (t.contains("leg press") || t.contains("presse a cuisse") || t.contains("hack squat") || t.contains("pendulum") || t.contains("belt squat")) {
            return "ex_leg_press.mp4"
        }
        if (t.contains("leg extension") || (t.contains("extension") && t.contains("jambe")) || t.contains("sissy")) {
            return "ex_leg_extension.mp4"
        }
        if (t.contains("leg curl") || t.contains("nordic") || (t.contains("ischio") && t.contains("curl")) || t.contains("glute ham") || t.contains("ghr") || t.contains("slider")) {
            return "ex_leg_curl.mp4"
        }
        if (t.contains("hip thrust") || t.contains("poussee de hanche") || t.contains("kas glute")) {
            return "ex_hip_thrust.mp4"
        }
        if (t.contains("pont fessier") || t.contains("glute bridge") || t.contains("frog pump") || t.contains("kickback") && sc == "fessiers" || t.contains("abduction") || t.contains("clamshell") || t.contains("fire hydrant")) {
            return "vision_glute_bridge.mp4"
        }
        if (t.contains("roumain") || t.contains("rdl") || t.contains("jambes tendues") || t.contains("sldl") || t.contains("good morning") || t.contains("hyperextension") || t.contains("back extension") || t.contains("pull through") || t.contains("reverse hyper")) {
            return "ex_romanian_deadlift.mp4"
        }
        if (t.contains("souleve de terre") || t.contains("deadlift") || t.contains("rack pull") || t.contains("clean") || t.contains("snatch") || (t.contains("epaule") && sc == "halterophilie") || t.contains("arrache")) {
            return "vision_deadlift.mp4"
        }
        if (t.contains("mollet") || t.contains("calf") || t.contains("soleus") || t.contains("soleaire") || t.contains("tibial") || t.contains("heel drop") || t.contains("short foot") || t.contains("achille")) {
            return "ex_calf_raise.mp4"
        }
        if (t.contains("fente") || t.contains("lunge") || t.contains("step up") || t.contains("step-up") || t.contains("pistol") || t.contains("cossack") || t.contains("skater")) {
            return "vision_lunge.mp4"
        }
        if (t.contains("squat") || t.contains("chaise") || t.contains("wall sit")) {
            return "vision_squat.mp4"
        }

        // 2. Chest & Pressing
        if (t.contains("incline") && (t.contains("developpe") || t.contains("press") || t.contains("bench") || sc == "pectoraux")) {
            return "ex_incline_press.mp4"
        }
        if (t.contains("ecarte") || (t.contains("fly") && !t.contains("rear") && !t.contains("invers") && !t.contains("oiseau") && !t.contains("l-fly") && !t.contains("l fly")) || t.contains("pec deck") || t.contains("butterfly") || t.contains("crossover") || t.contains("svend") || t.contains("pullover")) {
            return "ex_chest_fly.mp4"
        }
        if (t.contains("pompe") || t.contains("push up") || t.contains("push-up") || t.contains("pushup") || t.contains("planche push")) {
            return "vision_pushup.mp4"
        }
        if (t.contains("dip") || t.contains("repulsion")) {
            return "vision_tricep_dip.mp4"
        }
        if (t.contains("developpe couche") || t.contains("bench press") || t.contains("chest press") || t.contains("floor press") || t.contains("developpe decline") || t.contains("landmine press") || t.contains("chest pass")) {
            return "ex_bench_press.mp4"
        }

        // 3. Overhead & Shoulders
        if (t.contains("face pull") || t.contains("rotation externe") || t.contains("l-fly") || t.contains("coiffe") || t.contains("band pull apart") || t.contains("y-t-w") || t.contains("ytw") || t.contains("cuban") || t.contains("scapular") || t.contains("wall slide")) {
            return "ex_face_pull.mp4"
        }
        if (t.contains("oiseau") || t.contains("rear delt") || t.contains("deltoide posterieur") || t.contains("pec deck inverse") || t.contains("reverse fly") || t.contains("arriere epaule")) {
            return "ex_rear_delt_fly.mp4"
        }
        if (t.contains("shrug") || t.contains("haussement") || (t.contains("trapeze") && !t.contains("rowing"))) {
            return "ex_shrugs.mp4"
        }
        if ((t.contains("elevation") && t.contains("frontal")) || t.contains("front raise") || t.contains("bus driver") || t.contains("plate raise")) {
            return "ex_front_raise.mp4"
        }
        if ((t.contains("elevation") && t.contains("lateral")) || t.contains("lateral raise") || t.contains("lu raise") || t.contains("upright row") || t.contains("tirage menton")) {
            return "vision_lateral_raise.mp4"
        }
        if (t.contains("militaire") || t.contains("overhead") || t.contains("push press") || t.contains("arnold") || t.contains("z press") || t.contains("handstand") || t.contains("hspu") || t.contains("pike") || t.contains("split jerk") || t.contains("jerk") || (t.contains("developpe") && (sc == "epaules" || t.contains("epaule") || t.contains("haltere")))) {
            return "vision_overhead_press.mp4"
        }

        // 4. Back & Pulling
        if (t.contains("traction") || t.contains("pull up") || t.contains("pull-up") || t.contains("chin up") || t.contains("chin-up") || t.contains("muscle up") || t.contains("muscle-up") || t.contains("front lever") || t.contains("dead hang") || t.contains("suspension")) {
            return "vision_pull_up.mp4"
        }
        if (t.contains("tirage vertical") || t.contains("lat pulldown") || t.contains("pulldown") || t.contains("tirage poitrine") || t.contains("tirage bras tendus") || t.contains("straight arm") || t.contains("pullover poulie")) {
            return "ex_lat_pulldown.mp4"
        }
        if (t.contains("tirage horizontal") || t.contains("cable row") || t.contains("seated row") || t.contains("poulie basse") || t.contains("machine row") || t.contains("chest supported") || t.contains("trx") || t.contains("australian") || t.contains("rowing inverse") || t.contains("inverted row")) {
            return "ex_seated_cable_row.mp4"
        }
        if (t.contains("rowing") || t.contains("row") || t.contains("pendlay") || t.contains("meadows") || t.contains("t-bar") || t.contains("seal row") || t.contains("kroc") || t.contains("renegade")) {
            return "vision_bent_row.mp4"
        }

        // 5. Arms (Biceps, Triceps, Forearms)
        if (t.contains("marteau") || t.contains("hammer") || t.contains("zottman") || t.contains("wrist") || t.contains("tyler twist") || t.contains("pinch") || t.contains("curl inverse") || t.contains("reverse curl") || (sc == "avant-bras" && (t.contains("poignet") || t.contains("avant-bras") || t.contains("avant bras") || t.contains("pronation") || t.contains("supination") || t.contains("doigt")))) {
            return "ex_hammer_curl.mp4"
        }
        if (t.contains("curl") || t.contains("biceps") || t.contains("larry scott") || t.contains("pupitre") || t.contains("preacher") || t.contains("bayesian") || t.contains("spider")) {
            return "vision_bicep_curl.mp4"
        }
        if (t.contains("barre au front") || t.contains("skull") || t.contains("french press") || t.contains("extension nuque") || t.contains("overhead triceps") || t.contains("tate press") || t.contains("california")) {
            return "ex_skull_crusher.mp4"
        }
        if (t.contains("triceps") || t.contains("pushdown") || t.contains("poulie haute") || t.contains("kickback") || t.contains("extension corde") || t.contains("jm press")) {
            return "ex_tricep_pushdown.mp4"
        }

        // 6. Core & Abs
        if (t.contains("russian twist") || t.contains("pallof") || t.contains("woodchop") || t.contains("bucheron") || t.contains("oblique") || t.contains("bicycle") || t.contains("velo") || t.contains("windshield") || t.contains("essuie-glace") || (t.contains("rotation") && sc == "gainage")) {
            return "ex_russian_twist.mp4"
        }
        if (t.contains("mountain climber") || t.contains("grimpeur")) {
            return "ex_mountain_climber.mp4"
        }
        if ((t.contains("releve") && t.contains("jambe")) || t.contains("leg raise") || t.contains("toes to bar") || t.contains("l-sit") || t.contains("dragon flag") || t.contains("v-up") || t.contains("hollow") || t.contains("flutter") || t.contains("ciseaux") || t.contains("dead bug") || t.contains("knee raise")) {
            return "vision_leg_raise.mp4"
        }
        if (t.contains("crunch") || t.contains("sit up") || t.contains("sit-up") || t.contains("enroulement") || t.contains("ab wheel") || t.contains("roue abdominale")) {
            return "ex_crunch.mp4"
        }
        if (t.contains("planche") || t.contains("plank") || t.contains("gainage") || t.contains("bird dog") || t.contains("bear crawl") || t.contains("vacuum") || t.contains("stir the pot") || t.contains("mcgill") || t.contains("copenhagen") || t.contains("human flag")) {
            return "vision_plank.mp4"
        }

        // 7. Kettlebell, Carries, Plyo, Cardio & Mobility
        if (t.contains("farmer") || t.contains("marche") && (t.contains("fermier") || t.contains("leste") || t.contains("valise") || t.contains("pince") || t.contains("carry")) || t.contains("suitcase") || t.contains("waiter") || t.contains("zercher carry") || t.contains("yoke") || t.contains("sled")) {
            return "ex_farmer_walk.mp4"
        }
        if (t.contains("swing") || t.contains("kettlebell") && !t.contains("press") && !t.contains("squat") || t.contains("turkish get up") || t.contains("tgu") || t.contains("windmill") || t.contains("halo") || t.contains("scoop toss")) {
            return "ex_kettlebell_swing.mp4"
        }
        if (t.contains("box jump") || t.contains("saut") || t.contains("jump") && !t.contains("jumping jack") || t.contains("hop") || t.contains("bound") || t.contains("plyo") || t.contains("hurdle") || t.contains("cmj") || t.contains("broad")) {
            return "ex_box_jump.mp4"
        }
        if (t.contains("burpee") || t.contains("sprawl") || t.contains("man maker") || t.contains("devil press") || t.contains("slam")) {
            return "vision_burpee.mp4"
        }
        if (t.contains("jumping jack") || t.contains("corde a sauter") || t.contains("skip") || t.contains("sprint") || t.contains("course") || t.contains("montee de genou") || t.contains("talons fesses") || t.contains("rameur") || t.contains("assault") || t.contains("velo") || t.contains("hiit") || t.contains("tabata") || t.contains("battle rope") || t.contains("shadow")) {
            return "vision_jumping_jack.mp4"
        }
        if (t.contains("mobilite") || t.contains("etirement") || t.contains("stretch") || t.contains("yoga") || t.contains("90/90") || t.contains("pigeon") || t.contains("cat cow") || t.contains("chat vache") || t.contains("thoracique") || t.contains("foam roller") || t.contains("auto-massage") || t.contains("jefferson") || t.contains("scorpion") || t.contains("ouverture") || t.contains("cars") || t.contains("dislocation") || t.contains("world greatest")) {
            return "ex_mobility_stretch.mp4"
        }

        // 8. Guaranteed Subcategory Fallback (covers 100% of remaining specialized entries cleanly)
        return when (sc) {
            "quadriceps" -> "vision_squat.mp4"
            "fessiers" -> "ex_hip_thrust.mp4"
            "ischio-jambiers" -> "ex_romanian_deadlift.mp4"
            "chaine posterieure" -> "vision_deadlift.mp4"
            "mollets" -> "ex_calf_raise.mp4"
            "pectoraux", "poulie double + banc" -> "ex_bench_press.mp4"
            "dos" -> if (all.contains("poulie")) "ex_seated_cable_row.mp4" else "vision_bent_row.mp4"
            "epaules" -> "vision_overhead_press.mp4"
            "biceps" -> "vision_bicep_curl.mp4"
            "triceps" -> "ex_tricep_pushdown.mp4"
            "avant-bras" -> "ex_hammer_curl.mp4"
            "gainage" -> "vision_plank.mp4"
            "mobilite" -> "ex_mobility_stretch.mp4"
            "kettlebell" -> "ex_kettlebell_swing.mp4"
            "plyometrie" -> "ex_box_jump.mp4"
            "cardio / plyo" -> "vision_jumping_jack.mp4"
            "calisthenie" -> "vision_pushup.mp4"
            "halterophilie" -> "vision_deadlift.mp4"
            else -> "vision_squat.mp4"
        }
    }
}
