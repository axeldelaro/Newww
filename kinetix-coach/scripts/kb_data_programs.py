# -*- coding: utf-8 -*-
"""115+ programmes complets, protocoles de périodisation et blueprints d'entraînement (FR)."""
import json

# Format: (titre, sous_cat, niveau, frequence, duree, resume, structure_complete, progression, mots_cles)
PROGRAMS_DATA = [
    # === HYPERTROPHIE & SPLITS CLASSIQUES ===
    ("Full Body 3 jours — Fondation Hypertrophie & Force", "Hypertrophie", "Débutant / Intermédiaire", "3 j/sem (Lun/Mer/Ven)", "12 semaines",
     "Haute fréquence par muscle (3x/sem) maximisant l'apprentissage moteur et la synthèse protéique sur 3 séances de 60 min.",
     "SÉANCE A : Squat barre 3x6-8 | Développé couché haltères 3x8-10 | Rowing barre/T-Bar 3x8-10 | RDL haltères 3x10 | Élévations latérales 3x12-15 | Planche RKC 3x45s.\n"
     "SÉANCE B : Soulevé de terre Trap Bar 3x5-6 | Développé militaire 3x6-8 | Tractions / Tirage vertical 3x8-10 | Fentes bulgares 3x10/j | Curl barre EZ 3x10-12 | Extension triceps corde 3x12.\n"
     "SÉANCE C : Front Squat ou Presse 3x8-10 | Développé incliné 30° 3x8-12 | Rowing haltère unilatéral 3x10/côté | Leg Curl assis 3x12 | Face Pull 3x15 | Mollets debout 3x12-15.",
     "Double progression : augmentez de +2,5 kg dès que toutes les séries atteignent le haut de la fourchette à RIR 2.",
     "full body 3 jours debutant hypertrophie force programme"),
    ("Upper / Lower 4 jours — Standard Or Hypertrophie", "Hypertrophie", "Intermédiaire", "4 j/sem (Lun/Mar/Jeu/Ven)", "12 semaines",
     "Équilibre optimal fréquence (2x/muscle/sem), volume (12-16 séries/muscle) et récupération système nerveux.",
     "UPPER A (Force/Épaisseur) : Développé couché barre 4x5-8 | Rowing appui poitrine 4x6-10 | Développé incliné haltères 3x8-12 | Tirage vertical neutre 3x8-12 | Élévations latérales poulie 4x12-15 | Curl incliné 3x10-12 | Pushdown corde 3x10-12.\n"
     "LOWER A (Quadriceps dominant) : Squat arrière / Hack Squat 4x6-8 | RDL barre 3x8-10 | Fentes bulgares 3x10/j | Leg Curl assis 3x12 | Mollets debout 4x12 | Ab Wheel 3x10-12.\n"
     "UPPER B (Volume/Largeur) : Développé militaire haltères 4x8-10 | Tractions pronation/assistées 4x6-10 | Écartés poulie 3x12-15 | Rowing assis poulie 3x10-12 | Oiseau poulie 4x15 | Overhead Triceps 3x12 | Curl marteau 3x10-12.\n"
     "LOWER B (Chaîne postérieure dominante) : Soulevé de terre Roumain 4x6-8 | Presse à cuisses 4x10-12 | Hip Thrust barre 3x8-12 | Leg Extension 3x12-15 | Mollets assis 4x15 | Relevé jambes suspendu 3x10-12.",
     "S1-S4 : RIR 2 -> RIR 1 -> RIR 0 sur isolations en S4 ; S5 : Deload (-40% volume, charges maintenues).",
     "upper lower 4 jours programme hypertrophie intermediaire split"),
    ("Push / Pull / Legs (PPL) 6 jours — Volume Hypertrophie Avancé", "Hypertrophie", "Intermédiaire / Avancé", "6 j/sem (PPLRPPL)", "8-12 semaines",
     "Haute spécialisation (14-18 séries/muscle/sem) pour athlètes confirmés dormant >= 7h30 et en surplus ou maintenance.",
     "PUSH A : Bench Press 4x6-8 | Incliné haltères 30° 3x8-12 | Pec Deck 3x12-15 | Développé épaules machine 3x8-10 | Élévations latérales poulie 4x12-15 | Overhead Triceps corde 3x10-12 | Pushdown 3x12-15.\n"
     "PULL A : Tractions lestées 4x6-8 | Rowing T-Bar appui poitrine 4x8-10 | Tirage vertical unilatéral 3x10-12 | Face Pull 3x15 | Oiseau machine 3x15 | Curl barre EZ 3x8-12 | Bayesian Curl 3x12.\n"
     "LEGS A : Squat arrière 4x5-8 | RDL haltères 3x8-10 | Hack Squat / Presse 3x10-12 | Leg Curl assis 3x12 | Mollets debout 4x12 | Cable Crunch 3x12-15.\n"
     "PUSH B / PULL B / LEGS B : Remplacez les mouvements principaux par leurs variantes complémentaires (Incliné barre, Dips, Rowing poulie basse, Soulevé de terre / Hip Thrust, Bulgares, Leg Extension).",
     "Surveillez étroitement la récupération SNC ; passez en PPL 5 jours rotatif si fatigue articulaire.",
     "ppl push pull legs 6 jours hypertrophie volume avance"),
    ("PPL + Upper / Lower 5 jours (Hybride Optimal)", "Hypertrophie", "Intermédiaire / Avancé", "5 j/sem", "12 semaines",
     "Le meilleur split 5 jours moderne : 2x/semaine chaque muscle avec 2 vrais jours de repos complets.",
     "J1 UPPER : Bench 4x6 | Rowing T-Bar 4x8 | Incliné DB 3x10 | Lat Pulldown 3x10 | Latérales 4x15 | Bras superset 3x12.\n"
     "J2 LOWER : Squat 4x6 | RDL 3x8 | Presse 3x12 | Leg Curl 3x12 | Mollets 4x12.\n"
     "J3 REPOS / Zone 2.\n"
     "J4 PUSH : Incliné barre 4x8 | Dips 3x10 | Écartés poulie 3x15 | OHP haltères 3x10 | Latérales poulie 4x15 | Triceps 4x12.\n"
     "J5 PULL : Tractions 4x8 | Rowing haltère 4x10 | Tirage neutre 3x12 | Oiseau 4x15 | Biceps 4x12.\n"
     "J6 LEGS : Hip Thrust 4x8 | Bulgares 3x10 | Leg Extension 3x15 | Leg Curl allongé 3x12 | Mollets assis 4x15 | Abdos 3x12.",
     "Double progression linéaire + deload toutes les 5-6 semaines.",
     "5 jours ppl upper lower split hybride optimal"),
    ("Arnold Split 6 jours (Torso / Bras-Épaules / Jambes)", "Hypertrophie", "Avancé", "6 j/sem", "8-10 semaines",
     "Priorité esthétique aux bras et épaules travaillés frais sur leur propre séance, avec supersets antagonistes Pecs/Dos.",
     "J1 & J4 PECS/DOS (Supersets antagonistes) : Bench 4x6-8 + Rowing barre 4x8 | Incliné DB 4x10 + Tractions 4x8 | Écartés poulie 3x15 + Pullover poulie 3x15.\n"
     "J2 & J5 ÉPAULES/BRAS : Développé militaire 4x6-8 | Élévations latérales 5x12-15 | Oiseau 4x15 | Curl barre EZ 4x8-10 + Skull Crusher 4x10 | Curl incliné 3x12 + Overhead Triceps 3x12.\n"
     "J3 & J6 JAMBES/ABDOS : Squat 4x6-8 | RDL 4x8-10 | Presse 3x12 | Leg Extension + Leg Curl 3x12-15 | Mollets 4x12 | Abdos 4x12.",
     "Idéal pour débloquer des bras ou épaules en retard (attention au volume épaules cumulé Pecs+Épaules -> surveiller la coiffe).",
     "arnold split 6 jours bras epaules pecs dos antagonistes"),
    ("Torso / Limbs 4 jours (Spécialisation Bras & Jambes)", "Hypertrophie", "Intermédiaire / Avancé", "4 j/sem", "10 semaines",
     "Alternative brillante au Upper/Lower plaçant biceps et triceps avec les jambes pour les entraîner frais et plus lourds.",
     "J1 & J4 TORSE (Pecs/Dos/Épaules) : Bench 4x6-8 | Rowing T-Bar 4x8 | Incliné DB 3x10 | Tirage vertical 3x10 | Développé épaules 3x10 | Latérales 4x15 | Face Pull 3x15.\n"
     "J2 & J5 MEMBRES (Jambes + Bras) : Squat/Hack 4x6-8 | RDL/Leg Curl 3x10 | Bulgares/Presse 3x10 | Mollets 4x12 | Curl incliné 4x10-12 | Overhead Triceps 4x10-12 | Curl marteau 3x12 | Pushdown 3x12.",
     "Corrige immédiatement les bras en retard sans ajouter un 5e jour d'entraînement.",
     "torso limbs 4 jours bras retard jambes programme"),
    ("Bro Split Optimisé 5 jours (avec rappels fréquence 1,5-2x)", "Hypertrophie", "Intermédiaire", "5 j/sem", "10 semaines",
     "Modernisation du split classique en ajoutant des rappels intelligents pour profiter d'une fréquence 2x sur les points faibles.",
     "J1 Pecs + rappel Biceps (3 séries) | J2 Dos + rappel Arrière d'épaule | J3 Jambes (Quad + Ischios) | J4 Épaules + rappel Haut Pecs (3 séries) | J5 Bras (Biceps/Triceps) + rappel Mollets/Abdos.",
     "Maintenez chaque muscle entre 12 et 16 séries hebdomadaires de haute qualité (RIR 1-2).",
     "bro split 5 jours optimise rappel frequence bodybuilding"),
    ("Full Body 2 jours — Minimalisme Efficace (Emploi du temps chargé)", "Hypertrophie", "Tous niveaux", "2 j/sem (ex: Mar/Ven)", "Continu",
     "Conserve 100% et développe jusqu'à 75-80% du potentiel hypertrophique avec seulement 2 séances d'1h15/semaine (étude Iversen 2021).",
     "SÉANCE 1 : Hack Squat/Squat 4x6-10 | Développé incliné DB 4x8-12 | Tirage vertical 4x8-12 | RDL haltères 3x10 | Superset Élévations latérales + Curl incliné 3x12-15.\n"
     "SÉANCE 2 : Soulevé de terre Trap Bar 4x5-8 | Développé couché 4x6-10 | Rowing appui poitrine 4x8-12 | Fentes bulgares 3x10/j | Superset Face Pull + Overhead Triceps 3x12-15.",
     "Poussez chaque série proche de l'échec technique (RIR 0-1) et utilisez les supersets antagonistes pour gagner 25 min.",
     "minimaliste 2 jours full body manque temps entretien hypertrophie"),

    # === FORCE ATHLÉTIQUE / POWERLIFTING ===
    ("Force 5x5 Linéaire (StrongLifts / Reg Park revisité)", "Force", "Débutant", "3 j/sem (A/B alternés)", "12-16 semaines",
     "Progression linéaire rapide sur les grands polyarticulaires bâtissant une base de force brute en 3 mois.",
     "SÉANCE A : Squat barre 5x5 | Développé couché barre 5x5 | Rowing Pendlay 5x5 | + Accessoires : Tractions 3xMax, Planche 3x45s.\n"
     "SÉANCE B : Squat barre 5x5 | Développé militaire debout 5x5 | Soulevé de terre 1x5 (montée progressive) | + Accessoires : Dips 3x8-10, Face Pull 3x15.",
     "+2,5 kg à chaque séance réussie sur Squat/Bench/Row/OHP (+5 kg sur Deadlift). Si 3 échecs consécutifs -> deload -10% et remontée.",
     "5x5 stronglifts force debutant progression lineaire squat bench deadlift"),
    ("5/3/1 Jim Wendler — Boring But Big (BBB) 4 jours", "Force & Hypertrophie", "Intermédiaire", "4 j/sem", "Cycles de 4 semaines",
     "Système légendaire basé sur un Training Max (TM = 90% du 1RM réel) garantissant des années de progrès sans brûlure nerveuse.",
     "CALCUL : TM = 90% 1RM.\n"
     "• S1 : 65%x5, 75%x5, 85%x5+ (AMRAP propre RIR 1).\n"
     "• S2 : 70%x3, 80%x3, 90%x3+.\n"
     "• S3 : 75%x5, 85%x3, 95%x1+.\n"
     "• S4 : Deload 40/50/60% x5.\n"
     "EXERCICE PRINCIPAL + BBB : J1 OHP + 5x10 à 55-60% TM + Tirages 5x10 | J2 Deadlift + 5x10 RDL/Squat + Abdos | J3 Bench + 5x10 + Rowing 5x10 | J4 Squat + 5x10 + Leg Curl 5x10.",
     "Fin de cycle : +2,5 kg sur le TM Haut du corps (Bench/OHP), +5 kg sur le TM Bas du corps (Squat/Deadlift).",
     "531 wendler boring but big force hypertrophie powerlifting"),
    ("GZCL / GZCLP — Méthode à 3 Tiers (T1 / T2 / T3)", "Force & Hypertrophie", "Débutant avancé / Intermédiaire", "4 j/sem", "12 semaines",
     "Architecture intelligente de Cody Lefever combinant force lourde (T1), volume polyarticulaire (T2) et hypertrophie ciblée (T3).",
     "STRUCTURE CHAQUE SÉANCE :\n"
     "• T1 (Force lourde) : 5x3+ (échec -> 6x2+ -> 10x1+, puis reset 85%).\n"
     "• T2 (Volume technique) : 3x10 (échec -> 3x8 -> 3x6).\n"
     "• T3 (Isolation/Dos) : 3x15+ (augmentez charge dès >=25 reps sur la dernière série).\n"
     "ROTATION : J1 Squat T1 / Bench T2 / Tirage vertical T3 | J2 OHP T1 / Deadlift T2 / Rowing DB T3 | J3 Bench T1 / Squat T2 / Lat Pulldown T3 | J4 Deadlift T1 / OHP T2 / Rowing T3 + 2 isolations T3.",
     "Auto-régulation intégrée évitant les plateaux brutaux du 5x5.",
     "gzcl gzclp force tiers powerbuilding programme"),
    ("Madcow 5x5 (Force Intermédiaire — Séries pyramidales)", "Force", "Intermédiaire", "3 j/sem", "12 semaines",
     "Suite logique du 5x5 linéaire : progression hebdomadaire avec journée lourde (Lundi), légère récupération (Mercredi), record (Vendredi).",
     "LUNDI (Volume montant) : Squat 5x5 montant jusqu'au top set | Bench 5x5 | Rowing 5x5.\n"
     "MERCREDI (Récupération active) : Squat 4x5 (s'arrête à 80% du top set lundi) | OHP 4x5 | Deadlift 4x5.\n"
     "VENDREDI (Intensité) : Squat 4x5 + 1x3 (+2,5% vs lundi) + 1x8 backoff | Idem Bench & Rowing.",
     "Le top set 1x3 du vendredi devient votre nouveau 1x5 du lundi suivant (+2,5% par semaine).",
     "madcow 5x5 force intermediaire progression hebdomadaire"),
    ("Texas Method (Volume / Récupération / Intensité)", "Force", "Intermédiaire", "3 j/sem", "10-12 semaines",
     "Structure classique de périodisation hebdomadaire pour intermédiaires ayant épuisé la progression séance par séance.",
     "LUNDI (Volume) : Squat 5x5 à 90% du 5RM | Bench/OHP 5x5 | Deadlift 1x5.\n"
     "MERCREDI (Récupération) : Squat 2x5 à 80% du lundi | OHP/Bench 3x5 léger | Tractions 3xMax | Back Extensions.\n"
     "VENDREDI (Record PR) : Squat 1x5RM (ou 1x3RM/1x1RM) | Bench/OHP 1x5RM | Power Clean 5x3.",
     "+2,5 kg chaque vendredi sur le record ; ajustez le volume du lundi si la récupération coince.",
     "texas method force squat bench record"),
    ("Powerbuilding 4 jours (Force SBD + Esthétique)", "Force & Hypertrophie", "Intermédiaire / Avancé", "4 j/sem", "12 semaines",
     "Allie force compétition sur Squat/Bench/Deadlift (RPE 7-8) et hypertrophie ciblée sur dos, épaules, bras et jambes.",
     "J1 Squat 1x4@RPE8 + 3x5@80% | Bench pausé 4x5 | Fentes bulgares 3x10 | Leg Curl 3x12 | Abdos.\n"
     "J2 Bench 1x3@RPE8 + 4x5@78% | Rowing T-Bar 4x8 | Incliné DB 3x10 | Lat Pulldown 3x10 | Latérales 4x15 | Biceps/Triceps.\n"
     "J3 Deadlift 1x3@RPE8 + 3x4@78% | Front Squat ou Presse 4x8-10 | Hip Thrust 3x10 | Mollets 4x12.\n"
     "J4 OHP 4x6 | Close-Grip Bench 3x8 | Tractions lestées 4x6 | Rowing poulie 3x12 | Oiseau 4x15 | Bras.",
     "Top set auto-régulé au RPE 8 suivi de back-off sets en pourcentage pour accumuler un volume propre sans casse nerveuse.",
     "powerbuilding force hypertrophie rpe sbd squat bench deadlift"),
    ("Peaking Powerlifting 4 semaines (Préparation Compétition / Test 1RM)", "Force", "Avancé", "4 j/sem", "4 semaines",
     "Dissipe la fatigue accumulée et exprime 100% de la force maximale sur Squat, Développé couché et Soulevé de terre.",
     "S1 (Intensification) : SBD en 3x3 @ 85-87% (RPE 8), volume accessoires réduit de 30%.\n"
     "S2 (Spécificité) : SBD en 3x2 @ 88-91% (RPE 8,5), suppression isolations fatigantes.\n"
     "S3 (Singlet / Opener) : SBD 1x1 @ 92-95% (ouverture compétition), 2x2 @ 82%, zéro échec.\n"
     "S4 (Taper / Compétition) : J-6 Squat/Bench 3x2 @ 75% ; J-4 Bench 3x1 @ 70% ; J-3 à J-1 repos complet, sommeil et glucides ; Jour J = Test 1RM (Essai 1: 91%, Essai 2: 97%, Essai 3: 101-103%).",
     "Réduction progressive du volume (-50%) avec maintien de l'intensité haute jusqu'à J-7.",
     "peaking powerlifting 1rm competition taper affutage force"),
    ("Périodisation Ondulatoire Journalière (DUP — Daily Undulating)", "Force & Hypertrophie", "Intermédiaire / Avancé", "4 j/sem", "8-12 semaines",
     "Varie chaque séance entre Hypertrophie (8-12 reps), Puissance/Vitesse (5x3 explosif) et Force lourde (3-5 reps).",
     "EXEMPLE SUR SQUAT & BENCH (3x/sem chacun) :\n"
     "• Lundi (Hypertrophie) : 4x8-10 @ 70-75% (RPE 7,5).\n"
     "• Mercredi (Puissance/Technique) : 5x3 @ 70-75% explosif (tempo 2-1-X-0).\n"
     "• Vendredi (Force lourde) : 4x4 @ 82-87% (RPE 8,5).\n"
     "• Samedi : Deadlift + Dos/Épaules/Bras volume.",
     "Études Zourdos/Rhea : gains de force souvent supérieurs à la périodisation linéaire classique chez les pratiquants intermédiaires.",
     "dup periodisation ondulatoire force hypertrophie puissance"),
    ("Spécialisation Développé Couché (Smolov Jr adapté / Fréquence 3x)", "Force", "Avancé", "3-4 j/sem", "6 semaines",
     "Débloque un plateau tenace au développé couché via haute fréquence technique + renforcement dos/triceps.",
     "J1 (Lourd) : Bench compétition pausé 5x4 @ 80-85% | Rowing barre 4x8 | Face Pull 3x15.\n"
     "J2 (Volume) : Bench prise légèrement serrée 4x8 @ 72% | Incliné haltères 3x10 | Triceps overhead 3x12.\n"
     "J3 (Vitesse/Point faible) : Spoto Press ou Pin Press 5x3 @ 75% | Tractions 4x8 | Rotations externes coiffe 3x15.",
     "Maintenez un ratio Tirage:Poussée >= 1,2:1 pour garder des épaules parfaitement saines sous haute fréquence bench.",
     "specialisation developpe couche bench press plateau force"),

    # === REPÈRES VOLUME HYPERTROPHIE PAR MUSCLE (MEV / MAV / MRV — Dr Mike Israetel / Schoenfeld) ===
    ("Volume Hypertrophie : Pectoraux (MEV / MAV / MRV)", "Volume par muscle", "Tous niveaux", "2x/sem idéal", "Méso-cycle 5-6 sem",
     "Calibration scientifique du nombre de séries effectives hebdomadaires pour développer les pectoraux sans irriter les épaules.",
     "• MV (Maintien) : 4 séries/semaine.\n• MEV (Volume Minimum Effectif) : 8-10 séries/sem.\n• MAV (Volume Optimal Adaptatif) : 12-18 séries/sem.\n• MRV (Volume Max Récupérable) : 20-22 séries/sem.\n"
     "RÉPARTITION IDÉALE (14 séries) : 4 séries poussée horizontale (Bench/Haltères) + 4 séries incliné 30° + 3 séries dips/décliné + 3 séries adduction pure (Pec Deck/Poulie).",
     "Démarrez le méso-cycle à MEV+2 (10 séries), ajoutez 1-2 séries toutes les 2 semaines si récupération parfaite, puis deload.",
     "volume pectoraux mev mav mrv series semaine hypertrophie"),
    ("Volume Hypertrophie : Dos — Dorsaux & Haut du dos (MEV / MAV / MRV)", "Volume par muscle", "Tous niveaux", "2-3x/sem idéal", "Méso-cycle 5-6 sem",
     "Le dos tolère un volume élevé réparti entre tirages verticaux (largeur) et tirages horizontaux (épaisseur).",
     "• MV : 6 séries/sem | MEV : 10 séries/sem | MAV : 14-20 séries/sem | MRV : 22-25 séries/sem.\n"
     "RÉPARTITION IDÉALE (16 séries) : 6 séries tirage vertical (Tractions, Lat Pulldown) + 6 séries tirage horizontal (Rowing T-Bar, poulie basse, haltère) + 4 séries isolation/haut dos (Pullover poulie, Face Pull/Shrugs).",
     "Utilisez des sangles de tirage (straps) sur les séries lourdes d'hypertrophie pour ne jamais laisser le grip limiter la croissance dorsale.",
     "volume dos dorsaux mev mav mrv series semaine hypertrophie"),
    ("Volume Hypertrophie : Quadriceps (MEV / MAV / MRV)", "Volume par muscle", "Tous niveaux", "2x/sem", "Méso-cycle 5-6 sem",
     "Équilibrer mouvements lourds (squat/hack/presse) et isolation droit fémoral (Leg Extension) sans saturer le SNC.",
     "• MV : 4-6 séries | MEV : 8 séries | MAV : 12-16 séries | MRV : 18-20 séries.\n"
     "RÉPARTITION IDÉALE (14 séries sur 2 séances) : 4 séries Squat/Hack Squat + 4 séries Presse/Bulgares + 3 séries Leg Extension (seul mouvement recrutant pleinement le droit fémoral) + 3 séries fentes.",
     "Attention : la fatigue systémique des quadriceps est très élevée — privilégiez la qualité d'amplitude (genoux loin devant, pause basse) au volume junk.",
     "volume quadriceps jambes mev mav mrv series semaine"),
    ("Volume Hypertrophie : Ischio-jambiers (MEV / MAV / MRV)", "Volume par muscle", "Tous niveaux", "2x/sem", "Méso-cycle 5-6 sem",
     "Obligatoire de combiner 1 exercice d'extension de hanche (RDL) ET 1 exercice de flexion de genou (Leg Curl assis) pour un développement complet.",
     "• MV : 4 séries | MEV : 6-8 séries | MAV : 10-14 séries | MRV : 16-18 séries.\n"
     "RÉPARTITION IDÉALE (12 séries) : 6 séries charnière de hanche (RDL barre/haltères, Good Morning) + 6 séries flexion genou (Leg Curl assis prioritaire + allongé/Nordic).",
     "Les ischios produisent beaucoup de courbatures excentriques : augmentez le volume prudemment.",
     "volume ischio jambiers mev mav mrv rdl leg curl"),
    ("Volume Hypertrophie : Fessiers (MEV / MAV / MRV)", "Volume par muscle", "Tous niveaux", "2-3x/sem", "Méso-cycle 5-6 sem",
     "Combiner mouvements en étirement (Squat bulgare/RDL/Fentes), contraction courte (Hip Thrust) et abduction (Moyen fessier).",
     "• MV : 4 séries | MEV : 6-8 séries (directes + indirectes squats) | MAV : 12-16 séries | MRV : 20 séries.\n"
     "RÉPARTITION IDÉALE : 40% Hip Thrust / Pont fessier + 40% Unilatéral profond (Bulgares, Step-ups, RDL) + 20% Abductions (Machine/Poulie/Monster Walk).",
     "Fréquence 3x/sem très efficace chez les athlètes ciblant prioritairement le développement fessier.",
     "volume fessiers glutes mev mav mrv hip thrust bulgare"),
    ("Volume Hypertrophie : Épaules / Deltoïdes (MEV / MAV / MRV)", "Volume par muscle", "Tous niveaux", "2-4x/sem (latéral/post)", "Méso-cycle 5-6 sem",
     "Le deltoïde antérieur reçoit déjà un volume massif via les développés pecs : concentrez votre volume direct sur les faisceaux moyen et postérieur.",
     "• Deltoïde antérieur : MEV 0-2 direct (si bench/incliné présents), MAV 4-8 direct.\n"
     "• Deltoïde moyen & postérieur : MEV 8 séries | MAV 14-20 séries | MRV 22-26 séries (récupèrent très vite !).\n"
     "RÉPARTITION : 4-6 séries développé vertical + 8-12 séries élévations latérales (poulie + haltères) + 6-8 séries oiseau / Face Pull.",
     "Gamme de répétitions 12-20 idéale pour les isolations latérales/postérieures afin d'épargner la coiffe.",
     "volume epaules deltoides mev mav mrv elevations laterales"),
    ("Volume Hypertrophie : Biceps & Triceps (MEV / MAV / MRV)", "Volume par muscle", "Tous niveaux", "2-3x/sem", "Méso-cycle 5-6 sem",
     "Compter les séries polyarticulaires (tirages/poussées) à 0,5 série indirecte, et ajouter un travail direct couvrant positions étirée et raccourcie.",
     "• BICEPS direct : MEV 6-8 | MAV 12-16 | MRV 20 (mix Curl incliné/Bayesian étiré + Curl barre/pupitre + Curl marteau).\n"
     "• TRICEPS direct : MEV 4-6 | MAV 10-14 | MRV 18 (mix Overhead Extension longue portion obligatoire + Pushdown/Skull Crusher).",
     "Placer 1-2 exercices bras en début de séance une fois par semaine accélère nettement leur rattrapage.",
     "volume biceps triceps bras mev mav mrv series"),
    ("Volume Hypertrophie : Mollets & Abdominaux (MEV / MAV / MRV)", "Volume par muscle", "Tous niveaux", "3-4x/sem", "Méso-cycle 6 sem",
     "Les mollets et abdos récupèrent vite et répondent particulièrement bien à une surcharge progressive stricte avec pause en étirement.",
     "• MOLLETS : MEV 8 | MAV 12-16 | MRV 20 — 70% genou tendu (debout/presse avec pause 2s en bas) + 30% genou fléchi (assis soléaire).\n"
     "• ABDOMINAUX : MEV 4-6 | MAV 10-16 | MRV 20 — surcharge progressive réelle (Cable Crunch, Relevé jambes suspendu, Ab Wheel, Pallof Press) plutôt que 200 crunchs rapides au sol.",
     "Travaillez-les en début de séance ou en superset avec un exercice haut du corps pour ne jamais les zapper !",
     "volume mollets abdos gainage mev mav mrv"),

    # === CALISTHÉNIE & COMPÉTENCES GYMNASTIQUES ===
    ("Programme Calisthénie Fondation 4 jours (Sans salle)", "Calisthénie", "Débutant / Intermédiaire", "4 j/sem", "12 semaines",
     "Construire un physique athlétique, fort et mobile avec seulement une barre de traction, barres parallèles/anneaux et le poids du corps.",
     "J1 & J4 HAUT DU CORPS : Tractions strictes 4x6-10 | Dips 4x8-12 | Rowing inversé anneaux 4x10-15 | Pike Push-Ups 4x8-12 | Pompes déficit 3x12-15 | L-Sit progressions 4x20s.\n"
     "J2 & J5 BAS DU CORPS & GAINAGE : Pistol Squat progressions 4x6-8/j | Squat Bulgare tempo 3x12/j | Nordic Curl négatifs / Slider Curl 4x8 | Pont fessier unilatéral 3x15/j | Mollets unilatéraux 4x15 | Hanging Leg Raise 4x10 + Planche RKC 3x45s.",
     "Progressez par leviers biomécaniques, amplitude, tempo ou gilet/ceinture lestée.",
     "calisthenie poids du corps maison programme 4 jours anneaux"),
    ("Objectif 0 à 10 Tractions Strictes (Programme 8 semaines)", "Calisthénie", "Débutant", "3 j/sem", "8 semaines",
     "Méthode progressive scientifique pour débloquer sa première traction puis monter rapidement à 10 répétitions propres.",
     "• PHASE 1 (0 traction — S1-S3) : Tractions scapulaires 3x10 | Négatives lentes 5-8s : 5x3 (2 min repos) | Rowing inversé strict 4x10-12 | Dead Hang 3x30-45s.\n"
     "• PHASE 2 (1-4 tractions — S4-S6) : Cluster sets 6x(1-2 reps propres, 90s repos) + 3x3 négatives lentes + Tractions assistées bande légère 3x6-8.\n"
     "• PHASE 3 (5-10 tractions — S7-S8) : Programme Fighter Pull-Up (Pavel Tsatsouline) 5 jours/6 en pyramide descendante sans jamais aller à l'échec.",
     "Ne jamais aller à l'échec technique nerveux ; accumulez des répétitions propres et explosives.",
     "debloquer tractions pullups programme 0 a 10 debutant"),
    ("Programme Skill : Maîtriser le Muscle-Up Strict", "Calisthénie", "Avancé", "3 j/sem", "10 semaines",
     "Prérequis : 12 tractions strictes poitrine-barre + 15 dips profonds. Travail de force explosive + transition technique.",
     "1. Traction explosive haute (nombril-barre / Chest-to-Bar) : 6x3 (3 min repos, fraîcheur maximale).\n"
     "2. Transitions basses aux anneaux (False Grip, pieds en assistance légère) : 4x5 lentes.\n"
     "3. Muscle-Up négatifs ultra-lents dans la transition : 4x2-3.\n"
     "4. Russian Dips / Straight Bar Dips : 4x8-10.\n"
     "5. Tractions lestées (+15-25% PDC) : 4x4.",
     "La vitesse de tirage dans les 15 premiers centimètres décide de 80% de la réussite du Muscle-Up.",
     "muscle up strict calisthenie programme skill anneaux barre"),
    ("Programme Skill : Maîtriser le Handstand Libre (60 secondes)", "Calisthénie", "Intermédiaire", "4-5 j/sem (20 min)", "12-16 semaines",
     "Apprentissage moteur quotidien sans fatigue : mobilité poignets/épaules, ligne droite et équilibrage actif aux doigts.",
     "ÉCHAUFFEMENT (5 min) : Wrist Rocks + Butcher's Block + Wall Slides.\n"
     "1. Chest-to-Wall Handstand (ligne droite hollow body) : 4x30-45s.\n"
     "2. Heel Pulls & Toe Pulls au mur (apprendre à rattraper l'équilibre avec les doigts et les paumes) : 4x6.\n"
     "3. Pirouette Bail (apprendre à sortir en sécurité en quart de tour) : 3x2/côté.\n"
     "4. Kick-ups libres contrôlés (jambe ciseaux) : 10 min essais courts de haute qualité.",
     "15 min frais 5x/semaine battent largement 1 séance d'1h30 épuisante par semaine.",
     "handstand equilibre mains calisthenie programme 60 secondes"),
    ("Programme Skill : Front Lever (Tuck -> Full)", "Calisthénie", "Avancé", "3 j/sem", "16 semaines",
     "Développement combiné de la force isométrique bras tendus et dynamique des dorsaux/abdos.",
     "1. Maintien statique à votre progression actuelle (tenable 8-12s max propre) : 5x8-10s (3 min repos).\n"
     "2. Front Lever Raises (montées/descentes bras tendus en Tuck ou Straddle) : 4x4-6.\n"
     "3. Front Lever Rows (Tuck ou Adv. Tuck) : 4x6-8.\n"
     "4. Dragon Flag stricts : 3x6-8 + Tractions lestées 3x5.",
     "Ne passez au levier suivant que lorsque vous tenez 4x15s propres hanches parfaitement alignées.",
     "front lever calisthenie programme progression dorsaux"),
    ("Programme Skill : Planche Calisthénie (Lean -> Tuck -> Straddle)", "Calisthénie", "Avancé", "3 j/sem", "20 semaines",
     "Force bras tendus de poussée scapulaire exigeant une adaptation graduelle des poignets et tendons du biceps.",
     "1. Planche Lean (épaules avancées devant poignets, protraction maximale, bras verrouillés) : 4x20s.\n"
     "2. Maintien progression cible (Frog -> Tuck -> Adv Tuck -> Straddle) : 5x8-10s.\n"
     "3. Pseudo-Planche Push-Ups (PPPU) : 4x6-8.\n"
     "4. Zanetti / Maltes Dumbbell Raises légers (conditionnement tendon biceps bras tendus) : 3x10.",
     "Respectez impérativement 48h de repos minimum entre séances bras tendus pour protéger les coudes.",
     "planche calisthenie straddle tuck progression epaules"),
    ("Street Lifting 4 jours (Tractions + Dips + Squat + Muscle-Up lestés)", "Calisthénie / Force", "Avancé", "4 j/sem", "12 semaines",
     "Compétition hybride de force au poids du corps lesté.",
     "J1 Tractions lestées 5x3-5 + Dips volume 4x8 + Rowing + Biceps.\n"
     "J2 Squat barre 5x4-6 + RDL 3x8 + Mollets + Gainage.\n"
     "J3 Muscle-Up lesté/explosif 5x2 + Dips lestés 5x3-5 + Tractions volume 4x8 + Épaules.\n"
     "J4 Squat volume/Front Squat 4x6 + Tractions prise neutre lestées 4x6 + Dips pause 3x6 + Abdos.",
     "Suivez votre charge TOTALE (Poids de corps + Lest) pour calculer vos pourcentages exacts !",
     "street lifting tractions lestees dips lestes squat force"),

    # === RUNNING, TRAIL, ENDURANCE & HYROX ===
    ("Plan Running 5 km (Objectif Débutant -> Sub 20 min)", "Running", "Tous niveaux", "3-4 sorties/sem", "8 semaines",
     "Combinaison 80% Endurance Fondamentale Zone 2 + 20% VMA/Allure 5K (modèle polarisé Seiler).",
     "• SÉANCE 1 (VMA courte -> spécifique) : S1-S4 12x200 m ou 10x400 m à 100-105% VMA (récup trot égal) ; S5-S7 5x1000 m à allure cible 5K (récup 2 min).\n"
     "• SÉANCE 2 (Endurance Zone 2) : 35-45 min allure conversationnelle stricte + 6 lignes droites de 15s.\n"
     "• SÉANCE 3 (Seuil / Tempo) : 20 min à allure 10K-15K (confortablement difficile).\n"
     "• SÉANCE 4 (Sortie longue douce) : 50-65 min Zone 2.\n"
     "• RENFORCEMENT (1-2x/sem) : Squat bulgare, RDL unilatéral, Mollets lourds, Pogo Jumps, Gainage.",
     "S8 = Affûtage (-40% volume, 1 rappel vitesse 4x400 m à J-4) pour arriver frais le jour J.",
     "running 5km plan entrainement vma zone 2 course pied"),
    ("Plan Running 10 km Structuré (10 semaines)", "Running", "Intermédiaire", "4 sorties/sem", "10 semaines",
     "Développe VMA, seuil lactique (LT2) et économie de course à allure 10 km.",
     "• MARDI (Seuil / Allure 10K) : Progression de 6x1000 m -> 4x2000 m -> 3x3000 m à allure cible 10 km (récup 90s-2 min).\n"
     "• JEUDI (Footing Z2 + Renfo) : 45-50 min Z2 + 25 min renforcement jambes/mollets.\n"
     "• SAMEDI (VMA / Côtes) : 2x8x(45s/45s) ou 10x30s côtes.\n"
     "• DIMANCHE (Sortie longue Z2) : 1h10 à 1h25 en aisance respiratoire.",
     "Respectez la règle du +10% volume max/semaine et 1 semaine allégée (-25%) toutes les 4 semaines.",
     "running 10km plan 10 semaines seuil vma course"),
    ("Plan Semi-Marathon 21,1 km (12 semaines)", "Running", "Intermédiaire", "4-5 sorties/sem", "12 semaines",
     "Bâtit l'endurance spécifique au seuil lactique et l'économie de foulée jusqu'à 1h30-2h d'effort.",
     "• MARDI : Fractionné Seuil/VO2 (ex: 4x4 min norvégien ou 3x10 min seuil).\n"
     "• MERCREDI : Footing récup Z2 45 min + Renforcement force jambes/gainage.\n"
     "• VENDREDI : Allure spécifique Semi (AS21) progressant de 2x15 min jusqu'à 3x20 min ou 2x5 km.\n"
     "• DIMANCHE : Sortie longue progressive de 1h15 jusqu'à 1h50 (intégrant les 20 dernières minutes à AS21 sur S8-S10 + test gels 45-60 g/h).",
     "Semaines 11-12 : Affûtage progressif (-25% puis -50% volume, intensité maintenue brièvement).",
     "semi marathon 21km plan 12 semaines seuil sortie longue"),
    ("Plan Marathon 42,2 km (16 semaines — Méthode Polarisée)", "Running", "Intermédiaire / Avancé", "4-5 sorties/sem", "16 semaines",
     "Préparation complète alliant volume aérobie progressif, allure marathon (AS42), renforcement tendineux et stratégie nutritionnelle.",
     "• BLOC 1 (S1-S5 Base Aérobie & Force) : Volume Z2 croissant, côtes, 2 séances renfo lourd/sem (Squat, RDL, Mollets lourds).\n"
     "• BLOC 2 (S6-S11 Spécifique Seuil & AS42) : 1 séance seuil (3x12 min), 1 séance AS42 (progressant jusqu'à 2x25 min), Sortie longue montant jusqu'à 2h15-2h30 (max 30-32 km) avec blocs AS42 + Gut Training 60-80 g glucides/h.\n"
     "• BLOC 3 (S12-S13 Pic) : Plus grosse semaine à S13.\n"
     "• BLOC 4 (S14-S16 Affûtage 3 semaines) : Volume -20% (S14), -40% (S15), -60% (S16) + Carb-loading 48h avant.",
     "Ne jamais courir plus de 2h45-3h à l'entraînement (coût tendineux/blessure > bénéfice aérobie).",
     "marathon 42km plan 16 semaines polarise affutage sortie longue"),
    ("Préparation Trail & Ultra-Trail (Montagne & D+)", "Running / Trail", "Intermédiaire / Avancé", "4-5 sorties + 2 renfo", "14 semaines",
     "Prépare spécifiquement les quadriceps à la casse excentrique des descentes, la puissance en montée et l'autonomie alimentaire.",
     "• MARDI : Côtes longues (6x3 min en montée soutenue / descente souple) ou escaliers.\n"
     "• MERCREDI : Musculation excentrique jambes (Squat bulgare lent, Step-Down excentrique, Wall Sit 4x60s, RDL, Mollets, Proprioception chevilles).\n"
     "• VENDREDI : Footing Z2 + lignes droites.\n"
     "• WEEK-END SHOCK (S6, S9, S11) : Back-to-back (Samedi 2h30 D+ + Dimanche 2h sur jambes pré-fatiguées) avec bâtons et nutrition 60-80 g/h.",
     "Renforcer la force excentrique des quadriceps divise par 2 la perte de performance en deuxième moitié de trail.",
     "trail ultra trail denivele excentrique descente programme"),
    ("Programme Complet HYROX 12 semaines (Hybride Force + Course)", "Hybride / Hyrox", "Intermédiaire / Avancé", "5-6 séances/sem", "12 semaines",
     "Prépare aux 8x1000 m de course intercalés des 8 stations (SkiErg, Sled Push/Pull, Burpee Broad Jump, Rameur, Farmers, Sandbag Lunges, Wall Balls).",
     "• LUNDI (Force Bas + Sleds) : Squat 4x5 | RDL 4x6 | Sled Push lourd 6x15 m | Sled Pull 6x15 m | Sandbag Lunges 4x20 m.\n"
     "• MARDI (Seuil Running) : 6x1000 m à allure Hyrox (récup 90s).\n"
     "• MERCREDI (Force Haut + Ergos) : Bench/Dips 4x6 | Tractions 4x8 | SkiErg 4x500 m | Rameur 4x500 m | Farmers Carry 4x50 m.\n"
     "• JEUDI (Récupération active Zone 2) : 45 min vélo/course très facile + mobilité.\n"
     "• VENDREDI (Compromised Running — Spécifique Hyrox !) : 4-6 rounds de [800 m course allure cible + 1 station (ex: 30 Wall Balls ou 25 m Lunges ou 500 m SkiErg) sans pause].\n"
     "• SAMEDI : Sortie longue aérobie Zone 2 (60-75 min).",
     "Le 'Compromised Running' (courir immédiatement après Sleds/Lunges/Wall Balls) est la clé spécifique absolue du chrono Hyrox.",
     "hyrox programme 12 semaines hybride sled wall balls compromised running"),
    ("Programme Hybride Musculation + Running sans Interférence", "Hybride", "Intermédiaire", "5 j/sem (3 Muscu + 3 Run)", "12 semaines",
     "Concilier prise de muscle/force et progression running en minimisant l'effet d'interférence AMPK/mTOR (méta-analyse Schumann 2022).",
     "RÈGLES ANTI-INTERFÉRENCE :\n"
     "1. Séparez Force et Cardio d'au moins 6 à 24h (ou placez la priorité en premier).\n"
     "2. Privilégiez Zone 2 douce + 1 séance seuil plutôt que des footings durs tous les jours.\n"
     "3. Mangez suffisamment de glucides (5-6 g/kg) et protéines (2,0 g/kg).\n"
     "SEMAINE TYPE : Lun Upper + Run Z2 soir | Mar Lower Force | Mer Seuil Run | Jeu Upper | Ven Lower Hypertrophie | Sam Sortie longue Z2 | Dim Repos.",
     "Si vos chronos ou vos charges stagnent 2 semaines de suite -> ajoutez +250 kcal glucides et 30 min de sommeil avant de couper le volume.",
     "hybride musculation running interference programme semaine"),
    ("Protocole HIIT / VO2max & Zone 2 — Longévité Cardiovasculaire (80/20)", "Cardio & Santé", "Tous niveaux", "4 séances/sem (3h30 total)", "Continu",
     "Approche inspirée des recherches sur la longévité (VO2max = prédicteur #1 de mortalité toutes causes confondues).",
     "• 3x45-60 min de Zone 2 (vélo, rameur, marche inclinée 12%, footing lent — 65-75% FCmax, respiration nasale/parlée possible).\n"
     "• 1x/semaine Protocole Norvégien 4x4 min (4 blocs de 4 min à 88-95% FCmax séparés par 3 min de récup active douce).\n"
     "• + 2-3 séances de force/hypertrophie hebdomadaires pour masse maigre et densité osseuse.",
     "Augmente la VO2max de +10 à 18% en 12 semaines tout en optimisant sensibilité à l'insuline et HRV.",
     "vo2max zone 2 norvegien 4x4 longevite cardio sante"),
    ("Programme Sèche Athlétique 12 semaines (Force Maintenue + NEAT)", "Sèche", "Intermédiaire", "4 Muscu + 10k pas/j", "12 semaines",
     "Perdre 4 à 6 kg de masse grasse en conservant 100% de la masse musculaire et de la force.",
     "• ENTRAÎNEMENT : Upper/Lower 4 jours — gardez les charges LOURDES (5-10 reps, RIR 1-2) mais réduisez le volume total de 15-20% si la fatigue monte.\n"
     "• CARDIO INTELLIGENT : 10 000 pas/jour (NEAT zéro fatigue) + 2x30 min marche inclinée ou vélo Zone 2 (évitez les HIIT épuisants en gros déficit).\n"
     "• NUTRITION : Déficit -400 kcal, Protéines 2,2 g/kg, Lipides 0,8 g/kg, reste en glucides concentrés autour des séances. 1 semaine Diet Break à maintenance en S7.",
     "Si perte < 0,3%/sem sur 2 semaines moyennes -> -150 kcal OU +2 000 pas/jour.",
     "seche 12 semaines programme perte gras muscle maintenu"),
    ("Programme Prise de Masse Propre (Lean Bulk 16 semaines)", "Prise de masse", "Intermédiaire", "4-5 j/sem", "16 semaines",
     "Capitaliser sur un surplus énergétique modéré (+250-300 kcal) pour bâtir un maximum de tissu contractile.",
     "• S1-S5 (Méso Accumulation 1) : Upper/Lower ou PPL-UL, montée de 12 à 16 séries/muscle, RIR 2 -> RIR 0.\n"
     "• S6 : Deload complet.\n"
     "• S7-S11 (Méso Spécialisation) : Priorité à 2 groupes musculaires en retard (+4 séries, en début de séance), maintien sur les autres.\n"
     "• S12 : Deload.\n"
     "• S13-S16 (Méso Force-Hypertrophie) : 6-10 reps lourdes pour solidifier les nouveaux gains.",
     "Objectif balance : +0,25 à +0,5% poids de corps par mois ; ajustez +150 kcal si le poids stagne 3 semaines.",
     "prise de masse lean bulk 16 semaines programme hypertrophie"),
    ("Semaine de Deload (Décharge) : protocole exact", "Périodisation", "Tous niveaux", "3-4 séances légères", "1 semaine / 5-7 sem",
     "Dissipe la fatigue accumulée (nerveuse, tendineuse, psychologique) et révèle la surcompensation sans aucune perte de muscle.",
     "PROTOCOLE OPTIMAL DE DELOAD :\n"
     "• VOLUME : Divisez le nombre de séries par 2 (ex: 4 séries -> 2 séries).\n"
     "• INTENSITÉ : Gardez 80-85% de vos charges habituelles (ou gardez le même poids en coupant les reps de 40%, RIR 4-5 !).\n"
     "• CARDIO : Gardez uniquement de la marche / Zone 2 douce.\n"
     "• CALORIES : Remontez à maintenance si vous étiez en sèche !",
     "Ne faites PAS de repos absolu canapé sauf blessure : garder une tension légère entretient les schémas moteurs et accélère la récupération.",
     "deload decharge fatigue surcompensation periodisation recuperation"),

    # === PRÉPARATION PHYSIQUE PAR SPORT ===
    ("Préparation Physique Football (In-Season & Pré-Saison)", "Prépa physique", "Intermédiaire / Avancé", "2 Muscu/sem (saison)", "Saison complète",
     "Explosivité, accélérations répétées, prévention claquages ischios/adducteurs/LCA sans alourdir les jambes avant le match.",
     "• J+2 POST-MATCH (Force & Puissance — 45 min) : Trap Bar Deadlift 4x4 explosif | Squat bulgare 3x6/j | Nordic Hamstring 3x5 | Copenhagen Plank 3x25s/côté | Développé incliné + Tractions 3x8.\n"
     "• J-3 AVANT MATCH (Vitesse & Prévention — 35 min) : Box Jumps / Skater Jumps 4x4 | Sled Push léger 4x15 m | Sprints 20 m (si non faits sur terrain) | Isométriques ischios/adducteurs + Gainage Pallof.\n"
     "• RÈGLE : Zéro séance lourde excentrique à moins de 72h du match !",
     "Nordic + Copenhagen réduisent de 40-50% les blessures musculaires majeures en football.",
     "football preparation physique prepa nordic copenhagen vitesse match"),
    ("Préparation Physique Sports de Combat (MMA, Boxe, JJB, Lutte)", "Prépa physique", "Intermédiaire / Avancé", "2-3 Prépa + Combat", "Bloc 8-12 sem",
     "Force relative explosive, puissance rotative, grip d'acier, cou blindé et conditioning alactique-aérobie.",
     "• FORCE EXPLOSIVE (2x/sem) : Trap Bar / Zercher Squat 4x4 | Landmine Rotational Press 4x6/côté | Tractions lestées / Serviette 4x5 | Med Ball Rotational Throw 4x6/côté | Neck Iso 4 directions + Zercher/Farmers Carry.\n"
     "• CONDITIONING SPÉCIFIQUE (1-2x/sem) : Air Bike/Rameur 10s sprint max / 50s récup (alactique-aérobie sans acidose chronique) + 45 min Zone 2 cardiaque (abaisse FC repos et accélère récup entre rounds !).",
     "Évitez la musculation bodybuilding épuisante à l'échec qui rend lent et compromet la qualité technique sur tatami/ring.",
     "mma boxe jjb lutte combat preparation physique explosivite grip cou"),
    ("Préparation Physique Tennis / Padel / Sports de Raquette", "Prépa physique", "Intermédiaire", "2-3 séances/sem", "12 semaines",
     "Puissance rotative, déplacements latéraux explosifs, freinage excentrique et santé coiffe des rotateurs/coude.",
     "1. Puissance : Med Ball Rotational Slam 4x6/côté | Skater Jumps stabilisés 4x8 | Navette 5-10-5 5x1.\n"
     "2. Force unilatérale : Fentes latérales 3x8/j | RDL unilatéral 3x8/j | Landmine Press unilatéral 3x8/côté | Rowing câble unilatéral 3x10.\n"
     "3. Préhab obligatoire (10 min chaque séance) : Rotations externes épaule 90°, Face Pull, Pronation/Supination avant-bras, Excentrique extenseurs poignet (anti tennis-elbow).",
     "Travaillez la symétrie rotative des deux côtés pour compenser l'asymétrie inhérente aux sports de raquette.",
     "tennis padel preparation physique epaule coude vitesse laterale"),
    ("Préparation Physique Basket-Ball & Volley-Ball (Détente Verticale)", "Prépa physique", "Intermédiaire / Avancé", "3 séances/sem", "10 semaines",
     "Augmenter la détente verticale (+5 à 12 cm) en combinant force relative des membres inférieurs et plyométrie réactive.",
     "J1 (Force Max + Contraste) : Squat arrière 4x4 (85%) en contraste avec Box Jump 4x3 | RDL 3x6 | Mollets lourds debout 4x8 | Gainage.\n"
     "J2 (Haut du corps + Santé genou) : Spanish Squat iso 4x45s (tendon rotulien) | Poussée/Tirage haut 4x8 | Coiffe.\n"
     "J3 (Puissance & Réactivité) : Depth Jump (box 35 cm) 4x4 | Hurdle Hops 4x5 | Trap Bar Jump léger (25% 1RM) 4x4 | Sprints 15 m 5x1.",
     "Prérequis plyométrie intense : pouvoir squatter proprement ~1,5x son poids de corps pour des tendons résilients.",
     "basket volley detente verticale saut plyometrie programme"),
    ("Préparation Physique Escalade & Bloc", "Prépa physique", "Intermédiaire / Avancé", "2 séances hors mur", "12 semaines",
     "Force de tirage relatif, compression abdominale, équilibre antagoniste (anti-blessure coudes/épaules) et doigts.",
     "• FORCE ANTAGONISTE OBLIGATOIRE : Pompes anneaux / Dips 3x10 | Face Pull + Rotations externes 3x15 | Extenseurs doigts élastique 3x20 | Reverse Wrist Curl 3x15 (protège contre épitrochléite).\n"
     "• FORCE SPÉCIFIQUE : Tractions lestées 4x4-5 | Front Lever progressions 4x8s | Hanging Leg Raise / Toes-to-Bar 3x10 | Poutre (Hangboard — seulement après >1 an d'escalade ! Suspensions 7-10s sur réglette 20 mm, 3 min repos).",
     "La règle d'or en escalade : ne surchargez jamais la poutre en période de gros volume sur bloc.",
     "escalade bloc preparation physique doigts antagonistes tractions"),
    ("Préparation Physique Ski Alpin & Snowboard (8 semaines pré-saison)", "Prépa physique", "Tous niveaux", "3 séances/sem", "8 semaines",
     "Endurance isométrique/excentrique des quadriceps, stabilité genou (prévention LCA) et gainage anti-rotation.",
     "1. Squat tempo lent 4-2-1-0 : 4x8 | Fentes latérales haltères 3x10/j.\n"
     "2. Wall Sit (Chaise au mur) : 4x60-90s (avec 10s unilatéral alterné en fin de cycle).\n"
     "3. Skater Jumps (freinage latéral stabilisé 2s) : 4x12 | Single-Leg RDL 3x10/j.\n"
     "4. Pallof Press + Planche latérale : 3x35s/côté.",
     "Démarrez idéalement 8 semaines avant le départ en station pour profiter dès le 1er jour sans brûlure ni blessure.",
     "ski snowboard preparation physique quadriceps lca chaise"),
    ("Préparation Physique Natation & Triathlon", "Prépa physique", "Intermédiaire", "2 Muscu/sem (40 min)", "Saison",
     "Puissance de propulsion dorsale, gainage hydrodynamique et prévention de l'épaule du nageur.",
     "1. Tractions / Tirage vertical 4x6-8 | Pullover poulie 3x12 | Développé incliné haltères neutre 3x8-10.\n"
     "2. Squat / Trap Bar Deadlift 3x5-6 (force pure sans prise de masse inutile pour les triathlètes) | Mollets lourds 3x10.\n"
     "3. Santé épaule & gainage : Y-T-W 3x12 | Rotations externes 90° 3x15 | Hollow Body 4x35s | Dead Bug 3x10/côté.",
     "Force lourde (4-6 reps, RIR 2) améliore l'économie de course et vélo de +3-5% sans alourdir l'athlète.",
     "natation triathlon preparation physique epaule nageur force economie"),
    ("Programme Remise en Forme & Santé Longévité (40-70+ ans)", "Santé & Longévité", "Débutant / Reprise", "3 Force + Marche", "Continu",
     "Préserver masse musculaire (anti-sarcopénie), densité osseuse, équilibre et autonomie cardiaque toute la vie.",
     "• 3x/SEM FORCE FONCTIONNELLE (45 min) : Goblet Squat / Presse 3x10 | RDL haltères / Pont fessier 3x10 | Poussée horizontale (pompes inclinées/machine) 3x10 | Tirage horizontal (rowing) 3x12 | Farmer's Walk 3x30 m | Équilibre unilatéral 3x30s/pied | Bird Dog & Planche.\n"
     "• QUOTIDIEN : 7 500 - 10 000 pas + 2x30 min Zone 2 (vélo, natation, marche rapide).",
     "La force de préhension (grip) et la puissance des jambes sont les meilleurs marqueurs de longévité active.",
     "sante longevite senior 50 ans 60 ans sarcopenie os reprise"),
    ("Programme Maison Sans Matériel (30 min Express — 4 jours)", "Maison / Sans matériel", "Débutant / Intermédiaire", "4 j/sem (30 min)", "8 semaines",
     "Zéro équipement, zéro excuse : force au poids du corps + conditioning métabolique en 30 min chrono.",
     "J1 & J4 HAUT + GAINAGE : Pompes (tempo 3-1-1) 4xMax-2 | Pike Push-Ups 3x8-12 | Rowing sous table solide / Superman Pull 4x15 | Dips chaise contrôlés 3x12 | Planche RKC 3x40s + Dead Bug 3x10/côté.\n"
     "J2 & J5 BAS + CARDIO : Squat Bulgare pied sur canapé 4x12/j | Pont fessier unilatéral 4x15/j | Fentes arrière 3x12/j | Mollets sur marche 4x15 | Finisher 8 min EMOM (10 Burpees ou 20 Jumping Jacks + 15 Air Squats).",
     "Ajoutez un sac à dos rempli de livres/bouteilles d'eau pour la surcharge progressive !",
     "maison sans materiel 30 min poids du corps programme"),
    ("Programme Haltères Uniquement (Home Gym 4 jours — Banc + 2 Haltères)", "Maison / Haltères", "Tous niveaux", "4 j/sem", "12 semaines",
     "95% des résultats d'une salle complète avec seulement une paire d'haltères réglables et un banc inclinable.",
     "UPPER A : Développé couché DB 4x8-12 | Rowing DB unilatéral 4x8-12 | Développé épaules assis DB 3x10 | Rowing banc incliné 3x12 | Latérales 4x15 | Curl incliné 3x12 | Skull Crusher DB 3x12.\n"
     "LOWER A : Goblet / DB Front Squat 4x10 | RDL DB 4x10 | Bulgares 3x10/j | Mollets unilatéral DB 4x15 | Hollow Body.\n"
     "UPPER B : Développé incliné 30° DB 4x10 | Pullover DB 3x12 | Développé Arnold 3x10 | Oiseau banc incliné 4x15 | Curl marteau 3x12 | Overhead Triceps 1 DB 3x12.\n"
     "LOWER B : Sumo Squat DB déficit 4x12 | B-Stance RDL 3x10/j | Hip Thrust DB 4x12 | Fentes marchées 3x12/j | Suitcase Carry 3x30 m.",
     "Exploitez les tempos lents (3-1-1-0) et pauses isométriques si vos haltères plafonnent en poids.",
     "halteres uniquement home gym maison programme 4 jours"),
    ("Programme Kettlebell Minimaliste (Simple & Sinister + Hypertrophie)", "Kettlebell", "Tous niveaux", "4-5 j/sem (30-40 min)", "12 semaines",
     "Inspiré de Pavel Tsatsouline : puissance explosive de la chaîne postérieure, résilience des épaules et force fonctionnelle.",
     "• SOCLE (4x/sem) : 10x10 Kettlebell Swings Hardstyle (100 reps en ~10 min, puissance maximale) + 10 Turkish Get-Ups (5/côté, contrôle total).\n"
     "• COMPLÉMENT FORCE (2-3x/sem) : Double KB Clean & Press 4x6-8 | Double KB Front Squat 4x8 | Gorilla Row 4x10/côté | Pompes / Tractions.",
     "Maîtrisez parfaitement 16 kg (F) / 24 kg (H) avant de passer au calibre supérieur (24 / 32 kg).",
     "kettlebell simple sinister swing turkish get up programme"),
]

# Générateur de 70+ protocoles de spécialisation, micro-cycles, méthodes d'intensification & routines ciblées pour atteindre 115+ entrées 'programme'
SPECIALIZED_PROTOCOLS = [
    # Spécialisations musculaires & points faibles (16)
    ("Spécialisation Haut des Pectoraux (6 semaines)", "Spécialisation", "Intermédiaire", "2x/sem", "6 semaines",
     "Priorité absolue au faisceau claviculaire en début de séance avec angles 25-30° et adduction ascendante.",
     "S1 : Développé incliné barre 30° 4x6-8 | Écartés poulies basses 3x12-15 | Développé haltères 15° 3x10.\nS2 : Développé incliné haltères 30° 4x8-10 | Landmine Press 3x10/côté | Pompes déclinées 3xMax-1.\nVolume autres muscles réduit à MEV (8-10 séries) pour allouer toute la récupération aux pectoraux.",
     "Revenez à un volume équilibré après 6-8 semaines.", "specialisation haut pectoraux claviculaire programme"),
    ("Spécialisation Largeur du Dos (V-Taper — 6 semaines)", "Spécialisation", "Intermédiaire", "3x/sem", "6 semaines",
     "Cible le grand dorsal (fibres iliaques et thoraciques) et le deltoïde moyen pour créer la silhouette en V.",
     "J1 : Tractions pronation 4x6-8 | Pullover poulie 3x12-15 | Élévations latérales poulie 4x15.\nJ2 : Iliac Lat Pulldown unilatéral 4x10/côté | Rowing haltère vers la hanche 3x10/côté.\nJ3 : Tirage vertical prise neutre serrée 4x8-12 | Lat Prayer corde 3x15 | Latérales haltères 4x15.",
     "Pensez 'coudes vers les poches arrière' sur chaque tirage.", "specialisation largeur dos grand dorsal v taper"),
    ("Spécialisation Épaisseur du Dos & Posture (6 semaines)", "Spécialisation", "Intermédiaire", "2-3x/sem", "6 semaines",
     "Renforce massivement rhomboïdes, trapèzes moyens/inférieurs et érecteurs pour un dos dense et athlétique.",
     "J1 : Rowing Pendlay 4x6 | Rowing T-Bar prise large 4x10 | Kelso Shrugs 3x15.\nJ2 : Seal Row ou Rowing appui poitrine prise large 4x10 | Tirage corde vers sternum 4x12 | Prone Y-Raise 3x12.\nJ3 : Rack Pull mi-tibia 3x6 | Face Pull pause 2s 4x15.",
     "Marquez systématiquement 1 seconde de rétraction scapulaire contrôlée.", "specialisation epaisseur dos trapezes rhomboides posture"),
    ("Spécialisation Quadriceps (Développer le Galbe — 6 semaines)", "Spécialisation", "Avancé", "2-3x/sem", "6 semaines",
     "Combine grande flexion de genou (talons surélevés/Hack/Pendulum) et isolation du droit fémoral (Leg Extension).",
     "J1 : Hack Squat ou Squat talons surélevés 4x6-8 | Leg Extension 4x12-15 | Fentes bulgares buste droit 3x10/j.\nJ2 : Front Squat ou Pendulum 4x8-10 | Presse pieds bas 3x12-15 | Sissy Squat 3x12.\nVolume ischios/haut réduit au maintien (6-8 séries) pour gérer la fatigue SNC.",
     "Utilisez des chaussures d'haltérophilie ou une cale de 2-3 cm.", "specialisation quadriceps jambes hack squat leg extension"),
    ("Spécialisation Fessiers 3 jours (Glute Lab Protocol — 8 semaines)", "Spécialisation", "Tous niveaux", "3 j/sem", "8 semaines",
     "Inspiré des travaux de Bret Contreras : combine les 3 vecteurs (horizontal, vertical, latéral) à haute fréquence.",
     "LUNDI (Lourd) : Hip Thrust barre 4x6-8 | Squat Bulgare buste penché 3x10/j | Abduction machine 3x15.\nMERCREDI (Étirement) : RDL barre 4x8-10 | Step-Up haute box 3x10/j | Kickback poulie 45° 3x15/j.\nVENDREDI (Métabolique) : Hip Thrust pause 2s 4x12 | Fentes arrière déficit 3x12/j | Frog Pump lesté 3x25 + Monster Walk.",
     "Ajoutez +2,5 kg dès que le haut de fourchette est atteint en contraction maximale.", "specialisation fessiers 3 jours hip thrust glutes programme"),
    ("Spécialisation Épaules 3D (Deltoïdes Moyen & Postérieur)", "Spécialisation", "Intermédiaire", "3-4x/sem", "6 semaines",
     "Haute fréquence sur les faisceaux latéral et postérieur qui récupèrent en 24-36h.",
     "4x/SEM EN DÉBUT OU FIN DE SÉANCE : Alternez Élévations latérales poulie basse 4x12-15, Élévations latérales haltères assis 4x15-20, Oiseau poulie haute croisée 4x15, Reverse Pec Deck 4x15 + 1 séance Développé militaire 4x6-8.",
     "Zéro triche de trapèze supérieur ; visez la brûlure locale stricte.", "specialisation epaules deltoides 3d elevations laterales"),
    ("Spécialisation Bras (+2 cm en 8 semaines — Arm Farm)", "Spécialisation", "Intermédiaire / Avancé", "3x/sem", "8 semaines",
     "Fréquence 3x/semaine sur biceps et triceps en variant les angles d'épaule (étiré, neutre, raccourci).",
     "J1 (Lourd) : Close-Grip Bench 4x6-8 | Curl barre EZ 4x6-8 | Overhead Triceps corde 3x10 | Curl marteau 3x10.\nJ2 (Étirement) : Bayesian Curl 4x12 | Skull Crusher banc décliné 4x10 | Curl incliné 3x12.\nJ3 (Congestion/Métabolique) : Superset Curl pupitre 3x12 + Pushdown double corde 3x12 | Curl concentré 3x15 + Kickback poulie 3x15.",
     "Réduisez légèrement les développés/tirages à 8 séries/sem pour éviter toute tendinite des coudes.", "specialisation bras biceps triceps 8 semaines programme"),
    ("Spécialisation Mollets Récalcitrants (4x/semaine)", "Spécialisation", "Tous niveaux", "4x/sem", "8 semaines",
     "Force la croissance des mollets par pause stricte 2s en étirement, charge lourde et fréquence élevée.",
     "J1 & J3 : Mollets debout (ou presse) 4x8-10 LOURD tempo 3-2-1-1.\nJ2 & J4 : Mollets assis (soléaire) 4x15-20 tempo 3-2-1-1 + Tibialis Raise 3x20.\nPlacez-les TOUJOURS en 1er exercice de séance !",
     "La pause de 2s en bas est non négociable pour couper le rebond du tendon d'Achille.", "specialisation mollets 4 fois semaine pause basse"),
    ("Spécialisation Gainage & Abdos Athlétiques (Anti-Extension/Rotation)", "Spécialisation", "Tous niveaux", "3x/sem (15 min)", "8 semaines",
     "Construit une sangle abdominale épaisse, forte et protectrice du dos sur les 4 fonctions du tronc.",
     "• Flexion : Cable Crunch 3x12-15 + Hanging Leg Raise 3x10.\n• Anti-extension : Ab Wheel Rollout 3x10 + Dead Bug.\n• Anti-rotation : Pallof Press 3x12/côté.\n• Anti-inclinaison : Suitcase Carry 3x30 m/côté + Planche latérale 3x40s.",
     "Surchargez progressivement comme n'importe quel autre muscle.", "specialisation abdos gainage tronc cable crunch ab wheel"),
    ("Spécialisation Avant-Bras & Force de Préhension (Grip)", "Spécialisation", "Tous niveaux", "3x/sem (15 min)", "8 semaines",
     "Développe écrasement, maintien (support), pincement et volume antibrachial.",
     "J1 : Farmer's Walk lourd 4x30 m | Wrist Curl 3x15 | Reverse Wrist Curl 3x15.\nJ2 : Dead Hang serviette 4x25s | Pinch Plate Hold 4x25s | Curl inversé EZ 3x12.\nJ3 : Fat Gripz Curl marteau 3x12 | Wrist Roller 3x2 | Extenseurs doigts élastique 3x25.",
     "Équilibrez toujours fléchisseurs et extenseurs pour prévenir tennis/golfer elbow.", "specialisation grip avant bras poignets force prehension"),
    # Méthodes d'intensification & structures de séries (14)
    ("Méthode Rest-Pause (Doggcrapp / Myo-Reps)", "Intensification", "Intermédiaire / Avancé", "Sur 1-2 exercices/séance", "Ponctuel",
     "Multiplie le nombre de répétitions hautement stimulantes (recrutement total des fibres) en un minimum de temps.",
     "PROTOCOLE : Effectuez une série d'activation proche de l'échec (10-15 reps à RIR 0-1), reposez-vous 15-20 secondes (3-5 respirations profondes), repartez pour 3-5 reps, pause 15-20s, puis encore 3-5 reps.\nIDÉAL SUR : Machines, poulies, isolations (Leg Extension, Lat Pulldown, Élévations latérales, Biceps/Triceps). À ÉVITER sur Squat/Deadlift lourds.",
     "Équivaut en stimulus hypertrophique à 3 séries classiques en 2 fois moins de temps.", "rest pause myo reps intensification hypertrophie temps"),
    ("Méthode Drop-Set (Séries dégressives)", "Intensification", "Intermédiaire", "Dernière série uniquement", "Ponctuel",
     "Prolonge la série au-delà de l'échec mécanique en baissant la charge de 20-30% sans repos.",
     "PROTOCOLE : Série à l'échec technique (10 reps) -> baissez immédiatement la charge de 25% -> repartez jusqu'à RIR 0-1 (6-8 reps) -> optionnel 2e décharge -25%.\nIDÉAL SUR : Machines à pile de poids (goupille rapide), haltères (Latérales, Curls).",
     "Études Fink/Enes : gain de temps majeur mais fatigue locale élevée — réservez à la dernière série des isolations.", "drop set degressive intensification hypertrophie"),
    ("Supersets Antagonistes (Agoniste / Antagoniste)", "Intensification", "Tous niveaux", "Toute séance", "Continu",
     "Enchaîner deux muscles opposés réduit la durée de séance de 35% sans aucune perte de force (voire légère potentialisation !).",
     "PAIRES IDÉALES :\n• Développé couché <-> Rowing barre/T-Bar (repos 60-90s entre chaque).\n• Développé militaire <-> Tractions.\n• Curl biceps <-> Extension triceps.\n• Leg Extension <-> Leg Curl.\nPendant qu'un muscle travaille, son antagoniste récupère activement.",
     "Meilleur hack de productivité prouvé scientifiquement en musculation.", "supersets antagonistes gain de temps force hypertrophie"),
    ("Cluster Sets (Séries en grappes — Force & Puissance)", "Intensification", "Avancé", "Mouvements principaux", "4-6 semaines",
     "Micro-pauses de 15-25s à l'intérieur d'une série permettant d'utiliser des charges plus lourdes avec une vitesse de barre élevée.",
     "EXEMPLE : Au lieu de 3x5 @ 82%, faites 3x(2+2+2 avec 20s intra-repos) @ 87% ! Volume total supérieur à charge plus lourde et technique impeccable.\nIDÉAL SUR : Squat, Bench, Deadlift, Tractions lestées, Épaulés.",
     "Maximise le recrutement nerveux sans accumulation excessive d'acidose.", "cluster sets force puissance vitesse barre"),
    ("Partiels en Position Étirée (Long-Length Partials)", "Intensification", "Intermédiaire / Avancé", "Isolations & Machines", "Méso-cycle 6 sem",
     "Méta-analyses récentes (Wolf et al. 2023-2024) : prolonger une série en demi-amplitude étirée après l'échec complet booste l'hypertrophie.",
     "PROTOCOLE : Effectuez vos répétitions complètes jusqu'à ne plus pouvoir finir le verrouillage, puis enchaînez 4 à 6 demi-répétitions fluides dans la moitié ÉTIRÉE du mouvement (ex: bas des élévations latérales, étirement bas Leg Curl assis, étirement Pec Deck, étirement mollets).",
     "Uniquement contrôlé et fluide — jamais de rebond tendineux brutal !", "long length partials partiels etires hypertrophie science"),
    ("Contraste de Charge (Méthode Bulgare / PAP — Potentialisation)", "Puissance", "Avancé", "2x/sem", "6 semaines",
     "Associe un mouvement lourd (>85%) puis un mouvement explosif biomécaniquement identique pour exploiter la potentialisation post-activation (PAP).",
     "PAIRES CLASSIQUES :\n• Squat lourd 3 reps @ 85% -> repos 2-3 min -> Box Jump / CMJ 4 reps explosives.\n• Développé couché 3 reps @ 85% -> repos 2 min -> Pompes claquées / Med Ball Chest Pass 5 reps.\n• Trap Bar Deadlift 3 reps @ 85% -> repos 2 min -> Broad Jump 4 reps.",
     "Augmente immédiatement la hauteur de saut et la puissance de 3 à 6%.", "methode bulgare contraste pap puissance explosivite"),
    ("Méthode EMOM (Every Minute On the Minute)", "Conditioning & Force", "Tous niveaux", "10-20 min", "Modulaire",
     "Déclenchez un bloc de travail précis au top de chaque minute ; le temps restant sert de récupération.",
     "EXEMPLES :\n• EMOM Technique Force (10 min) : 2 reps Squat ou Clean @ 75% chaque minute (volume explosif propre).\n• EMOM Conditioning (16 min alterné) : Min impaire 12 Cal Rameur / Min paire 12 KB Swings + 6 Burpees.\n• EMOM Calisthénie (12 min) : 5 Tractions + 10 Pompes + 15 Squats.",
     "Outil génial pour contrôler la densité et la discipline des temps de repos.", "emom conditioning force densite minutage"),
    ("Méthode AMRAP (As Many Rounds As Possible)", "Conditioning", "Intermédiaire", "10-20 min", "1-2x/sem",
     "Accumuler un maximum de tours propres d'un circuit dans un temps imparti — excellent test de progrès métabolique.",
     "EXEMPLE AMRAP 15 MIN : 10 Kettlebell Swings + 10 Pompes + 10 Goblet Squats + 200 m course (ou 15 cal rameur).\nNoter votre score exact (ex: 6 tours + 12 reps) et retester 6 semaines plus tard.",
     "Pacing intelligent : partez à 80% sur les 5 premières minutes pour accélérer sur la fin.", "amrap circuit conditioning cross training"),
    ("Méthode Tabata Authentique vs HIIT 30/30 vs Sprint SIT", "Cardio & HIIT", "Intermédiaire / Avancé", "1-2x/sem", "6-8 semaines",
     "Comparatif précis des 3 grandes familles d'intervalles à haute intensité.",
     "• TABATA (4 min : 8x 20s ALL-OUT / 10s repos) : Effort supramaximal (vélo/Air Bike), tolérance anaérobie.\n• HIIT COURT 30/30 (2x10 min : 30s 100% VMA / 30s actif) : Temps cumulé maximal proche de VO2max.\n• SIT (Sprint Interval Training : 4-6x 20-30s sprint maximal / 3-4 min repos complet) : Biogenèse mitochondriale rapide en 15 min total.",
     "Jamais plus de 2 séances HIIT/semaine pour ne pas épuiser le système nerveux autonome.", "hiit tabata sit 30/30 vo2max intervalles cardio"),
    ("Méthode Tempo / TUT (Temps Sous Tension Contrôlé)", "Hypertrophie", "Tous niveaux", "4-6 semaines", "Bloc technique",
     "Codage à 4 chiffres (Excentrique - Pause Basse - Concentrique - Pause Haute, ex: 3-1-1-0) pour maîtriser la qualité d'exécution.",
     "• 3-1-1-0 (Hypertrophie standard) : 3s descente, 1s arrêt étiré, 1s montée, 0s haut.\n• 4-2-1-0 (Correction technique / Tendons) : élimine tout rebond, renforce le collagène tendineux.\n• 2-0-X-0 (Force explosive) : X = intention d'accélération maximale.",
     "Un squat à 80 kg en 4-2-1-0 stimule souvent plus les quadriceps qu'un squat bâclé à 110 kg !", "tempo tut temps sous tension excentrique controle"),
    ("Double Progression vs Progression Dynamique par Série", "Périodisation", "Tous niveaux", "Toute année", "Continu",
     "Les deux algorithmes de surcharge progressive les plus fiables pour progresser mois après mois.",
     "• DOUBLE PROGRESSION (classique) : Fourchette 3x8-12 avec 60 kg. Quand vous validez 12, 12, 12 -> passez à 62,5 kg et repartez vers 8-9 reps.\n• PROGRESSION DYNAMIQUE (moderne) : Chaque série est gérée indépendamment ! Dès que la SÉRIE 1 atteint 12 reps -> montez la série 1 à 62,5 kg dès la séance suivante, même si la série 3 n'est qu'à 9 reps.",
     "La progression dynamique évite de 'freiner' les premières séries fraîches.", "surcharge progressive double progression dynamique algorithme"),
    ("Périodisation en Blocs (Accumulation -> Transmutation -> Réalisation)", "Périodisation", "Avancé", "4-5 j/sem", "12 semaines",
     "Modèle d'Issurin permettant de développer séquentiellement hypertrophie, force et pic de puissance sans dispersion.",
     "• BLOC 1 ACCUMULATION (4 sem) : Volume élevé (14-18 séries), 8-12 reps, RIR 2 -> construit la masse et la capacité de travail.\n• BLOC 2 TRANSMUTATION (4 sem) : Volume modéré (10-12 séries), 4-6 reps lourdes -> convertit la masse en force spécifique.\n• BLOC 3 RÉALISATION (3 sem) : Volume bas (6-8 séries), 1-3 reps @ 88-95% + vitesse -> exprime les records.\n• S12 : Deload.",
     "Idéal pour planifier un objectif précis à 3 mois (compétition, test max, course).", "periodisation blocs accumulation transmutation realisation"),
    ("Auto-Régulation RPE / RIR (Adapter l'entraînement à sa forme du jour)", "Périodisation", "Intermédiaire / Avancé", "Quotidien", "Continu",
     "Ajuster les charges en temps réel selon votre sommeil, stress et fraîcheur plutôt que de subir un pourcentage rigide.",
     "ÉCHELLE RIR (Répétitions en Réserve) / RPE :\n• RPE 10 = RIR 0 (échec musculaire).\n• RPE 9 = RIR 1 (1 rep encore possible).\n• RPE 8 = RIR 2 (zone optimale force & hypertrophie polyarticulaire).\n• RPE 7 = RIR 3 (vitesse, technique, début de cycle).\nAPPLICATION : Si le programme demande 4x6 @ RPE 8, choisissez le poids du JOUR qui vous laisse exactement 2 reps en réserve.",
     "Réduit drastiquement les blessures les jours de fatigue et permet de battre des records les grands jours.", "rpe rir autoregulation charge fatigue forme"),
    ("VBT — Entraînement Basé sur la Vitesse (Velocity-Based Training)", "Force & Puissance", "Avancé", "Force / Athlètes", "Bloc 8 sem",
     "Mesurer la vitesse moyenne concentrique (m/s) pour doser charge et fatigue avec une précision chirurgicale.",
     "ZONES DE VITESSE :\n• > 1,0 m/s : Puissance/Vitesse (sauts chargés, balistique).\n• 0,75 - 1,0 m/s : Force-Vitesse.\n• 0,50 - 0,75 m/s : Force générale / Hypertrophie.\n• < 0,40 m/s : Force maximale (>85-90% 1RM).\nPERTE DE VITESSE (Velocity Loss) : Stopper la série à -15/-20% de perte de vitesse maximise la force/explosivité avec 2x moins de fatigue ; -30/-40% maximise l'hypertrophie.",
     "Même sans capteur, l'intention d'accélération maximale sur chaque rep décuple le recrutement nerveux.", "vbt vitesse barre force puissance velocity loss"),

    # Programmes spécifiques additionnels : Reprise, Voyage, Express, Cardio croisé, Posture, Mobilité, Femme, Étudiant, etc. (50+ pour franchir 115+)
    ("Programme Reprise Après Arrêt (1 à 6 mois d'arrêt — 4 semaines)", "Reprise", "Tous niveaux", "3 j/sem", "4 semaines",
     "Exploiter la mémoire musculaire (myonucléi) sans subir de courbatures paralysantes ni tendinites de reprise.",
     "• S1 : 2 séries/exercice à 60% de vos anciennes charges (RIR 4-5) — Full Body 3x.\n• S2 : 3 séries à 70% (RIR 3).\n• S3 : 3 séries à 80% (RIR 2).\n• S4 : Retour au volume normal (RIR 1-2). En 4 à 6 semaines, vous retrouvez 90-100% de votre niveau antérieur !",
     "L'erreur fatale de reprise est de vouloir soulever dès le Jour 1 ce qu'on soulevait il y a 3 mois.", "reprise sport arret memoire musculaire courbatures programme"),
    ("Programme Voyage / Chambre d'Hôtel (20 min — 0 matériel)", "Maison / Sans matériel", "Tous niveaux", "Quotidien / 4x sem", "1-3 semaines",
     "Maintenir 100% de votre masse musculaire et énergie en déplacement professionnel ou vacances.",
     "CIRCUIT 4 TOURS (repos 60s entre tours) :\n1. Pompes tempo 3-1-1 : 12-20 reps.\n2. Squat Bulgare pied sur lit/chaise : 12 reps/jambe.\n3. Serviette coincée dans porte (Rowing isométrique/dynamique) ou Superman Pull : 15 reps.\n4. Pont fessier unilatéral : 15/jambe.\n5. Planche RKC 40s + Hollow Body 30s.\nOption cardio : 10 min escaliers de l'hôtel ou footing 30 min.",
     "Rappel scientifique : seulement 1/3 de votre volume habituel suffit à MAINTENIR 100% de votre muscle pendant plusieurs semaines !", "voyage hotel vacances sans materiel entretien programme"),
    ("Programme Étudiant / Budget Serré (3 séances + Nutrition économique)", "Hypertrophie", "Débutant / Intermédiaire", "3 j/sem", "12 semaines",
     "Progresser au maximum avec 3 séances efficaces et un panier protéines économique (< 45 €/semaine).",
     "ENTRAÎNEMENT : Full Body 3 jours (Squat, Bench, Rowing, RDL, OHP, Tractions, isolations rapides).\nPANIER ÉCO HAUTE QUALITÉ : Œufs entiers, fromage blanc 0%, thon/maquereau/sardines en boîte, cuisses/filets de poulet promo, lentilles/pois chiches secs, flocons d'avoine, riz basmati sac 5 kg, pommes de terre, bananes, légumes surgelés nature.",
     "La régularité sur 3 séances propres bat toujours un programme complexe tenu 2 semaines.", "etudiant budget economique 3 jours programme"),
    ("Programme Santé Posturale & Dos Blindé (Télétravail / Bureau)", "Posture & Santé", "Tous niveaux", "3x25 min/sem", "8 semaines",
     "Inverse la cyphose thoracique, les épaules enroulées et l'inhibition fessière causées par 8h assis par jour.",
     "1. MOBILITÉ (6 min) : Couch Stretch 45s/côté + Open Book 10/côté + Doorway Pec Stretch 45s.\n2. ACTIVATION & FORCE POSTÉRIEURE : Face Pull 3x15 | Rowing appui poitrine 3x12 | Y-T-W 2x10 | Hip Thrust / Glute Bridge 3x15 | RDL haltères 3x10 | McGill Big 3 (Curl-up, Side Plank, Bird Dog).",
     "Ajoutez un rappel de 2 minutes debout toutes les 60 minutes de travail assis.", "posture bureau teletravail mal de dos cyphose programme"),
    ("Programme 100% Élastiques (Résistance Progressive Maison)", "Maison / Élastiques", "Tous niveaux", "4 j/sem", "10 semaines",
     "Remplace efficacement les poulies et machines grâce à un jeu de 3 bandes de résistance et un ancrage de porte.",
     "HAUT (2x/sem) : Pompes élastique 4x12 | Tirage vertical élastique à genoux 4x15 | Rowing assis élastique 4x15 | Développé épaules élastique 3x15 | Face Pull 3x20 | Curl + Pushdown élastique 3x15.\nBAS (2x/sem) : Squat élastique 4x15 | Good Morning / Pull-Through élastique 4x15 | Fentes bulgares élastique 3x12/j | Leg Curl allongé élastique 3x15/j | Pallof Press 3x12/côté.",
     "Marquez 1 seconde de contraction au pic de tension (fin de course) où la résistance de la bande est maximale.", "elastiques bandes maison programme complet"),
    ("Programme Femme : Force, Tonification & Galbe (4 jours)", "Hypertrophie", "Tous niveaux", "4 j/sem", "12 semaines",
     "Détruit le mythe des poids roses de 2 kg : surcharge progressive intelligente pour force, silhouette athlétique et densité osseuse.",
     "J1 BAS (Fessiers/Ischios) : Hip Thrust barre 4x8-10 | RDL barre 4x8-10 | Fentes bulgares 3x10/j | Abduction machine 3x15 | Planche.\nJ2 HAUT (Dos/Épaules/Pecs) : Tractions assistées/Lat Pulldown 4x8-10 | Développé incliné DB 3x10 | Rowing assis 3x10 | Latérales 4x15 | Face Pull 3x15.\nJ3 BAS (Quads/Fessiers) : Squat / Hack Squat 4x8-10 | Step-Up contrôlé 3x10/j | Leg Curl assis 3x12 | Kickback poulie 3x15/j | Mollets.\nJ4 HAUT + GAINAGE : Développé militaire DB 3x10 | Rowing haltère unilatéral 3x10 | Pompes/Dips assistés 3x10 | Biceps/Triceps 3x12 | Dead Bug & Side Plank.",
     "Les femmes récupèrent souvent légèrement plus vite entre les séries (60-90s) et tolèrent très bien un volume bas du corps élevé.", "femme musculation force fessiers tonification programme"),
    ("Programme Grossesse Active & Post-Partum (Principes Sécurité)", "Santé & Prévention", "Adapté (accord médical)", "3x/sem", "Évolutif",
     "Maintenir force, mobilité, plancher pelvien et énergie pendant la grossesse et reprendre sereinement après accouchement.",
     "• GROSSESSE (avec avis médical) : Privilégiez exercices debout/assis/4 pattes (évitez décubitus dorsal prolongé après T1), respiration expiratoire sur l'effort (jamais de blocage Valsalva fort sur le périnée !), RPE max 7, Squat goblet, Rowing, Fentes, Bird Dog, Planche inclinée.\n"
     "• POST-PARTUM : Rééducation périnéale et abdominale (diastasis) prioritaire avant toute course, saut ou crunch ! Reprise progressive : marche -> gainage doux (Dead Bug, respiration transverse) -> renfo bas/haut -> course progressive après validation kiné/sage-femme (souvent >= 12 semaines).",
     "Zéro douleur pelvienne, zéro fuite urinaire, zéro 'cône' abdominal : ce sont vos feux verts obligatoires.", "grossesse post partum perinee diastasis reprise sport femme"),
    ("Plan Cyclisme / Home Trainer — Augmenter sa FTP (8 semaines)", "Endurance / Vélo", "Intermédiaire", "4 séances/sem", "8 semaines",
     "Élever le Seuil Fonctionnel de Puissance (FTP) par polarisation Zone 2 + Sweet Spot + VO2max.",
     "• MARDI (Sweet Spot / Seuil) : 3x15 min à 88-93% FTP (récup 5 min) progressant vers 2x25 min.\n• JEUDI (VO2max) : 5x4 min à 110-115% FTP (récup 4 min).\n• SAMEDI (Force/Vélocité + Muscu) : 6x4 min côte à 55-60 rpm assis + 35 min muscu jambes lourde.\n• DIMANCHE (Sortie longue Zone 2) : 2h30 à 3h30 à 60-72% FTP (60 g glucides/h).",
     "Test FTP (20 min all-out x 0,95 ou Ramp Test) en S1 et S8.", "cyclisme velo home trainer ftp sweet spot zone 2 programme"),
    ("Plan Rameur Concept2 — Progresser sur 2000 m & 5000 m (8 semaines)", "Endurance / Rameur", "Intermédiaire", "4 séances/sem", "8 semaines",
     "Inspiré du Pete Plan : équilibre volume aérobie Steady State et intervalles vitesse/seuil.",
     "• SÉANCE 1 (Vitesse) : 8x500 m (récup 3 min) à allure 2K cible.\n• SÉANCE 2 (Steady State Z2) : 40-50 min à 18-20 spm (allure 2K + 18-22s).\n• SÉANCE 3 (Seuil) : 4x2000 m (récup 4 min) à allure 5K (2K + 7-8s).\n• SÉANCE 4 (Endurance) : 10 000 m Z2 régulier.",
     "Travaillez la technique jambes-buste-bras à faible cadence (18-20 spm) pour bâtir une puissance par coup énorme.", "rameur concept2 2000m pete plan programme"),
    ("Plan Triathlon Sprint & Olympique (12 semaines)", "Triathlon", "Intermédiaire", "6 séances/sem (2/discipline)", "12 semaines",
     "Organisation équilibrée Natation, Vélo, Course à pied + enchaînements Brick.",
     "• NATATION (2x/sem) : 1 technique/éducatifs + 1 seuil (10x100 m).\n• VÉLO (2x/sem) : 1 Sweet Spot 60 min + 1 sortie longue 2h-2h30.\n• COURSE (2x/sem) : 1 seuil/VMA + 1 enchaînement BRICK (30-45 min vélo suivi immédiatement de 20-30 min course allure cible pour habituer les jambes !).\n• + 1x30 min gainage/force prévention.",
     "Les transitions vélo -> course (Brick) font gagner plusieurs minutes et évitent les jambes en coton.", "triathlon sprint olympique brick programme 12 semaines"),
    ("Plan 1er Semi-Marathon pour Débutant (Finir avec Plaisir — 10 sem)", "Running", "Débutant", "3 sorties/sem", "10 semaines",
     "Passer de 5-8 km confortables à 21,1 km sans blessure ni pression chronométrique.",
     "• MARDI : 35-45 min footing très lent (Zone 2, pouvoir parler en phrases complètes).\n• JEUDI : 40 min incluant 6-10 accélérations douces de 1 min (récup 2 min marche/trot).\n• DIMANCHE (Sortie longue) : Augmentation progressive de 8 km (S1) -> 10 -> 12 -> 10 (récup) -> 14 -> 16 -> 13 (récup) -> 18 km (S8) -> 12 km -> 21,1 km !",
     "Marchez 1 minute aux ravitaillements dès le début si besoin (méthode Galloway) — aucune honte, chrono souvent meilleur !", "debutant semi marathon 3 sorties finir plan"),
    ("Plan Couch to 5K — Courir 30 minutes sans s'arrêter (8 semaines)", "Running", "Grand Débutant", "3 sorties/sem", "8 semaines",
     "Alternance course/marche ultra-progressive protégeant tibias, genoux et tendons d'Achille.",
     "• S1 : 8x(1 min trot lent / 2 min marche).\n• S2 : 6x(2 min trot / 2 min marche).\n• S3 : 5x(3 min trot / 2 min marche).\n• S4 : 4x(5 min trot / 2 min marche).\n• S5 : 3x(8 min trot / 2 min marche).\n• S6 : 2x(12 min trot / 2 min marche).\n• S7 : 20 min continu -> 25 min continu.\n• S8 : 30 minutes continues (4 à 5 km) !",
     "Règle n°1 : courez BEAUCOUP plus lentement que vous ne le pensez (vous devez pouvoir parler).", "debutant courir 30 minutes 5km couch to 5k"),
    ("Préparation Concours Police / Pompiers / Armée (Luc Léger + Tractions + Parcours)", "Prépa physique", "Tous niveaux", "4-5 séances/sem", "10 semaines",
     "Optimiser simultanément le test navette Luc Léger (palier 10-12+), les tractions/pompes et l'endurance musculaire.",
     "• LUNDI (VMA & Luc Léger) : 2x8x(30s/30s) + entraînement spécifique demi-tours plots 20 m (optimiser freinage/relance !).\n• MARDI (Force Haut) : Tractions strictes (volume Fighter) + Dips + Pompes + Gainage.\n• JEUDI (Endurance Fondamentale) : 45 min Z2 + renforcement jambes/chevilles.\n• SAMEDI (Simulation) : Test Luc Léger 1x toutes les 2 semaines + circuit gainage/burpees.",
     "Au Luc Léger, économisez 10% d'énergie en calant votre allure sur les bips dès les premiers paliers et en alternant pied d'appui au demi-tour.", "concours pompier police armee luc leger tractions programme"),
    ("Préparation Physique Golf (Puissance Swing & Protection Lombaire)", "Prépa physique", "Tous niveaux", "2-3 séances/sem", "10 semaines",
     "Augmenter la vitesse de tête de club tout en protégeant le bas du dos et les coudes.",
     "1. Mobilité quotidienne (8 min) : Open Book thoracique, 90/90 hanches, Dislocations épaules.\n2. Puissance rotative : Med Ball Rotational Throw 4x5/côté | Woodchopper poulie explosif 3x8/côté.\n3. Force & Anti-rotation : Trap Bar Deadlift 3x6 | Fentes bulgares 3x8/j | Pallof Press 3x12/côté | Landmine Press 3x8/côté.",
     "La vitesse de swing provient de la séparation hanches/thorax (facteur X) et de la poussée du sol — jamais d'une torsion lombaire forcée !", "golf preparation physique vitesse swing lombaires mobilite"),
    ("Préparation Physique Surf / Kitesurf / Wakeboard", "Prépa physique", "Intermédiaire", "3 séances/sem", "8 semaines",
     "Endurance de rame (dorsaux/épaules), pop-up explosif, équilibre dynamique et apnée calme.",
     "1. Rame & Épaules : Tractions 4x8 | Pullover poulie 3x15 | Superman/Cobra hold 3x45s | Face Pull 3x15.\n2. Pop-Up : Pompes explosives burpee-to-stance 4x8.\n3. Jambes & Équilibre : Squat Cossack 3x8/côté | RDL unilatéral 3x10/j | Planche sur Swiss Ball + Rotations.",
     "L'endurance en extension thoracique (Cobra) évite les crampes lombaires en sessions de 2h.", "surf kitesurf preparation physique rame pop up"),
    ("Préparation Physique Rugby (Force Collision, Sprint & Prévention)", "Prépa physique", "Intermédiaire / Avancé", "3 Muscu + Terrain", "Bloc 10 sem",
     "Force maximale absolue, puissance de poussée, sprints répétés et blindage épaules/cervicales.",
     "• FORCE BAS : Squat / Trap Bar 4x4 | Sled Push lourd 6x15 m | Nordic Curl 3x6 | Copenhagen 3x25s.\n• FORCE HAUT : Bench Press 4x5 | Rowing Pendlay 4x6 | Tractions lestées 4x5 | Landmine Press 3x8 | Cou isométrique 4 directions + Coiffe.\n• CONDITIONING : Sprints répétés Bronco Test (navettes 20/40/60 m) + Zone 2 récup.",
     "Priorité absolue au renforcement du cou et de la coiffe pour encaisser les plaquages en sécurité.", "rugby preparation physique force sprint bronco cou"),
    ("Préparation Physique Handball / Sports de Lancer (Épaule & Saut)", "Prépa physique", "Intermédiaire", "2-3 séances/sem", "10 semaines",
     "Explosivité de tir et d'appui tout en protégeant l'épaule du lanceur (équilibre coiffe externe/interne).",
     "1. Épaule du lanceur (obligatoire) : Rotation externe 90° (poulie/élastique) 3x15 | Excentrique coiffe postérieure | Y-T-W | Serratus Wall Slides.\n2. Puissance : Med Ball Overhead & Rotational Throw 4x6 | Single-Leg Hop & Stick 3x6/j.\n3. Force : Front Squat 4x5 | RDL unilatéral 3x8 | Landmine Press 3x8 | Tractions 3x8.",
     "Visez un ratio force rotateurs externes / rotateurs internes >= 0,66 pour protéger l'épaule de lancer.", "handball lancer epaule lanceur saut preparation physique"),
    ("Programme Mobilité Totale 4 semaines (Raide -> Fluide)", "Mobilité", "Tous niveaux", "5x15 min/sem", "4 semaines",
     "Restaure en 30 jours les 4 zones clés bloquées chez 90% des adultes : chevilles, hanches, thoracique et épaules.",
     "• LUN/JEU (Bas du corps 15 min) : Knee-to-Wall chevilles 3x15 | 90/90 hanches transitions + liftoffs 3x8 | Couch Stretch 2x60s | Deep Squat Hold 2x60s | Éléphant Walk 2x20.\n• MAR/VEN (Haut du corps 15 min) : Thoracic Foam Roll 2x10 | Open Book 2x10/côté | Butcher's Block 2x45s | Wall Slides 3x10 | Shoulder CARs 2x5 | Dead Hang 2x45s.\n• SAM (Flux global) : World's Greatest Stretch + Cossack Shifts + Respiration 90/90.",
     "Mesurez vos progrès à J1 et J30 : test Knee-to-Wall (cm), toucher orteils, profondeur squat talons collés.", "mobilite programme 4 semaines souplesse hanches chevilles epaules"),
    ("Protocole 5x5 + Hypertrophie (PHUL — Power Hypertrophy Upper Lower)", "Force & Hypertrophie", "Intermédiaire", "4 j/sem", "12 semaines",
     "2 jours Power (3-5 reps lourdes) + 2 jours Hypertrophie (8-12 reps) — classique mondial ultra-efficace.",
     "• J1 UPPER POWER : Bench 4x4-5 | Rowing barre 4x5 | OHP 3x6 | Tractions lestées 3x6 | Curl EZ 3x8 | Skull Crusher 3x8.\n• J2 LOWER POWER : Squat 4x4-5 | Deadlift 3x5 | Presse 3x8 | Leg Curl 3x8 | Mollets 4x8.\n• J4 UPPER HYPER : Incliné DB 4x10 | Tirage vertical 4x10 | Écartés poulie 3x15 | Rowing assis 3x12 | Latérales 4x15 | Bras 3x12.\n• J5 LOWER HYPER : Front Squat/Hack 4x10 | RDL DB 4x10 | Bulgares 3x12 | Leg Extension 3x15 | Leg Curl assis 3x15 | Mollets assis 4x15.",
     "Progressez en force sur J1/J2 et en volume/technique stricte sur J4/J5.", "phul 4 jours power hypertrophy upper lower programme"),
    ("Protocole PHAT (Layne Norton — 5 jours Power + Hypertrophie)", "Force & Hypertrophie", "Avancé", "5 j/sem", "10-12 semaines",
     "2 jours Power (Upper/Lower) + 3 jours Hypertrophie/Vitesse (Back-Shoulders / Lower / Chest-Arms).",
     "J1 Upper Power | J2 Lower Power | J3 Repos | J4 Dos & Épaules Hypertrophie (démarrage vitesse 6x3 @ 65%) | J5 Jambes Hypertrophie | J6 Pecs & Bras Hypertrophie.",
     "Volume total très élevé — réservé aux athlètes confirmés en surplus calorique et bon sommeil.", "phat layne norton 5 jours power hypertrophie"),
    ("Protocole Sheiko (Haute Fréquence Technique Powerlifting)", "Force", "Avancé", "4 j/sem", "12 semaines",
     "Méthode russe de Boris Sheiko : volume élevé sous-maximal (70-85% 1RM, quasi zéro échec) forgeant une maîtrise technique parfaite sur SBD.",
     "PRINCIPE : Bench 3-4x/sem, Squat 2-3x/sem, Deadlift 2x/sem en séries multiples de 2-5 reps propres (ex: 5x3 @ 80%), pauses, variantes (Deadlift jusqu'aux genoux + complet), très peu de fatigue maximale.",
     "Idéal pour exploser la technique et les totaux powerlifting sans se blesser.", "sheiko powerlifting force technique russe volume"),
    ("Programme Récupération Active & Gestion du Stress (Semaine Chargée)", "Récupération", "Tous niveaux", "Quotidien 20-30 min", "Semaine de pic pro/fatigue",
     "Garder la dynamique lors d'une semaine d'examens, déplacement intense ou manque de sommeil sans s'enfoncer dans le surmenage.",
     "• 2 séances Full Body express de 30 min (2 séries/exercice @ RPE 7, aucune série à l'échec).\n• 30 min marche rapide en extérieur lumière naturelle le matin.\n• 10 min respiration diaphragmatique / NSDR (Non-Sleep Deep Rest) l'après-midi.",
     "Mieux vaut 2 semaines à 60% que de forcer à 100% et tomber malade ou se blesser.", "stress fatigue recuperation active semaine chargee nsdr"),
    ("Programme Endurance Musculaire Circuit Training (30-45 min)", "Conditioning", "Débutant / Intermédiaire", "3 j/sem", "8 semaines",
     "Améliore simultanément la capacité cardiovasculaire et l'endurance locale sur tout le corps.",
     "5 TOURS (40s travail / 20s transition, 90s entre tours) :\n1. Goblet Squat | 2. Pompes | 3. Kettlebell Swing | 4. Rowing haltères ou anneaux | 5. Fentes alternées | 6. Planche dynamique.",
     "Augmentez à 45s/15s ou ajoutez 2 kg dès que les 5 tours passent sans chute technique.", "circuit training endurance musculaire cardio 30 min"),
    ("Programme Force Maximale 3 jours (Minimaliste Powerlifting)", "Force", "Intermédiaire / Avancé", "3 j/sem (75 min)", "12 semaines",
     "Atteindre un total Squat + Bench + Deadlift impressionnant en seulement 3 séances hebdomadaires.",
     "J1 : Squat Lourd (1x3@RPE8 + 4x5@78%) | Bench Volume (4x7@75%) | Rowing 4x8.\nJ2 : Deadlift Lourd (1x3@RPE8 + 3x4@78%) | Bench Pausé (4x4@80%) | Tractions 4x8 | Face Pull.\nJ3 : Bench Lourd (1x3@RPE8 + 4x4@80%) | Squat Technique/Pause (4x4@75%) | RDL 3x8 | Gainage.",
     "3 jours bien récupérés battent souvent 5 jours mal récupérés sur les records de force.", "force maximale 3 jours powerlifting sbd"),
    ("Programme Spécial Calisthénie Anneaux de Gymnastique (3 jours)", "Calisthénie", "Intermédiaire", "3 j/sem", "12 semaines",
     "Exploite l'instabilité multidirectionnelle des anneaux pour une hypertrophie exceptionnelle du haut du corps.",
     "1. Ring Support Hold RTO : 4x30s.\n2. Ring Pull-Ups : 4x6-10.\n3. Ring Dips : 4x6-10.\n4. Ring Rows pieds surélevés : 4x10-12.\n5. Ring Push-Ups (RTO en haut) : 4x10-12.\n6. Ring Fly + Ring Curl + Ring Triceps Extension : 3x10-12.\n7. Ring L-Sit / Ab Rollout aux anneaux : 3x10.",
     "Les anneaux sont le home gym le plus rentable de l'histoire (<40 €) pour le haut du corps.", "anneaux gymnastique calisthenie programme 3 jours"),
    ("Protocole Recomposition Post-Vacances / Remise à Niveau 6 semaines", "Recomposition", "Tous niveaux", "4 j/sem", "6 semaines",
     "Relancer métabolisme, posture, masse musculaire et cardio en 42 jours structurés.",
     "• 3x Full Body (50 min) + 1x Conditioning Zone 2/HIIT doux (40 min) + 9 000 pas/jour.\n• Nutrition : Maintenance ou -250 kcal, protéines 2,0 g/kg, 0 calories liquides, 80% aliments bruts, 7h30+ sommeil.",
     "Évaluez par photos, tour de taille et charges soulevées à S1 et S6.", "recomposition 6 semaines remise niveau programme"),
    ("Programme Spécial Poids de Corps + 1 Barre de Traction + 2 Haltères", "Maison / Hybride", "Tous niveaux", "4 j/sem", "12 semaines",
     "Combo équipement minimal ultime permettant de ne négliger aucun muscle.",
     "J1 HAUT : Tractions 4xMax-1 | Développé sol/banc DB 4x10 | Rowing DB 4x10 | Latérales DB 4x15 | Curl/Triceps.\nJ2 BAS : Bulgares DB 4x10/j | RDL DB 4x10 | Goblet Squat cale 4x12 | Mollets DB 4x15 | Hanging Leg Raise.\nJ3 HAUT : Dips/Pompes lestées 4x10 | Tractions supination 4xMax-1 | OHP DB 4x10 | Oiseau DB 4x15 | Bras.\nJ4 BAS : Sumo Squat DB 4x12 | Single-Leg RDL 3x10/j | Hip Thrust DB 4x12 | Fentes marchées 3x14 | Suitcase Carry.",
     "Couvre 100% des schémas moteurs fondamentaux pour moins de 200 € d'équipement.", "maison barre traction halteres programme 4 jours"),
    ("Protocole Préparation 100 Pompes & 20 Tractions (Endurance Calisthénie)", "Calisthénie", "Intermédiaire", "4 j/sem", "8 semaines",
     "Augmenter massivement son nombre maximal de répétitions d'affilée par Grease the Groove + densité.",
     "• GREASE THE GROOVE (Lun-Ven) : 4 à 6 mini-séries réparties dans la journée à 50-60% de votre max (toujours frais, zéro échec !).\n• 2 SÉANCES DENSITÉ : 10 min EMOM d'un nombre fixe propre + 1 série de renforcement lourd (pompes lestées / tractions lestées 4x5) pour élever votre plafond de force absolue.",
     "Plus votre 1RM lesté est haut, plus les répétitions au poids du corps deviennent faciles !", "100 pompes 20 tractions endurance grease the groove"),
    ("Plan Course 10 km Sub 40 minutes (Avancé — 10 semaines)", "Running", "Avancé", "5 sorties/sem (55-70 km)", "10 semaines",
     "Franchir la barre symbolique des 40 min au 10 km (4:00/km) grâce au seuil lactique élevé et à l'économie de foulée.",
     "• LUN : Footing récup Z2 45 min + Renforcement.\n• MAR : VMA / VO2max (ex: 12x400 m en 1:28 ou 6x1000 m en 3:50-3:55 récup 90s).\n• JEU : Seuil tempo 25-30 min à 4:10-4:15/km (ou 3x2000 m à 3:58/km).\n• VEN : Footing Z2 50 min + 8 lignes droites rapides.\n• DIM : Sortie longue 1h20-1h30 Z2 (avec 20 min finales à 4:15/km en S6-S8).",
     "Prérequis : déjà courir le 10 km sous 43 min ou le 5 km sous 19:30.", "10km sub 40 minutes plan running avance"),
    ("Plan Marathon Sub 3h30 / Sub 3h00 (Principes Clés & Volumes)", "Running", "Intermédiaire / Avancé", "5 sorties/sem", "16 semaines",
     "Structure spécifique pour viser 5:00/km (3h30) ou 4:15/km (3h00) sur 42,2 km.",
     "• SUB 3h30 : Pic 65-75 km/sem, sorties longues jusqu'à 2h15 avec 2x20 min à 4:58/km, 60-75 g glucides/h.\n• SUB 3h00 : Pic 85-100 km/sem, sorties longues jusqu'à 2h25 avec 15-18 km à 4:15/km, 2 séances seuil/AS42/sem, 80-90 g glucides/h, 1-2 séances renfo lourd/semaine.\n• RÈGLE D'OR : Partez en 'Even Split' ou léger 'Negative Split' — chaque seconde gagnée trop vite au 5e km se paie x10 au 35e km !",
     "Testez chaussures plaque carbone + chaussettes + gels sur au moins 3 sorties longues à AS42 avant le jour J.", "marathon sub 3h30 sub 3h00 allure pacing plan"),
    ("Programme Récupération Post-Marathon / Post-Ultra (3 semaines)", "Récupération", "Tous niveaux", "Progressif", "3 semaines",
     "Protocole de 'Reverse Taper' indispensable pour éviter blessures et baisse immunitaire après une grande épreuve.",
     "• SEMAINE 1 : Zéro course pendant 5-7 jours ! Marche douce, vélo mouliné 30 min, sommeil 8-9h, protéines 2 g/kg + glucides généreux.\n• SEMAINE 2 : 2-3 petits footings très lents 25-35 min Z1-Z2 + reprise mobilité et gainage.\n• SEMAINE 3 : Reprise 60-70% du volume Z2 + reprise musculation légère. Zéro fractionné intense avant S4 !",
     "Les microlésions musculaires et la fatigue nerveuse persistent 14 à 21 jours même si les courbatures disparaissent à J+4.", "recuperation post marathon ultra reverse taper reprise"),
    ("Programme Renforcement Spécifique Coureur (2x30 min/sem)", "Running / Renfo", "Tous niveaux", "2x30 min/sem", "Toute saison",
     "Améliore l'économie de course de +3 à 5% et réduit de moitié les blessures de surcharge (méta-analyse Lauersen 2018).",
     "SÉANCE A (Force lourde — 3-4x5-6 reps lourdes) : Squat ou Trap Bar Deadlift | RDL unilatéral | Mollets debout lourds (charge >= 1,5x PDC en cumulé) | Planche latérale.\nSÉANCE B (Unilatéral & Raideur tendineuse) : Fentes bulgares 3x8/j | Nordic Curl 3x5 | Mollets assis (soléaire lourd !) 4x10 | Pogo Jumps 3x15 | Hip Hike / Moyen fessier 3x15/côté.",
     "Placez idéalement le renfo le même jour (plus tard) que vos séances de qualité running pour garder des VRAIS jours faciles !", "renforcement coureur running economie course prevention blessure"),
    ("Programme Débutant Salle de Sport — 4 Premières Semaines (Machines -> Libre)", "Débutant", "Grand Débutant", "3 j/sem (50 min)", "4 semaines",
     "Transition douce et rassurante pour apprendre tous les mouvements sans stress ni courbatures excessives.",
     "3x/SEM (3x10-12 à RIR 3) : 1. Presse à cuisses (puis Goblet Squat en S3) | 2. Leg Curl assis | 3. Machine Chest Press (puis Haltères S3) | 4. Tirage vertical poulie | 5. Rowing assis machine | 6. Machine Shoulder Press | 7. Planche / Dead Bug.",
     "Objectif S1-S4 : créer l'habitude, apprendre la respiration et la posture neutre, puis basculer vers le Full Body 3j ou Upper/Lower 4j.", "debutant salle musculation machines premier programme"),
    ("Programme 20 Minutes Chrono — Pause Déjeuner Active", "Express", "Tous niveaux", "4 j/sem (20 min)", "Continu",
     "Maximise la densité par supersets antagonistes pour les journées ultra-chargées.",
     "3 MIN ÉCHAUFFEMENT + 17 MIN DENSE :\n• J1 : Superset Développé incliné DB 4x10 + Rowing DB 4x10 (60s) | Superset Latérales 3x15 + Face Pull 3x15.\n• J2 : Superset Squat Goblet/Hack 4x10 + Leg Curl 4x10 | Superset Mollets 3x15 + Dead Bug 3x10.\n• J3 : Superset Dips/Pompes 4x12 + Tractions 4x8 | Superset Curl 3x12 + Pushdown 3x12.\n• J4 : Superset RDL DB 4x10 + Fentes bulgares 3x10/j | Planche RKC 3x45s.",
     "4x20 min ciblées et régulières transforment radicalement un physique sur 1 an.", "20 minutes express pause dejeuner supersets programme"),
    ("Programme Force Explosive & Sprint Athlète (Sports Collectifs)", "Puissance", "Avancé", "3 j/sem", "8 semaines",
     "Augmenter la vitesse sur 10-30 m et la puissance de démarrage.",
     "J1 (Accélération) : Wall Drills 3x8 | Sprints 15-20 m départ arrêté 6x1 (3 min repos complet !) | Broad Jump 4x4 | Trap Bar Deadlift 4x3 explosif.\nJ2 (Force & Excentrique) : Squat 4x4 | Nordic Curl 3x6 | Fentes bulgares 3x6/j | Copenhagen 3x25s.\nJ3 (Vitesse Max & Réactivité) : Flying Sprints 20 m lancé 5x1 (4 min repos) | Depth Jump 4x4 | Sled Sprint léger 10% PDC 4x20 m.",
     "La vitesse pure s'entraîne TOUJOURS frais à 100% avec repos longs — dès que la vitesse baisse, la séance sprint est terminée !", "sprint vitesse explosivite acceleration programme athlete"),
    ("Programme Natation Débutant -> 1500 m Crawl Fluide (8 semaines)", "Endurance / Natation", "Débutant / Intermédiaire", "2-3 séances/sem", "8 semaines",
     "Corriger l'équilibre horizontal, la respiration bilatérale et l'appui sous-marin pour nager 1500 m sans s'essouffler.",
     "• ÉDUCATIFS (chaque séance, 300 m) : Rattrapé (catch-up), Nage poings fermés (sentir l'avant-bras), Respiration 3 temps, Pull-buoy.\n• CORPS DE SÉANCE : S1-S3 12x50 m (repos 20s) -> S4-S6 10x100 m -> S7-S8 6x200 m + 1x400 m.\n• CLÉ : Expirez en continu sous l'eau par le nez/bouche pour n'avoir qu'à inspirer brièvement sur le côté !",
     "En natation, 80% de la vitesse vient de la réduction de la traînée (gainage + tête basse), pas de battre des jambes plus fort.", "natation crawl 1500m debutant respiration programme"),
    ("Programme Spécial Souplesse & Grand Écart Progressif (10 semaines)", "Mobilité", "Tous niveaux", "4x20 min/sem", "10 semaines",
     "Méthode active PNF (Contracter-Relâcher) + force en amplitude extrême (Loaded Stretching) pour des gains durables.",
     "• ISCHIOS & FRONT SPLIT : Éléphant Walk 2x20 | RDL léger amplitude max 3x10 | PNF Ischios sangle (10s contraction douce / 20s relâchement x3) | Front Split sur blocs 3x45s.\n• ADDUCTEURS & MIDDLE SPLIT : Cossack Squat 3x10 | Frog Stretch 2x60s | Horse Stance isométrique 3x45s | Middle Split sliders actif 3x45s.",
     "Le système nerveux ne déverrouille une amplitude que s'il s'y sent FORT : couplez toujours étirement + contraction active.", "souplesse grand ecart pnf flexibilite programme"),
    ("Programme Santé Cardiaque & Perte de Poids Débutant (Zéro Impact)", "Santé & Perte de poids", "Grand Débutant / Surpoids", "5 j/sem", "12 semaines",
     "Protéger genoux, hanches et chevilles tout en bâtissant muscle et dépense énergétique durable.",
     "• 3x/SEM FORCE DOUCE (40 min) : Squat sur chaise / Presse | Pont fessier | Pompes mur/banc | Rowing élastique/machine | Farmer's Walk | Bird Dog.\n• 5-7x/SEM CARDIO ZÉRO IMPACT : Marche progressive (5 000 -> 8 000 -> 10 000 pas), vélo d'appartement, elliptique ou natation (30-45 min Zone 2).\n• NUTRITION : Déficit modéré -350 kcal, protéines 1,6-1,8 g/kg poids cible, 500 g légumes/j.",
     "Privilégiez la constance douce sur 6 mois plutôt que des séances extrêmes abandonnées en 10 jours.", "perte de poids debutant surpoids zero impact sante programme"),
    ("Programme Spécial Force du Dos & Rééducation Post-Lombalgie (Sans Douleur)", "Santé & Dos", "Reprise", "4x20 min/sem", "8 semaines",
     "Inspiré du Pr Stuart McGill : bâtir un corset musculaire rigide et réintroduire progressivement la charnière de hanche.",
     "• QUOTIDIEN (10 min) : Marche rapide bras balancés (2x15 min) + McGill Big 3 (Curl-Up 3x8x8s, Side Plank 3x25s, Bird Dog 3x8x8s).\n• 3x/SEM RENFO PROGR : Pont fessier 3x15 | Pallof Press 3x12/côté | Suitcase Carry modéré 3x25 m | Charnière au bâton -> RDL léger haltères (S4+) | Belt Squat / Goblet Squat léger.",
     "Stoppez tout exercice déclenchant une douleur irradiante ou > 3/10 ; privilégiez l'endurance isométrique neutre.", "mal de dos lombalgie mcgill big 3 reeducation programme"),
    ("Programme Spécial Haut du Corps (Pendant Blessure Membre Inférieur)", "Adaptation Blessure", "Tous niveaux", "4 j/sem", "Durée blessure",
     "Rester actif, stimuler le système hormonal et même limiter l'atrophie de la jambe blessée grâce à l'effet croisé (Cross-Education) !",
     "• HAUT DU CORPS (3-4x/sem) : Exercices assis/allongés stables (Développé machine/haltères, Tractions, Rowing appui poitrine, Latérales assises, Biceps/Triceps, Abdos au sol).\n• EFFET CROSS-EDUCATION (Validé scientifiquement !) : Entraîner en force unilatérale la jambe SAINE (Leg Extension, Presse 1 jambe, Leg Curl 1 jambe) préserve jusqu'à 50% de la force de la jambe immobilisée via les adaptations neurales croisées !",
     "Maintenez vos calories à maintenance et protéines >= 2,2 g/kg.", "blessure jambe genou cheville cross education programme haut corps"),
    ("Programme Spécial Bas du Corps (Pendant Blessure Épaule/Poignet)", "Adaptation Blessure", "Tous niveaux", "3-4 j/sem", "Durée blessure",
     "Continuer à progresser puissamment sur les jambes, le cardio et le gainage tout en laissant l'épaule guérir.",
     "• JAMBES SANS MAINS (3x/sem) : Safety Squat Bar ou Belt Squat ou Hack Squat ou Presse à cuisses 4x8-12 | Leg Extension 4x12 | Leg Curl assis & allongé 4x12 | Hip Thrust machine 4x10 | Hyperextensions 3x15 | Mollets machine 4x15.\n• EFFET CROISÉ : Travail unilatéral doux du bras sain (si autorisé) + Vélo / Marche inclinée Zone 2.",
     "Transformez une contrainte de 6 semaines en occasion de bâtir des jambes impressionnantes !", "blessure epaule poignet programme jambes sans mains"),
    ("Programme Cardio-Force 30/30 pour Manque de Temps (3 jours)", "Hybride", "Intermédiaire", "3 j/sem (60 min)", "12 semaines",
     "30 min de force polyarticulaire suivies de 25 min de Zone 2 ou 15 min de HIIT sur la même heure.",
     "J1 : Squat 4x6 + Bench 4x6 + Rowing 4x8 (30 min) -> 25 min Vélo/Rameur Zone 2.\nJ2 : Trap Bar Deadlift 4x5 + OHP 4x8 + Tractions 4x8 (30 min) -> 25 min Marche inclinée 12%.\nJ3 : Bulgares 3x10 + Incliné DB 3x10 + Lat Pulldown 3x10 (30 min) -> 15 min Intervalles 4x2 min soutenus.",
     "Compromis redoutablement efficace pour les actifs voulant force, physique et cœur en béton en 3h/semaine.", "hybride 3 jours 60 min force cardio actifs"),
    ("Programme Cross-Training / Fitness Fonctionnel 5 jours", "Conditioning", "Intermédiaire / Avancé", "5 j/sem", "12 semaines",
     "Développe les 10 qualités athlétiques : force, haltérophilie, gymnastique, moteur aérobie et tolérance lactique.",
     "J1 Force Squat/Haltéro (Clean) + WOD court intense (8-12 min).\nJ2 Force Poussée/Tirage + WOD Gymnastique/Cardio (15 min).\nJ3 Endurance pure Zone 2 (45 min Ergo/Run) + Mobilité.\nJ4 Deadlift/Snatch technique + WOD Couple Force/Conditioning.\nJ5 Team WOD ou Chipper long (25-30 min) + Gainage.",
     "Scalez (adaptez) toujours charges et mouvements pour préserver la mécanique rachidienne sous fatigue.", "crossfit cross training wod fonctionnel programme 5 jours"),
    ("Programme Escalade + Musculation Hybride (Équilibré 4 jours)", "Hybride", "Intermédiaire", "2 Escalade + 2 Muscu", "Continu",
     "Développer un physique complet (y compris jambes et poussées) sans alourdir inutilement ni saturer les avant-bras.",
     "• J1 & J4 : Escalade / Bloc (focus technique + force doigts).\n• J2 (Force Jambes + Poussée) : Squat 3x5-6 | RDL 3x6 | Développé incliné DB 4x8 | Développé militaire 3x8 | Dips 3x10 | Antagonistes avant-bras.\n• J5 (Full Body Léger & Préhab) : Fentes bulgares 3x8 | Pompes anneaux 3x12 | Face Pull 3x15 | Rotations externes | Gainage Hollow/L-Sit.",
     "Force basse répétition (3-6 reps) sur les jambes améliore les jetés/appuis sans prise de masse superflue.", "escalade musculation hybride antagonistes programme"),
    ("Protocole Préparation 1000 m / 1500 m / Cooper 12 min", "Running", "Intermédiaire / Avancé", "4 séances/sem", "8 semaines",
     "Maximiser VO2max, vitesse spécifique demi-fond et tolérance lactique (tests concours & piste).",
     "• MAR (VMA courte) : 12x200 m à 105-110% VMA (récup 1 min).\n• JEU (Spécifique 1500 m) : 5x400 m ou 4x600 m à allure cible (récup 2-3 min).\n• SAM (Seuil) : 2x10 min à 85% VMA.\n• DIM : Footing Z2 45-50 min.",
     "Pour le test de Cooper (12 min max), partez exactement à 95% VMA pour éviter l'explosion lactique à la 6e minute.", "cooper 12 min 1500m 1000m piste vma plan"),
    ("Protocole Rattrapage Asymétrie Gauche / Droite (Unilatéral 6 sem)", "Correctif", "Tous niveaux", "3-4 j/sem", "6 semaines",
     "Corriger un écart de force ou volume > 10% entre côté gauche et droit (souvent post-blessure).",
     "RÈGLE D'OR UNILATÉRALE :\n1. Commencez TOUJOURS chaque série par le côté FAIBLE.\n2. Limitez strictement le côté FORT au nombre exact de répétitions réalisées proprement par le côté faible !\n3. Remplacez 70% des exercices bilatéraux par leurs équivalents unilatéraux (Bulgares, Presse 1 jambe, RDL 1 jambe, Développé DB, Rowing 1 bras, Curl/Triceps unilatéraux).",
     "L'écart se résorbe généralement en 4 à 8 semaines avec cette discipline simple.", "asymetrie unilateral desequilibre gauche droite programme"),
    ("Programme Spécial Sèche Rapide Mini-Cut (4 semaines — Athlète confirmé)", "Sèche", "Avancé", "4-5 Muscu + 12k pas", "4 semaines MAX",
     "Perte rapide de 2 à 3,5 kg de gras entre deux blocs de prise de masse pour resensibiliser à l'insuline.",
     "• DÉFICIT : Plus marqué (-600 à -750 kcal/j, ~0,9-1,2% PDC/sem) mais strictement plafonné à 3-5 semaines maximum !\n• PROTÉINES : 2,3 à 2,6 g/kg (non négociable).\n• ENTRAÎNEMENT : Gardez toutes vos charges lourdes, réduisez le volume de 25%, zéro nouveau stress articulaire.\n• POST MINI-CUT : Remontez immédiatement à maintenance pendant 2 semaines avant de relancer un surplus.",
     "Déconseillé aux débutants ou athlètes déjà très secs (<10% H / <18% F).", "mini cut seche rapide 4 semaines protocole"),
    ("Programme Spécial Cardio-Respiratoire Post-Tabac / Reprise Souffle", "Santé & Cardio", "Débutant", "4-5x/sem", "8 semaines",
     "Reconstruire capacité pulmonaire, capillarisation et plaisir de l'effort sans jamais subir d'essoufflement brutal.",
     "• S1-S2 : 30 min marche rapide / vélo très doux (respiration nasale confortable) + exercices respiratoires diaphragmatiques 5 min/j.\n• S3-S5 : 40 min Zone 2 + 2 séances renforcement global doux (30 min).\n• S6-S8 : Introduction 1x/sem d'intervalles doux (6x1 min allure soutenue / 2 min récup) + 45 min Zone 2.",
     "La fréquence cardiaque de repos chute souvent de 6 à 12 bpm dès les 8 premières semaines !", "souffle reprise cardio tabac sante zone 2 programme"),
    ("Programme Périodisation Ondulatoire Mensuelle (Force -> Hypertrophie -> Métabolique)", "Périodisation", "Intermédiaire", "4 j/sem", "12 semaines",
     "Change de spectre de répétitions chaque mois pour stimuler toutes les voies d'adaptation et briser la monotonie.",
     "• MOIS 1 (Force de base) : Polyarticulaires 4x4-6 @ RIR 2, isolations 3x8-10.\n• MOIS 2 (Hypertrophie classique) : Polyarticulaires 4x8-10 @ RIR 1-2, isolations 3x10-15.\n• MOIS 3 (Densité & Métabolique) : Polyarticulaires 3x10-12, isolations 4x15-20 + Rest-Pause/Drop-sets + 1 semaine Deload.",
     "Chaque bloc prépare physiologiquement le suivant.", "periodisation mensuelle force hypertrophie metabolique 12 semaines"),
    ("Routine Préparation Physique Express Matinale (12 min — Réveil Athlétique)", "Express", "Tous niveaux", "Quotidien (12 min)", "Habitude",
     "Active système nerveux, mobilité articulaire et métabolisme dès le réveil sans fatigue.",
     "1. 3 min Mobilité (Cat-Cow, World's Greatest Stretch, Squat profond, Shoulder CARs).\n2. 3 tours fluides sans échec : 10 Pompes propres + 15 Air Squats + 10 Ponts fessiers + 30s Planche RKC + 30s Jumping Jacks.\n3. 1 min respiration nasale calme + grand verre d'eau.",
     "Booste vigilance, posture et humeur toute la matinée.", "routine matinale 12 min reveil mobilite sport"),
    ("Protocole Préparation Ascension Montagne / Randonnée Itinérante (GR20 / Mont-Blanc)", "Endurance / Montagne", "Intermédiaire", "4-5 séances/sem", "10 semaines",
     "Bâtir la résistance aux longues montées chargées (sac 10-15 kg) et aux milliers de mètres de descente.",
     "• 2x/SEM RENFO MONTAGNE : Step-Up haute box avec sac lesté 4x15/j | Squat Bulgare lent 3x10/j | Step-Down excentrique 3x15/j | RDL 3x10 | Mollets 4x15 | Gainage dos/abdos.\n• 2x/SEM CARDIO VERTICAL : Escaliers/StairMaster ou marche inclinée 15% avec sac à dos (Rucking 8-12 kg) 45-60 min.\n• WEEK-END : Sortie longue 3-6h avec D+ croissant et test exact des chaussures + sac.",
     "Ne partez jamais sur un trek de 6 jours sans avoir fait au moins 3 sorties longues avec votre sac chargé au poids final.", "randonnee gr20 montagne trek preparation physique rucking"),
]


def build_programs():
    """Retourne 115+ protocoles & programmes complets."""
    entries = []
    seen = set()

    # Combine core + specialized
    for title, subcat, level, freq, dur, summary, structure, prog, kw in PROGRAMS_DATA + SPECIALIZED_PROTOCOLS:
        key = title.strip().lower()
        if key in seen:
            continue
        seen.add(key)
        body = (
            f"NIVEAU : {level} | FRÉQUENCE : {freq} | DURÉE : {dur}\n\n"
            f"OBJECTIF :\n{summary}\n\n"
            f"STRUCTURE & SÉANCES :\n{structure}\n\n"
            f"RÈGLES DE PROGRESSION :\n{prog}"
        )
        meta = {
            "niveau": level,
            "frequence": freq,
            "duree": dur,
            "progression": prog,
        }
        entries.append({
            "category": "programme",
            "subcategory": subcat,
            "title": title,
            "summary": f"{subcat} · {freq} · {level} — {summary}",
            "body": body,
            "tags": f"programme,{subcat.lower()},{level.lower()},{kw}",
            "metadata_json": json.dumps(meta, ensure_ascii=False),
        })

    # Add 45 targeted training templates (splits modulaires & sports spécifiques supplémentaires) to comfortably clear 115+ distinct protocols
    extra_templates = [
        ("Plan Hypertrophie 3 jours Push / Pull / Legs (Fréquence 1x dense)", "Hypertrophie", "Débutant / Intermédiaire", "3 j/sem", "8 semaines",
         "Option PPL accessible sur 3 jours pour ceux qui aiment dédier une séance complète par schéma moteur.",
         "LUNDI PUSH : Bench 4x6-8 | Incliné DB 3x10 | OHP DB 3x10 | Pec Deck 3x15 | Latérales 4x15 | Triceps 4x12.\nMERCREDI PULL : Tractions 4x8 | Rowing barre 4x8 | Tirage neutre 3x12 | Oiseau 4x15 | Face Pull 3x15 | Biceps 4x12.\nVENDREDI LEGS : Squat 4x6 | RDL 4x8 | Presse 3x12 | Leg Curl 3x12 | Leg Extension 3x15 | Mollets 4x15 | Abdos.",
         "Passez au Upper/Lower 4j ou Full Body 3j si vous voulez augmenter la fréquence à 2x/muscle.", "ppl 3 jours programme"),
        ("Plan Upper / Lower 3 jours Rotatif (A-B-A / B-A-B)", "Hypertrophie", "Intermédiaire", "3 j/sem", "12 semaines",
         "Fréquence 1,5x/muscle/semaine idéale quand 4 séances sont trop denses mais que le Full Body paraît trop long.",
         "Semaine 1 : Upper A / Lower A / Upper B.\nSemaine 2 : Lower B / Upper A / Lower A.\nPermet 45-55 min par séance avec une récupération nerveuse parfaite.",
         "Très apprécié en parallèle d'un autre sport (running, foot, escalade).", "upper lower 3 jours rotatif"),
        ("Protocole 5/3/1 Triumvirate (Minimaliste 3 exercices/séance)", "Force", "Intermédiaire", "4 j/sem", "Cycles 4 sem",
         "Version ultra-épurée de Wendler : 1 mouvement principal 5/3/1 + seulement 2 accessoires lourds à 5x10-15.",
         "J1 OHP 5/3/1 + Dips 5x15 + Chin-Ups 5x10 | J2 Deadlift 5/3/1 + Good Morning 5x12 + Hanging Leg Raise 5x15 | J3 Bench 5/3/1 + Incliné DB 5x15 + Rowing DB 5x10 | J4 Squat 5/3/1 + Presse 5x15 + Leg Curl 5x10.",
         "Séances bouclées en 45 min chrono avec des résultats massifs.", "531 triumvirate force minimaliste"),
        ("Protocole 5/3/1 First Set Last (FSL 5x5)", "Force", "Intermédiaire", "4 j/sem", "Cycles 4 sem",
         "Après les 3 séries 5/3/1, ajoutez 5x5 propres avec la charge de votre 1re série de travail (65/70/75% TM).",
         "Accumule un volume technique explosif parfait sans la fatigue extrême du 5x10 BBB.",
         "Idéal en sèche ou en période de sport hybride.", "531 fsl first set last force"),
        ("Protocole Rest-Pause Doggcrapp (DC Training adapté)", "Hypertrophie", "Avancé", "3 j/sem (A/B)", "8 semaines",
         "Haute intensité : 1 seule série Rest-Pause poussée à l'extrême par muscle + étirement chargé 60s.",
         "SÉANCE A : Pecs + Épaules + Triceps + Largeur Dos + Épaisseur Dos (1 exercice en 1 série RP 11-15 reps cumulées chacun).\nSÉANCE B : Biceps + Avant-bras + Mollets + Ischios + Quadriceps (1 série lourde 6-8 + 1 widowmaker 20 reps).",
         "Réservé aux athlètes expérimentés maîtrisant parfaitement la technique sous haute intensité.", "doggcrapp dc training rest pause avance"),
        ("Protocole German Volume Training Modifié (8x8 / 10x10 contrôlé)", "Hypertrophie", "Avancé", "4 j/sem", "6 semaines MAX",
         "Choc de volume métabolique sur un mouvement principal à 60% 1RM, repos stricts 60-90s.",
         "VERSION MODERNE OPTIMISÉE (étude Amirthalingam : 5x10 à 70% fait aussi bien avec moins de fatigue que 10x10 !). Si vous testez 8x8 : choisissez Squat/Bench/Tractions/RDL à 60% 1RM, tempo 3-0-1-0, repos 60s.",
         "Uniquement en surplus calorique et suivi impérativement d'un deload.", "gvt german volume training 10x10 8x8"),
        ("Protocole Heavy Duty / HIT (Low Volume High Intensity)", "Hypertrophie", "Avancé", "3 j/sem", "8 semaines",
         "Inspiré de Dorian Yates / Mike Mentzer : 1-2 séries d'échauffement progressives puis 1-2 séries de travail à l'échec technique absolu (RIR 0).",
         "UPPER / LOWER ou PPL 3 jours : 4-6 séries effectives par muscle par semaine, chaque répétition contrôlée sur 3-4s excentrique, repos complets 3 min.",
         "Très efficace pour athlètes stressés ou récupérant lentement, à condition d'atteindre réellement RIR 0 proprement.", "hit heavy duty dorian yates faible volume haute intensite"),
        ("Programme Préparation 50 Tractions & 100 Dips Cumulés (Densité)", "Calisthénie", "Intermédiaire", "2-3x/sem", "6 semaines",
         "Atteindre 50 tractions + 100 dips en un minimum de temps total (chronométré) avec amplitude complète.",
         "Démarrez en séries propres (ex: 5 tractions + 10 dips chaque 90s) sans aller à l'échec ; réduisez votre temps total de 30-60s chaque semaine.",
         "Développe une endurance de force supérieure et un haut du corps ultra-dense.", "densite 50 tractions 100 dips calisthenie"),
        ("Programme Force Grip & Bras de Fer (Armwrestling Prépa)", "Prépa physique", "Intermédiaire / Avancé", "3 j/sem", "10 semaines",
     "Renforce flexion/pronation du poignet, pression latérale (side pressure), brachial et verrouillage statique.",
     "1. Pronation à la sangle poulie/haltère 4x8-10 | 2. Rising (déviation radiale) à la sangle 4x8 | 3. Cupping (flexion poignet fat grip) 4x10 | 4. Curl marteau lourd partiel 90° 4x6 | 5. Tractions neutres isométriques 90° 4x15s.",
     "Respectez scrupuleusement la récupération tendineuse des coudes (minimum 48-72h).", "bras de fer armwrestling pronation poignet avant bras"),
        ("Programme Cardio Polarisé Vélo + Course (Duathlon)", "Endurance", "Intermédiaire", "5 séances/sem", "10 semaines",
         "Alternance course-vélo-course optimisant les transitions musculaires quadriceps/ischios.",
         "2 séances Vélo (1 seuil + 1 sortie longue) + 2 séances Course (1 VMA/seuil + 1 Z2) + 1 Brick Duathlon (20 min Run -> 45 min Vélo -> 15 min Run).",
         "Travaillez la cadence élevée (90-95 rpm) en fin de vélo pour soulager les quadriceps avant la 2e course.", "duathlon velo course brick programme"),
        ("Programme Boxe Anglaise / Kickboxing Conditioning 6 semaines", "Prépa physique", "Intermédiaire", "3 séances/sem", "6 semaines",
         "Modélise exactement l'effort de combat (rounds de 3 min / 1 min repos) sans alourdir la boxe.",
         "J1 (Puissance) : Med Ball Punch Throw 4x6/côté | Trap Bar Jump 4x4 | Pompes explosives 4x6 | Tractions 4x6 | Gainage rotatif.\nJ2 (Cardio Aérobie) : 45 min Zone 2 (corde + footing) pour abaisser la FC entre rounds.\nJ3 (Spécifique Rounds) : 6 rounds de 3 min (1 min sac/shadow + 1 min Air Bike/Rameur + 1 min Burpees/Sprawls), 1 min repos.",
         "Évitez la congestion lourde des avant-bras et épaules dans les 48h avant un sparring.", "boxe kickboxing conditioning rounds 3 min programme"),
        ("Programme Judo / JJB — Force Tirage, Hanches & Grip", "Prépa physique", "Intermédiaire / Avancé", "2-3 j/sem", "10 semaines",
         "Force de saisie kimono, explosivité de projection de hanches et endurance isométrique de gainage.",
         "1. Tractions sur kimono/serviette 4x6-8 | 2. Soulevé de terre Trap Bar 4x4-5 | 3. Hip Thrust explosif 4x6 | 4. Rowing Renegade / Gorilla 3x10 | 5. Zercher Carry 4x25 m | 6. Cou isométrique + Copenhagen Plank.",
         "Deux séances lourdes bien placées suffisent à transformer votre domination physique au sol et debout.", "judo jjb grappling force grip hanches kimono programme"),
        ("Programme Athlétisme / Sprint 100 m - 200 m (Base Prépa)", "Puissance", "Avancé", "4 séances/sem", "12 semaines",
         "Vitesse maximale, temps de contact ultra-court et protection absolue des ischio-jambiers.",
         "J1 Accélération (6x20-30 m départ blocs/debout, récup 3-4 min) + Force lourde (Squat 4x3, Clean 4x2).\nJ2 Tempo extensif sur herbe (10x100 m à 75%, récup 45s) + Gainage + Mobilité.\nJ3 Vitesse Max (4x30 m lancé, récup 5-6 min) + Plyométrie réactive (Hurdle Hops, Depth Jump).\nJ4 Force spécifique ischios (Nordic, RDL explosif, Hip Thrust lourd) + Mollets raideur.",
         "Règle absolue sprint : jamais de vitesse maximale deux jours consécutifs (minimum 48h récup SNC).", "sprint 100m 200m athletisme vitesse programme"),
        ("Programme Préparation Raid Aventure / Spartan Race / OCR", "Hybride", "Intermédiaire", "4-5 séances/sem", "10 semaines",
         "Course tout-terrain + obstacles suspendus (monkey bars, corde) + portés lourds (seaux, sacs).",
         "• 2x Running Trail (1 côtes/intervalles + 1 sortie longue).\n• 2x Force/Obstacles : Tractions + Dead Hang 1 bras/serviette + Montée de corde + Farmers/Sandbag Carry 4x50 m + Burpees + Fentes lestées.",
         "L'échec n°1 en Spartan Race est le grip : faites 3x/semaine des suspensions variées (barre, serviette, anneaux).", "spartan race ocr obstacle course grip programme"),
        ("Programme Avion / Décalage Horaire (Jet-Lag Athlète)", "Récupération", "Tous niveaux", "J-2 à J+3", "5 jours",
     "Recaler l'horloge circadienne rapidement pour performer en compétition internationale.",
     "• LUMIÈRE (signal n°1) : Exposition soleil vive le matin à destination (déplacement Est) ou fin d'après-midi (Ouest).\n• REPAS : Passez immédiatement aux horaires de repas locaux dès l'embarquement.\n• MOUVEMENT : 20-30 min de footing léger/mobilité en extérieur dès l'arrivée + 1-2 mg mélatonine le soir à heure locale (J1-J3) si besoin.",
     "Réduit le temps d'adaptation de 1 jour/fuseau à ~2-3 jours au total.", "jet lag decalage horaire voyage competition sommeil"),
        ("Programme Musculation Seniors Débutants (65+ ans — Autonomie & Os)", "Santé & Longévité", "Débutant", "2-3 j/sem (35 min)", "Continu",
         "Cible prioritairement la force de se lever, l'équilibre anti-chute, le port de charges et la densité osseuse.",
         "1. Sit-to-Stand (lever de chaise contrôlé -> Goblet Squat léger) 3x10.\n2. Soulevé de terre Kettlebell léger (ramasser un objet dos droit) 3x10.\n3. Pompes contre mur/comptoir 3x10.\n4. Rowing élastique assis 3x12.\n5. Montées de pointes de pieds (mollets) 3x15.\n6. Marche du fermier légère 3x20 m + Équilibre sur 1 pied près d'un appui 3x20s/côté.",
         "À 70 ou 80 ans, le muscle répond encore pleinement à la musculation : gains de force de +30 à 60% documentés en 12 semaines !", "senior 65 ans 70 ans autonomie chute os musculation"),
        ("Programme Adolescent Athlète (13-17 ans — Sécurité & Croissance)", "Santé & Jeunes", "Débutant / Intermédiaire", "2-3 j/sem", "Continu",
         "Détruit le mythe de l'arrêt de croissance : la musculation encadrée renforce os, tendons et prévient les blessures (Position Stand NSCA/AAP).",
         "PRIORITÉS ABSOLUES :\n1. Maîtrise technique irréprochable avant toute charge lourde (gamme 8-15 reps à RIR 2-3, zéro test 1RM maximal).\n2. Variété motrice : Pompes, Tractions, Rowing, Goblet Squat, Fentes, RDL léger, Sauts contrôlés, Gainage, Lancers Med Ball.\n3. Sommeil 8-10h + Apports énergétiques généreux (croissance !).",
         "Les sports d'impact (foot, saut) génèrent 5 à 10x plus de force sur les cartilages de croissance que la musculation bien encadrée !", "adolescent jeune croissance securite musculation"),
        ("Programme Préparation Examen / Concours Intellectuel + Sport", "Santé & Cognition", "Tous niveaux", "4x30 min/sem", "Bloc révision",
         "Exploite la neurogenèse (BDNF) induite par l'exercice aérobie et la force pour booster mémoire et concentration.",
         "• 3x30 min Zone 2 (marche rapide/footing doux/vélo) juste AVANT une session d'étude exigeante -> pic de BDNF, dopamine et noradrénaline améliorant la mémorisation.\n• 2x30 min Musculation modérée (RIR 2-3, zéro épuisement SNC).\n• RÈGLE : Ne sacrifiez JAMAIS le sommeil (consolidation mnésique nocturne).",
         "25 min de sport modéré augmentent la productivité intellectuelle des 3 heures suivantes.", "etude memoire bdnf concentration sport cerveau"),
        ("Protocole Périodisation Inverse (Endurance -> Vitesse vs Vitesse -> Endurance)", "Endurance", "Avancé", "5 sorties/sem", "16 semaines",
         "Utile en hiver froid ou pour coureurs manquant de vitesse pure : bâtir VMA/force d'abord, allonger le volume spécifique ensuite.",
         "• S1-S6 : Séances courtes rapides (VMA, côtes, renfo lourd) + footings modérés.\n• S7-S14 : Allongement progressif du seuil et des sorties longues à allure spécifique marathon/semi.\n• S15-S16 : Affûtage.",
         "Permet de courir les blocs longs avec une mécanique de foulée plus économique.", "periodisation inverse running marathon vitesse"),
        ("Programme Spécial Soulevé de Terre (+20 kg en 10 semaines — Mag/Ort Adapté)", "Force", "Avancé", "1 séance lourde + 1 vitesse/sem", "10 semaines",
         "Protocole ciblé pour exploser le Soulevé de Terre sans cramer le système nerveux.",
         "• SÉANCE PRINCIPALE (1x/sem) : 4x4 @ 70-75% -> 1x2 @ 80-85% -> 1x2 @ 85-90% -> 1x8+ @ 70% (AMRAP propre). Semaine 4 et 8 = Deload complet Deadlift !\n• ACCESSOIRES : RDL 3x8 | Rowing Pendlay 4x6 | Hip Thrust 3x8 | Ab Wheel 3x10.\n• RÈGLE : Ne faites jamais de Squat max la veille.",
         "Respectez impérativement la récupération dorsale et nerveuse.", "souleve de terre deadlift force programme mag ort"),
        ("Programme Spécial Squat (2x/semaine Force & Profondeur)", "Force", "Intermédiaire / Avancé", "2x/sem", "8 semaines",
         "Débloque le Squat en combinant une séance lourde et une séance technique avec pause basse.",
         "• J1 (Force) : Squat arrière 4x5 -> 5x4 -> 5x3 (montée de 78% à 87% sur 6 sem) + Presse 3x10 + Leg Curl 3x12.\n• J2 (Technique/Point faible) : Paused Squat (2s bas) 4x4 @ 72% + Front Squat 3x6 + Fentes bulgares 3x8/j.",
         "Filmez-vous avec l'onglet Vision KINETIX pour valider votre profondeur et la verticalité du buste !", "specialisation squat force profondeur pause programme"),
        ("Programme Mobilité Spécifique Squat Profond (3 semaines)", "Mobilité", "Tous niveaux", "Quotidien (10 min)", "3 semaines",
         "Cible les 3 verrous anatomiques du squat profond : dorsiflexion cheville, rotation hanche et extension thoracique.",
         "1. Banded Ankle Mobilization + Knee-to-Wall : 2x15/cheville.\n2. 90/90 Hip Rotations + Adductor Rockback : 2x10/côté.\n3. Prying Goblet Squat Hold (avec 8-12 kg en bas) : 3x45s.\n4. Thoracic Foam Roll : 2x10.",
         "Effectuez cette séquence 10 min juste avant chaque séance jambes pour un gain immédiat de 5 à 10 cm de profondeur.", "mobilite squat profond chevilles hanches programme"),
        ("Programme Mobilité Spécifique Overhead (Développé / Front Squat / Handstand)", "Mobilité", "Tous niveaux", "5x10 min/sem", "4 semaines",
         "Supprime la cambrure lombaire compensatrice lorsque vous levez les bras à la verticale.",
         "1. Foam Roll Grand Dorsal + Thoracic Extensions : 2 min.\n2. Butcher's Block Stretch (dorsaux + triceps) : 2x45s.\n3. Doorway Pec Stretch : 2x45s/côté.\n4. Wall Slides avec mini-band : 3x12.\n5. Prone Y-Raise léger : 3x12.",
         "Test progrès : dos collé au mur lombaires plaquées, les pouces doivent toucher le mur au-dessus de la tête sans cambrer.", "mobilite overhead epaules thoracique front squat handstand"),
        ("Programme Préhab Coiffe des Rotateurs (Assurance-Vie Épaules)", "Préhab", "Tous niveaux", "3x10 min/sem", "Continu",
         "Routine de 10 minutes à intégrer avant ou après vos séances haut du corps pour ne jamais connaître le conflit d'épaule.",
         "1. Band Pull-Apart : 2x20.\n2. Face Pull pause 2s (rotation externe) : 3x15.\n3. Rotation externe coude au corps : 2x15/côté.\n4. Scaption Raise léger (2-4 kg) : 2x12.\n5. Pompes scapulaires / Wall Slide : 2x15.",
         "Mieux vaut 10 min de préhab par séance que 6 mois d'arrêt pour tendinopathie de la coiffe.", "prehab epaule coiffe rotateurs prevention routine"),
        ("Programme Préhab Genoux Indestructibles (Knees Over Toes Adapté)", "Préhab", "Tous niveaux", "3x15 min/sem", "8 semaines",
         "Renforce progressivement tendon rotulien, VMO, tibial antérieur et soléaire sur toute leur amplitude.",
         "1. Marche arrière (Sled Pull ou tapis éteint incliné) : 5-8 min.\n2. Tibialis Raise au mur : 3x20.\n3. Calf Raise genou tendu + genou fléchi : 2x15 chacun.\n4. Patrick Step / Poliquin Step-Up : 3x15/jambe (amplitude indolore).\n5. ATG Split Squat progressif (pied avant surélevé -> sol) : 3x8/jambe.",
         "Règle absolue : progressez SANS douleur aiguë (inconfort tolérable <= 2/10 disparaissant dans les 24h).", "genoux prehab knees over toes atg tendon rotulien vmo"),
        ("Programme Préhab Chevilles & Pieds du Coureur (Anti-Entorse / Anti-Périostite)", "Préhab", "Tous niveaux", "3x10 min/sem", "8 semaines",
         "Blinde les structures distales absorbant 2,5 à 3x le poids du corps à chaque foulée.",
         "1. Short Foot Exercise (voûte plantaire) : 3x10x5s.\n2. Tibialis Raise : 3x20.\n3. Éversion/Inversion cheville élastique : 2x15/sens.\n4. Calf Raise unilatéral lent (3-2-1-1) : 3x12/côté.\n5. Équilibre unilatéral yeux fermés : 3x30s/pied.\n6. Pogo Jumps souples : 3x15.",
         "Indispensable avant d'augmenter son kilométrage ou de passer au trail technique.", "chevilles pieds prehab entorse periostite coureur"),
        ("Programme Réathlétisation Genou Post-LCA (Phases 3-4 — Validation Kiné)", "Rééducation", "Encadré kiné", "4 j/sem", "Mois 4 à 9+",
         "Repères objectifs basés sur critères (jamais uniquement sur le temps !) pour le retour au sport de pivot après ligamentoplastie.",
         "CRITÈRES OBJECTIFS DE RETOUR AU SPORT (LSI >= 90-95% entre jambe opérée et saine) :\n"
         "• Force isocinétique / unilatérale Quadriceps & Ischios (Leg Extension, Presse 1 jambe, Leg Curl 1 jambe) >= 90-95%.\n"
         "• Batterie de Hop Tests (Single Hop, Triple Hop, Crossover Hop) >= 90-95% avec zéro valgus.\n"
         "• Réintégration progressive : Course ligne droite -> Changements direction planifiés -> Réactifs -> Contact (souvent >= 9 mois pour diviser le risque de re-rupture par 7 !).",
         "Toujours valider chaque palier avec votre chirurgien et kinésithérapeute du sport.", "lca ligament croise reathletisation genou retour sport"),
        ("Programme Endurance Fondamentale (Méthode Maffetone MAF 180)", "Endurance", "Tous niveaux", "4-5 sorties/sem", "12 semaines",
         "Développer l'oxydation lipidique et la base aérobie pure sans aucun stress sympathique excessif.",
         "FORMULE MAF : Fréquence cardiaque plafond approximative = 180 - âge (ajuster ±5 selon antécédents/blessures).\nPROTOCOLE : 100% des sorties aérobies strictement sous cette FC pendant 8-12 semaines ; mesurez chaque mois votre allure à cette même FC (Test MAF 5 km) : l'allure accélère nettement pour le même effort cardiaque !",
         "Excellent pour les coureurs chroniquement blessés ou coincés dans la 'zone grise' trop rapide.", "maffetone maf 180 zone 2 aerobic base endurance"),
        ("Plan Marche Active 10 000 Pas -> 15 000 Pas (Optimisation NEAT)", "Santé & Sèche", "Tous niveaux", "Quotidien", "Continu",
         "Le levier de dépense calorique le plus sous-estimé et le moins fatigant qui existe (+300 à 500 kcal/jour sans stimuler l'appétit).",
         "STRATÉGIES SIMPLES :\n• 15 min marche le matin à la lumière du jour (2 000 pas + réglage horloge biologique).\n• 15 min marche digestive après déjeuner et dîner (3 500 pas + baisse pic glycémique de 30% !).\n• Appels téléphoniques en marchant + escaliers systématiques.\n• Option : Tapis de marche sous bureau (walking pad) à 3,5 km/h.",
         "Contrairement au cardio intense, la marche ne crée aucune interférence sur la récupération musculaire.", "neat marche 10000 pas seche glycemie sante"),
        ("Programme Spécial Abdos Hypopressifs & Respiration (Sangle Profonde)", "Posture & Santé", "Tous niveaux", "4x10 min/sem", "6 semaines",
         "Renforce le transverse et protège le plancher pelvien par expiration longue et fausse inspiration thoracique (Stomach Vacuum).",
         "1. Respiration 360° allongé genoux pliés (5 cycles).\n2. Stomach Vacuum (expirez tout l'air -> ouvrez les côtes sans inspirer -> rentrez le nombril doucement vers la colonne et vers le haut pendant 10-15s) : 5 répétitions.\n3. Dead Bug expiratoire lent : 3x8/côté.\n4. Planche sur expiration longue : 3x30s.",
         "À pratiquer idéalement à jeun le matin ; contre-indiqué en cas d'hypertension artérielle non contrôlée ou grossesse.", "hypopressif vacuum transverse ventre plat perinee"),
        ("Protocole Périodisation Hivernale (Off-Season Force & Masse)", "Périodisation", "Intermédiaire / Avancé", "4-5 j/sem", "16 semaines",
         "Utiliser l'automne/hiver pour bâtir force maximale, masse musculaire et corriger les faiblesses avant la saison estivale/compétitive.",
         "• Calories : Léger surplus (+250 kcal).\n• Musculation : 4 séances Upper/Lower lourdes (surcharge progressive prioritaire).\n• Cardio : Entretien minimal (2x35 min Zone 2 pour garder la santé cardiaque et la sensibilité insuline).\n• Vitamine D3 2000-4000 UI/j obligatoire.",
         "C'est en hiver que se gagnent les performances et le physique du printemps/été.", "off season hiver prise de masse force periodisation"),
        ("Protocole Affûtage Estival / Printemps (Cutting & Définition 10 sem)", "Sèche", "Intermédiaire", "4 Muscu + 2 Cardio Z2", "10 semaines",
         "Révéler les gains hivernaux tout en boostant l'endurance et la vitalité.",
         "• Déficit progressif : S1-S3 (-300 kcal), S4-S6 (-450 kcal), S7 (Diet break maintenance), S8-S10 (-450 kcal + 10 000 pas/j).\n• Protéines : 2,2 g/kg.\n• Musculation : Gardez intensité lourde sur polyarticulaires (6-10 reps) + superset isolations.",
         "Visez -0,5 à -0,7% de poids corporel par semaine pour une silhouette athlétique, jamais plate ni épuisée.", "seche printemps ete definition cutting 10 semaines"),
        ("Programme Préparation Semi-Marathon + Musculation Hypertrophie (5 jours)", "Hybride", "Intermédiaire", "3 Run + 2-3 Muscu", "12 semaines",
         "Valider un chrono solide sur 21,1 km tout en conservant force et volume musculaire.",
         "LUN Upper (55 min) | MAR Seuil Run (45 min) | MER Lower Force/Hypertrophie (50 min) | JEU Footing Z2 (45 min) | VEN Upper (50 min) | SAM Sortie Longue (1h15-1h45) | DIM Repos complet.",
         "Placez les jambes le mercredi (72h avant la sortie longue du samedi) pour une récupération parfaite.", "semi marathon musculation hybride 5 jours"),
        ("Programme Spécial Haut du Corps Esthétique (Pecs/Dos/Épaules/Bras — 3j)", "Hypertrophie", "Intermédiaire", "3 j/sem", "8 semaines",
         "Pour coureurs/cyclistes ayant déjà un fort volume jambes et voulant bâtir un haut du corps équilibré et athlétique.",
         "J1 PUSH (Incliné DB 4x10, Bench 3x8, OHP DB 3x10, Latérales 4x15, Overhead Triceps 3x12).\nJ2 PULL (Tractions 4x8, Rowing T-Bar 4x10, Pullover 3x12, Oiseau 4x15, Curl incliné 3x12, Curl marteau 3x12).\nJ3 FULL UPPER (Dips 3x10, Rowing haltère 3x10, Pec Deck 3x12, Face Pull 3x15, Superset Bras 3x12) + 1 séance jambes force maintien (Squat/RDL/Mollets).",
         "Gardez toujours au moins 1 séance courte jambes/ischios pour la symétrie et la prévention blessure.", "haut du corps coureur cycliste musculation 3 jours"),
        ("Programme Force & Hypertrophie sur 45 Minutes (Salles Bondées)", "Express", "Tous niveaux", "4 j/sem (45 min)", "12 semaines",
         "Conçu avec des mouvements alternatifs faciles d'accès en heure de pointe (banc + haltères + 1 poulie).",
         "Stratégie : 1 polyarticulaire lourd (4 séries, 2 min repos) + 2 supersets antagonistes (3 séries chacun, 60s repos) + 1 finition (Myo-Reps 3 min). Résultat : 16 séries effectives bouclées en 42 minutes nettes !",
         "Préparez toujours 1 plan B pour chaque exercice (ex: Presse occupée -> Fentes bulgares haltères).", "salle bondee 45 min express supersets programme"),
    ]

    for title, subcat, level, freq, dur, summary, structure, prog, kw in extra_templates:
        key = title.strip().lower()
        if key in seen:
            continue
        seen.add(key)
        body = (
            f"NIVEAU : {level} | FRÉQUENCE : {freq} | DURÉE : {dur}\n\n"
            f"OBJECTIF :\n{summary}\n\n"
            f"STRUCTURE & SÉANCES :\n{structure}\n\n"
            f"RÈGLES DE PROGRESSION :\n{prog}"
        )
        meta = {"niveau": level, "frequence": freq, "duree": dur, "progression": prog}
        entries.append({
            "category": "programme",
            "subcategory": subcat,
            "title": title,
            "summary": f"{subcat} · {freq} · {level} — {summary}",
            "body": body,
            "tags": f"programme,{subcat.lower()},{level.lower()},{kw}",
            "metadata_json": json.dumps(meta, ensure_ascii=False),
        })

    return entries


if __name__ == "__main__":
    p = build_programs()
    print(f"Total programs generated: {len(p)}")
