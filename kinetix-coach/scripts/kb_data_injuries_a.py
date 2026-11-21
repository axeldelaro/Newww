# -*- coding: utf-8 -*-
"""Prévention des blessures, préhab, rééducation & mobilité — Partie A (protocoles cliniques & pathologies sportives majeures)."""

# Format: (titre, sous_cat, resume, protocole_complet, drapeaux_rouges, mots_cles)
INJURIES_PART_A = [
    ("Protocole PEACE & LOVE (Remplace le vieux RICE / Glace)", "Principes Rééducation",
     "Consensus moderne du British Journal of Sports Medicine (Dubois & Esculier 2019) pour gérer toute blessure aiguë des tissus mous.",
     "JOURS 1-3 (PEACE) :\n"
     "• P — Protection : décharge relative 1-3 jours (éviter douleur vive sans immobilisation excessive).\n"
     "• E — Élévation : membre au-dessus du cœur le plus souvent possible.\n"
     "• A — Avoid Anti-inflammatories : ÉVITEZ les AINS (ibuprofène) et la glace excessive qui ralentissent la cicatrisation naturelle !\n"
     "• C — Compression : bandage élastique doux limitant l'œdème.\n"
     "• E — Éducation : confiance dans l'auto-guérison active.\n"
     "APRÈS J+3 (LOVE) :\n"
     "• L — Load : remise en charge progressive guidée par la tolérance (douleur <= 3/10).\n"
     "• O — Optimisme : le mental module directement la douleur.\n"
     "• V — Vascularisation : cardio doux sans douleur dès que possible.\n"
     "• E — Exercice : restaurer mobilité, force et proprioception.",
     "Déformation visible, craquement audible majeur, incapacité totale d'appui, engourdissement -> urgences / médecin.",
     "peace love rice glace entorse blessure aigue ains protocole"),
    ("Règle du Feu Tricolore de la Douleur en Rééducation (Silbernagel)", "Principes Rééducation",
     "Repère clinique validé pour doser l'entraînement sur tendinopathie ou reprise sans aggraver la lésion.",
     "• VERT (0 à 2/10) : Zone sûre, on peut progresser.\n"
     "• ORANGE (3 à 4/10) : Zone acceptable UNIQUEMENT si la douleur redescend au niveau de base dans les 24h suivantes (pas de raideur matinale aggravée le lendemain !).\n"
     "• ROUGE (>= 5/10 ou aggravation à J+1) : Trop lourd/trop vite -> réduisez charge ou volume de 30-50%.",
     "Douleur nocturne constante qui réveille, perte de force neurologique, gonflement chaud -> avis médical.",
     "regle douleur echelle 0 10 silbernagel reeducation tendon reprise"),
    ("Tendinopathie Rotulienne (Jumper's Knee / Genou du Sauteur)", "Genou",
     "Douleur localisée exactement sous la pointe inférieure de la rotule, déclenchée par sauts, squats profonds et escaliers.",
     "PROTOCOLE PROGRESSIF 4 PHASES (Jill Cook / Kongsgaard) :\n"
     "• Phase 1 (Analgésie) : Isométriques lourds (Spanish Squat ou Leg Extension à 60°, 5x45s à 70% effort, 2 min repos, quotidien). Réduisez temporairement les sauts.\n"
     "• Phase 2 (HSR — Heavy Slow Resistance) : Squat plan incliné / Presse / Hack Squat tempo 3-0-3-0 (4x12 -> 4x8 -> 4x6 lourd, 3x/sem, douleur <= 3/10).\n"
     "• Phase 3 (Stockage Énergie) : Réintroduction progressive sauts doux, Skater Jumps, courses courtes.\n"
     "• Phase 4 (Retour Sport) : Sauts maximaux / matchs (espacer de 48-72h au début) + Collagène 15 g + vit C 45 min avant séance.",
     "Blocage mécanique du genou, gonflement massif immédiat, douleur sur l'os tibial chez l'ado (Osgood-Schlatter) -> médecin du sport.",
     "tendinopathie rotulienne tendinite genou jumper knee spanish squat reeducation"),
    ("Syndrome Fémoro-Patellaire (Syndrome Rotulien / Genou du Coureur)", "Genou",
     "Douleur diffuse autour ou derrière la rotule aggravée par descentes, escaliers, squat ou position assise prolongée ('signe du cinéma').",
     "CAUSES : Surcharge trop rapide + faiblesse quadriceps/moyen fessier + overstriding.\n"
     "PROTOCOLE : 1. Ajustez temporairement le volume de course/descentes pour rester <= 3/10. 2. Renforcez HANCHES + QUADRICEPS (Abductions, Hip Hike, Fentes arrière, Step-Down contrôlé, Leg Extension amplitude indolore, Presse). 3. Augmentez la cadence de course de +5-7%.",
     "Instabilité vraie (genou qui lâche), épanchement important -> consultation médicale.",
     "syndrome rotulien femoro patellaire mal genou coureur escaliers"),
    ("Syndrome de l'Essuie-Glace (Bandelette Ilio-Tibiale / TFL)", "Genou",
     "Douleur aiguë sur la face EXTERNE du genou apparaissant typiquement après 15-30 min de course, surtout en descente.",
     "MYTHE : Ce n'est PAS une 'friction' qu'il faut écraser violemment au foam roller ! C'est une COMPRESSION de la zone graisseuse sous la bandelette due à l'adduction excessive de hanche.\n"
     "PROTOCOLE : 1. Stoppez temporairement le foam roller direct sur la zone douloureuse (roulez plutôt fessiers/TFL haut). 2. Réduisez la course sous le seuil d'apparition de douleur (courez en légère côte = moins d'impact !). 3. Renforcez lourdement MOYEN FESSIER & HANCHES (Side Plank Star, Hip Hike, Squat Bulgare, Fentes latérales, RDL unilatéral). 4. Élargissez très légèrement l'écart des pas en course (ne pas croiser la ligne médiane).",
     "Douleur au repos permanent ou gonflement externe important -> médecin du sport.",
     "essuie glace tfl bandelette ilio tibiale douleur externe genou course"),
    ("Tendinopathie d'Achille (Moyenne vs Insertionnelle)", "Cheville & Pied",
     "Douleur et raideur matinale au tendon d'Achille (les 10 premiers pas du matin sont typiques). NE JAMAIS étirer agressivement une tendinopathie d'insertion !",
     "DISTINCTION CRUCIALE :\n"
     "• TENDINOPATHIE CORPORÉALE (2-6 cm au-dessus talon) : Isométriques 5x45s -> Protocole HSR / Alfredson sur marche (Mollets debout + assis lourd tempo 3-0-3-0, 3x/semaine, progression lente sur 12 semaines) -> Pogo Jumps progressifs.\n"
     "• TENDINOPATHIE D'INSERTION (directement sur l'os du talon) : ÉVITEZ les descentes talons sous l'horizontale et les étirements qui compriment l'insertion ! Travaillez mollets lourds AU SOL PLAT + talonnette temporaire 6-10 mm dans les chaussures.",
     "Sensation de 'coup de fouet' brutal avec craquement et impossibilité de monter sur pointe -> suspicion rupture tendon d'Achille = URGENCES immédiates !",
     "tendinite achille tendinopathie tendon talon mollet alfredson hsr"),
    ("Périostite Tibiale (Shin Splints / MTSS)", "Jambe",
     "Douleur diffuse le long du bord interne du tibia (tiers inférieur/moyen) typique des hausses brutales de course ou changements de chaussures.",
     "PROTOCOLE : 1. Coupez temporairement l'impact (remplacez par vélo/natation/elliptique 2-3 semaines) jusqu'à disparition douleur marche/pression. 2. Renforcez SOLÉAIRE (mollets assis lourds !), TIBIAL ANTÉRIEUR (Tibialis Raise 3x20), voûte plantaire (Short Foot) et hanches. 3. Vérifiez Vitamine D + Calcium + apports caloriques (éliminer RED-S). 4. Reprise ultra-progressive (+10%/sem, cadence +5%, surfaces souples).",
     "Douleur très localisée sur 1 point précis (<2 cm) qui persiste au repos/nuit ou au test du saut sur 1 pied -> risque FRACTURE DE FATIGUE tibiale = arrêt immédiat + avis médical !",
     "periostite tibiale tibia shin splints course douleur jambe fracture fatigue"),
    ("Aponévrosite / Fasciite Plantaire", "Cheville & Pied",
     "Douleur vive sous le talon (surtout aux premiers pas au saut du lit) due à une surcharge dégénérative du fascia plantaire.",
     "PROTOCOLE VALIDÉ (Rathleff 2015 — supérieur aux étirements seuls !) :\n"
     "• High-Load Strength Training : Mollets unilatéraux sur marche avec une SERVIETTE ROULÉE SOUS LES ORTEILS (mettant le gros orteil en extension dorsale), tempo lent 3-2-3 (3x12 -> 4x8 avec sac à dos lesté, 1 jour sur 2 pendant 12 semaines).\n"
     "• Compléments : Short Foot Exercise, automassage doux balle fraîche, chaussures amorties avec léger drop (éviter de marcher pieds nus sur carrelage dur en phase aiguë).",
     "Engourdissements / brûlures irradiant dans les orteils (tunnel tarsien) -> avis médical.",
     "fasciite plantaire aponevrosite talon douleur pied rathleff"),
    ("Entorse Externe de Cheville (LLE) — Prévention des Récidives", "Cheville & Pied",
     "Blessure n°1 du sport ; mal rééduquée, elle crée une instabilité chronique dans 40% des cas !",
     "1. J1-J3 : PEACE & LOVE (compression, élévation, mobilisation douce indolore en alphabet, appui progressif dès tolérable sans boiterie).\n"
     "2. J4-S3 : Restauration DORSIFLEXION (Knee-to-Wall critique !) + renforcement fibulaires/éverseurs à l'élastique + mollets bilatéraux -> unilatéraux.\n"
     "3. S3-S6 : Proprioception (équilibre 1 pied yeux ouverts -> fermés -> surface instable -> Y-Balance) + réintroduction Pogo Jumps, Skater Jumps et changements de direction avant reprise sport collectif.",
     "Critères d'Ottawa : douleur osseuse sur malléoles/5e métatarse/naviculaire OU incapacité à faire 4 pas -> radiographie obligatoire pour exclure fracture !",
     "entorse cheville lle proprioception ottawa reeducation recurrence"),
    ("Conflit Sous-Acromial & Tendinopathie de la Coiffe des Rotateurs", "Épaule",
     "Douleur antéro-latérale d'épaule à l'élévation du bras (arc douloureux 70°-120°), sur développés, dips ou en dormant sur le côté.",
     "PROTOCOLE : 1. Modifiez temporairement les exercices irritants (remplacez Bench barre -> Floor Press / Haltères prise neutre ; OHP -> Landmine Press ; stoppez tirage menton serré et dips profonds). 2. Isométriques rotation externe + abduction mur (4x30s analgésie). 3. Renforcez COIFFE EXTERNE (Rotations externes coude au corps puis 90°, Face Pull) + SCAPULA (Wall Slides, Prone Y-Raise, Pompes scapulaires) + Mobilité thoracique (Open Book).",
     "Perte soudaine de capacité à lever le bras après chute/choc (rupture transfixiante coiffe) ou douleur nocturne intense insomniante -> médecin du sport.",
     "mal epaule conflit sous acromial coiffe rotateurs sus epineux tendinite"),
    ("Tendinopathie du Long Biceps (Face Antérieure Épaule)", "Épaule",
     "Douleur précise dans la gouttière devant l'épaule lors des développés bas, écartés profonds, dips ou curls inclinés.",
     "PROTOCOLE : 1. Réduisez temporairement l'extension d'épaule en charge (dips profonds, bench prise très large, écartés bas). 2. Stabilisez l'omoplate (Face Pull, Rowing, Serratus). 3. Rechargez progressivement le biceps en isométrique puis curl marteau -> curl supination lent (3-0-3).",
     "Apparition soudaine d'une 'boule' dans le biceps ('signe de Popeye') après craquement -> rupture tendon biceps = consultation urgente.",
     "tendinite long biceps epaule avant douleur gouttiere"),
    ("Instabilité d'Épaule / Post-Luxation Gléno-Humérale", "Épaule",
     "Sensation d'appréhension ou de déboîtement en position d'armé du bras (abduction + rotation externe).",
     "PROTOCOLE (après accord médical/kiné) : Évitez temporairement la position extrême 'armé du bras' sous charge (Bench prise large, Pullover lourd, derrière la nuque). Bâtissez un blindage actif : Bottoms-Up Kettlebell Press/Carry (co-contraction réflexe maximale !), rotations internes ET externes progressives, stabilité en chaîne fermée (Pompes sur surfaces contrôlées, Bear Crawl, Landmine Press).",
     "Luxations récidivantes, engourdissements du bras -> avis chirurgien orthopédiste épaule.",
     "luxation epaule instabilite labrum coiffe bottoms up"),
    ("Épicondylite Latérale (Tennis Elbow — Face Externe Coude)", "Coude & Poignet",
     "Douleur sur la bosse externe du coude exacerbée en serrant la main, soulevant une bouteille paume vers le bas ou aux tractions pronation.",
     "PROTOCOLE VALIDÉ : 1. Passez temporairement vos tirages en prise NEUTRE ou SUPINATION (et utilisez des sangles sur tirages lourds pour décharger les extenseurs). 2. Isométriques extenseurs poignet 4x35s (poignet droit, douleur <= 3/10). 3. Excentriques lents & HSR : Reverse Wrist Curl lent 3-0-3 + Tyler Twist (FlexBar) + Pronation/Supination au marteau (3x15, 3-4x/sem pendant 8-12 semaines).",
     "Perte de sensibilité dans les doigts ou blocage du coude -> médecin.",
     "tennis elbow epicondylite douleur coude externe tyler twist avant bras"),
    ("Épitrochléite Médiale (Golfer's / Climber's Elbow — Face Interne Coude)", "Coude & Poignet",
     "Douleur interne du coude fréquente chez les pratiquants de tractions lourdes, escalade, curls et dips.",
     "PROTOCOLE : 1. Passez aux anneaux libres / prise neutre ; évitez barre droite curl (passez EZ/marteau) et réduisez le volume grip brutal. 2. Isométriques fléchisseurs poignet 4x35s. 3. Renforcement HSR progressif : Wrist Curl lent (3-0-3) + Pronation/Supination au marteau + Reverse Tyler Twist + renforcement épaules/dorsaux (souvent une épaule raide fait surcompenser le coude !).",
     "Fourmillements dans le 4e et 5e doigt (nerf ulnaire/cubital) -> évitez flexion prolongée du coude et consultez.",
     "golfers elbow epitrochleite douleur coude interne tractions escalade"),
    ("Lombalgie Aiguë (Lumbago / Tour de Rein) : quoi faire les 7 premiers jours", "Dos & Rachis",
     "90% des lombalgies aiguës sont bénignes et guérissent en 2-6 semaines — le repos strict au lit est la PIRE erreur !",
     "PROTOCOLE MODERNE :\n"
     "• J1-J3 : Restez en mouvement doux ! Marches fréquentes de 10-15 min sur plat (bras balancés), respiration diaphragmatique 90/90, position de soulagement (allongé mollets sur chaise), chaleur locale agréable.\n"
     "• J4-J14 : Réintroduction McGill Big 3 sans douleur (Curl-Up, Side Plank genoux, Bird Dog), Cat-Cow très doux, Pont fessier.\n"
     "• S3+ : Reprise charnière de hanche légère (Pull-Through -> RDL haltères) et Belt/Goblet Squat.",
     "DRAPEAUX ROUGES URGENTS : Perte contrôle vessie/intestins, anesthésie en selle (périnée), faiblesse motrice brutale d'un pied ('pied tombant'), fièvre, traumatisme violent -> URGENCES IMMÉDIATES !",
     "lombalgie lumbago mal bas du dos tour de rein mcgill marche"),
    ("Hernie Discale Lombaire & Sciatique : retour progressif au sport", "Dos & Rachis",
     "Bonne nouvelle : 66 à 85% des hernies discales se résorbent spontanément grâce au système immunitaire sans chirurgie (méta-analyse Zhong 2017) !",
     "PROTOCOLE (avec suivi kiné/médecin) :\n"
     "• Identifier la 'préférence directionnelle' (souvent légère extension type McKenzie coudes au sol si elle centralise la douleur hors de la jambe, et éviter flexion lombaire chargée matinale).\n"
     "• Marche quotidienne fractionnée + McGill Big 3 + Nerve Glide sciatique très doux (sans jamais tirer fort sur le nerf !).\n"
     "• Renforcement sans compression axiale : Belt Squat, Presse légère neutre, Rowing appui poitrine, Pull-Through, Pallof Press, avant retour progressif à la barre.",
     "Déficit moteur progressif de la jambe/pied, douleur insupportable rebelle aux antalgiques, syndrome queue de cheval -> avis neurochirurgical urgent.",
     "hernie discale sciatique lombaire dos sport mckenzie reeducation"),
    ("Syndrome du Piriforme / Fausse Sciatique (Douleur Fessière Profonde)", "Hanche & Bassin",
     "Irritation du nerf sciatique au passage sous le muscle piriforme profond de la fesse (douleur fesse/arrière cuisse aggravée assis longtemps).",
     "PROTOCOLE : 1. Évitez de rester assis >45 min (et ne gardez jamais un portefeuille dans la poche arrière !). 2. Relâchement doux balle Lacrosse (pas écrasement violent) + étirement Figure-4 allongé 2x45s. 3. RENFORCEMENT fessiers (le piriforme est souvent surmené car grand et moyen fessiers sont faibles !) : Pont fessier, Clamshell, Hip Thrust, Fentes arrière.",
     "Fourmillements persistants jusqu'au pied ou perte de force -> vérifier origine discale lombaire.",
     "piriforme fausse sciatique douleur fesse nerf sciatique"),
    ("Conflit Fémoro-Acétabulaire (CFA / Pincement Avant Hanche au Squat)", "Hanche & Bassin",
     "Douleur pincée au pli de l'aine en flexion profonde de hanche (squat bas, position assise basse).",
     "NE PAS FORCER L'ÉTIREMENT DANS LE PINCEMENT OSSEUX ! Cela irrite le labrum acétabulaire.\n"
     "PROTOCOLE : 1. Trouvez votre largeur et ouverture de pieds indolores (test Rockback) et placez une box/cale pour squatter juste au-dessus du point de pincement. 2. Banded Hip Distraction (glissement capsulaire). 3. Renforcez profondément fessiers, adducteurs, rotateurs et gainage. 4. Consultez un médecin du sport si blocages articulaires.",
     "Ressaut douloureux franc, blocage de hanche -> bilan imagerie/médecin du sport.",
     "conflit hanche cfa pincement aine squat labrum"),
    ("Pubalgie du Sportif (Douleur Aine / Adducteurs / Pubis)", "Hanche & Bassin",
     "Conflit de surcharge au carrefour pubien entre abdominaux et adducteurs, très fréquent en football, course et changements de direction.",
     "PROTOCOLE CLINIQUE (Hölmich / Aspetar) :\n"
     "• Phase 1 : Coupez temporairement frappes de balle et sprints à changements brusques ; Isométriques adducteurs ballon entre genoux (0° et 45°, 4x30s <= 3/10) + Gainage profond (Dead Bug, Pallof).\n"
     "• Phase 2 : Copenhagen Plank (genou -> cheville progressif), Adduction poulie debout, Fentes latérales contrôlées, RDL.\n"
     "• Phase 3 : Reprise course ligne droite -> courbes -> accélérations -> frappes progressives (8-12 semaines).",
     "Tuméfaction/boule à l'aine augmentant à la toux (hernie inguinale vraie) -> consultation chirurgicale.",
     "pubalgie aine adducteurs football douleur pubis copenhagen"),
    ("Claquage / Lésion Musculaire des Ischio-Jambiers (Grades 1-2)", "Cuisse",
     "Coup de poignard soudain derrière la cuisse lors d'un sprint ou d'un étirement brusque. Taux de récidive de 30% si reprise trop hâtive !",
     "PROTOCOLE ASPETAR / ASKLING :\n"
     "• J1-J4 : PEACE & LOVE, marche normale sans boiter, isométriques doux genou fléchi -> tendu (douleur <= 3/10).\n"
     "• S1-S3 : Pont ischio talons surélevés -> Slider Leg Curl -> RDL haltères léger -> Protocoles Askling L-Protocol (Extender, Diver, Glider) + Nordic progressif.\n"
     "• S3-S6 : Reprise course progressive 50% -> 70% -> 85% -> 95% -> 100% Vmax AVANT retour match (et force ischios >= 95% côté sain) !",
     "Hématome géant immédiat + creux palpable dans le muscle + incapacité totale de marcher -> suspicion rupture complète tendineuse (échographie/IRM urgente).",
     "claquage dechirure ischio jambiers cuisse sprint protocole askling"),
    ("Claquage du Mollet ('Tennis Leg' — Désinsertion Gastrocnémien Médial)", "Jambe",
     "Sensation brutale de 'coup de caillou' dans le mollet interne lors d'un démarrage explosif ou tennis/padel.",
     "PROTOCOLE : 1. J1-J5 PEACE & LOVE + talonnette temporaire bilatérale 1 cm pour soulager la marche sans boiter. 2. Dès J4-J7 : isométriques doux -> montées pointes bilatérales sol plat -> assis (soléaire !) -> unilatérales -> marche avec charge. 3. S4-S6 : Pogo Jumps doux -> footings courts -> accélérations progressives (typiquement 4 à 8 semaines).",
     "Mollet gonflé, chaud, dur, rouge et douloureux au repos sans traumatisme clair -> exclure PHLÉBITE (TVP) en urgence médicale !",
     "claquage mollet tennis leg gastrocnemien dechirure jambe"),
    ("Douleur Cervicales & Trapèzes (Raideur / Torticolis Sportif)", "Cou & Cervicales",
     "Souvent déclenchée par une mauvaise posture au développé militaire/shrugs, un blocage thoracique ou le travail prolongé sur écran.",
     "PROTOCOLE : 1. Chaleur douce 15 min + mouvements lents indolores (Cervical CARs très doux, jamais de craquement forcé !). 2. Chin Tucks (rétraction cervicale profonde 3x10x5s). 3. Mobilisez le RACHIS THORACIQUE (Open Book, Foam Roller) et renforcez trapèzes inférieurs/rhomboïdes (Face Pull, Prone Y-Raise).",
     "Douleur irradiant dans le bras avec fourmillements/perte de force main, fièvre ou traumatisme -> médecin.",
     "cervicales torticolis cou trapeze douleur raideur"),
    ("Syndrome du Canal Carpien & Douleur Poignet en Musculation", "Coude & Poignet",
     "Extension forcée répétée (pompes, Front Squat, Bench barre mal positionnée) irritant les tissus antérieurs du poignet.",
     "CORRECTIONS IMMÉDIATES : 1. Alignez la barre au développé directement au-dessus de l'os du radius (talon de la paume, jamais sur les doigts poignet cassé !). 2. Passez aux parallettes/haltères neutres sur les pompes et sangles sur Front Squat. 3. Wrist Rocks doux + renforcement fléchisseurs/extenseurs + bandes de poignets sur charges >85%.",
     "Engourdissements nocturnes pouce-index-majeur persistants -> consultation médicale (EMG).",
     "poignet douleur pompes bench front squat canal carpien"),
    ("Tendinopathie de la Patte d'Oie (Face Interne Basse Genou)", "Genou",
     "Douleur à la face interne du tibia, 3-5 cm sous l'interligne du genou (insertion couturier, gracile, semi-tendineux), fréquente chez coureurs.",
     "PROTOCOLE : Réduire volume course temporairement, éviter valgus du genou, renforcer ischio-jambiers (Leg Curl assis), adducteurs (Copenhagen progressif) et moyen fessier (Hip Hike).",
     "Douleur mécanique sur l'interligne interne avec blocage -> exclure lésion méniscale interne.",
     "patte oie douleur interne genou coureur tendinite"),
    ("Bursite Trochantérienne / Tendinopathie du Moyen Fessier (Douleur Hanche Externe)", "Hanche & Bassin",
     "Douleur sur la bosse osseuse latérale de la hanche, aggravée couché sur le côté la nuit, en montant les escaliers ou en croisant les jambes.",
     "CLÉ CRITIQUE : ÉVITEZ tout étirement en adduction (jambes croisées assis, étirement IT band debout, dormir en laissant le genou supérieur tomber) qui ÉCRASE le tendon sur le grand trochanter !\n"
     "PROTOCOLE : Dormez avec un coussin entre les genoux ; isométriques abduction douce -> Hip Hike -> Pont fessier -> Abductions progressives.",
     "Douleur intense fébrile ou chute -> avis médical.",
     "bursite trochanterienne moyen fessier hanche externe douleur nuit"),
    ("Lésion Méniscale Dégénérative vs Traumatique du Genou", "Genou",
     "Les études (Sihvonen / NEJM) montrent que la kinésithérapie structurée égale la chirurgie sur les lésions méniscales dégénératives non bloquées !",
     "PROTOCOLE CONSERVATEUR : Évitez temporairement les flexions extrêmes sous charge + torsions pivot genou fléchi ; renforcez quadriceps/ischios/fessiers dans l'amplitude indolore (Presse amplitude partielle, RDL, Hip Thrust, Vélo sans douleur, Leg Extension).",
     "VRAI blocage mécanique (impossible de tendre complètement le genou = 'anse de seau') -> avis orthopédique rapide.",
     "menisque genou douleur fissure chirurgie kine reeducation"),
    ("Fracture de Fatigue (Stress Fracture — Tibia, Métatarse, Col Fémoral)", "Os & Surcharge",
     "Micro-fissure osseuse résultant d'un déséquilibre entre impacts répétés et capacité de remodelage osseux (souvent couplée au RED-S !).",
     "GESTION : ARRÊT IMMÉDIAT de l'impact (course/sauts) et bilan médical/IRM ! Maintien cardio zéro impact (natation/aquajogging/vélo si autorisé) + renforcement haut du corps + correction nutritionnelle (calories suffisantes, 1200 mg calcium, Vitamine D3 40-60 ng/mL). Reprise course très progressive après consolidation (6-12 semaines).",
     "Douleur osseuse localisée exquise au toucher/saut unilatéral, s'aggravant au fil de la course puis présente au repos/nuit (surtout AINE/HANCHE = urgence absolue col fémoral !).",
     "fracture fatigue os tibia metatarse col femoral reds course"),
]
