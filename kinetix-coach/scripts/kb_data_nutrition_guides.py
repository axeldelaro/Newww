# -*- coding: utf-8 -*-
"""Guides scientifiques de nutrition sportive & supplémentation (70+ guides profonds) + assembleur nutrition complet (1 100+ entrées)."""
import json
from kb_data_nutrition_foods_a import FOODS_PART_A, EXTRA_FOODS_PART_A
from kb_data_nutrition_foods_b import FOODS_PART_B, CIQUAL_EXTENDED_CATALOG, CIQUAL_DEEP_CATALOG

# Format: (titre, sous_cat, resume, contenu_detaille, mots_cles)
NUTRITION_SCIENCE_GUIDES = [
    ("Créatine Monohydrate : dosage, mécanisme et mythes", "Suppléments prouvés",
     "Supplément ergonomique #1 le plus étudié au monde : +5 à 15% de force/puissance et gain de masse maigre validé.",
     "MÉCANISME : Augmente les réserves intramusculaires de phosphocréatine (PCr) de 15 à 20%, accélérant la resynthèse d'ATP durant les efforts explosifs (1-30 s) et les séries de musculation.\n"
     "DOSAGE OPTIMAL : 3 à 5 g par jour (ou 0,07 g/kg), TOUS les jours (y compris jours de repos), avec un repas contenant glucides/protéines. La phase de charge (20 g/j pendant 5 j) est optionnelle — elle sature en 5 jours au lieu de 28 jours mais peut causer des troubles digestifs.\n"
     "FORME : Créatine Monohydrate pure (label Creapure si souhaité). Les formes HCl, éthyl-ester ou tamponnées ne font pas mieux et coûtent plus cher.\n"
     "SÉCURITÉ : Totalement sûre pour des reins sains (ISSN Position Stand). L'élévation légère de la créatinine sanguine est un artefact métabolique bénin (non une atteinte rénale). Pas d'effet prouvé sur la calvitie. Bénéfices cognitifs supplémentaires prouvés en manque de sommeil.",
     "creatine monohydrate dosage charge reins force hypertrophie atp"),
    ("Caféine et performance sportive : protocole scientifique", "Suppléments prouvés",
     "Antagoniste des récepteurs à l'adénosine réduisant la perception de l'effort (RPE) et améliorant force et endurance de +2 à 4%.",
     "DOSAGE EFFICACE : 3 à 6 mg/kg de poids de corps (ex: 210 à 420 mg pour 70 kg), pris 45 à 60 minutes avant l'effort. Des doses plus faibles (1,5-3 mg/kg) sont déjà efficaces sur la vigilance et l'endurance.\n"
     "EFFETS : Augmente le recrutement moteur, diminue la douleur perçue, prolonge le temps jusqu'à épuisement.\n"
     "PIÈGE SOMMEIL : Demi-vie moyenne de 5 à 7 heures (jusqu'à 10h chez les métaboliseurs lents CYP1A2). Coupez impérativement toute caféine 8 à 10 heures avant le coucher pour ne pas détruire votre sommeil profond et votre récupération !",
     "cafeine preworkout dosage performance sommeil endurance force"),
    ("Bêta-Alanine : tampon lactique sur efforts 1 à 4 minutes", "Suppléments prouvés",
     "Précurseur limitant de la carnosine intramusculaire, tamponnant l'acidose (ions H+) lors d'efforts intenses de 60 à 240 secondes.",
     "DOSAGE : 3,2 à 6,4 g par jour en prises fractionnées (ex: 2x1,6 g ou forme à libération prolongée) pendant au moins 4 à 6 semaines pour saturer les muscles en carnosine.\n"
     "DISCIPLINES CIBLES : Cross-training, Hyrox, 400 m / 800 m / 1500 m, rameur 2000 m, natation 200-400 m, sports de combat (rounds 3-5 min), séries longues en musculation (12-20+ reps).\n"
     "EFFET SECONDAIRE BÉNIN : Paresthésie (picotements cutanés temporaires 30-60 min), totalement inoffensive, réduite en fractionnant les doses.",
     "beta alanine carnosine acidose lactique hyrox crossfit 800m"),
    ("L-Citrulline & Malate de Citrulline : congestion et oxyde nitrique", "Suppléments prouvés",
     "Précurseur direct de la L-arginine et de l'oxyde nitrique (NO) supérieur à l'arginine orale (qui subit un fort effet de premier passage hépatique).",
     "DOSAGE : 6 à 8 g de L-Citrulline pure (ou 8 à 10 g de Citrulline Malate 2:1) 45 à 60 minutes avant l'entraînement.\n"
     "EFFETS : Vasodilatation accrue (meilleure congestion), amélioration modérée du nombre de répétitions sur séries multiples (+1 à 2 reps en fin de séance), clairance accrue de l'ammoniac et réduction légère des courbatures.",
     "citrulline malate oxyde nitrique congestion preworkout vasodilatation"),
    ("Nitrates alimentaires (Betterave / Roquette) et endurance", "Suppléments prouvés",
     "Réduit le coût en oxygène d'un effort sous-maximal de 2 à 3% via la voie Nitrate -> Nitrite -> Oxyde Nitrique.",
     "DOSAGE : 350 à 500 mg de nitrates (~6-8 mmol) 2h30 à 3h avant l'effort (1 à 2 shots concentrés de jus de betterave ou 250 g d'épinards/roquette/betterave).\n"
     "RÈGLE CRUCIALE : N'utilisez PAS de bain de bouche antiseptique ! Les bactéries buccales sont indispensables pour convertir le nitrate en nitrite actif.",
     "nitrates betterave endurance vo2max oxyde nitrique economie course"),
    ("Protéines : besoins quotidiens exacts selon l'objectif (g/kg)", "Macronutriments",
     "Repères scientifiques précis (ISSN / Morton et al. 2018) pour maximiser l'hypertrophie et préserver le muscle en sèche.",
     "• Sédentaire (minimum santé) : 0,8 à 1,0 g/kg/jour.\n"
     "• Sportif d'endurance : 1,4 à 1,8 g/kg/jour (réparation tissulaire + renouvellement enzymatique).\n"
     "• Musculation / Hypertrophie en surplus ou maintien : 1,6 à 2,2 g/kg/jour (le plateau anabolique moyen se situe autour de 1,62 g/kg, 2,0 g/kg offre une marge de sécurité optimale).\n"
     "• Sèche / Déficit calorique chez athlète sec : 2,0 à 2,6 g/kg/jour (ou 2,3-3,1 g/kg de masse maigre) pour éviter le catabolisme et maximiser la satiété.\n"
     "• Senior sportif (>60 ans) : 1,6 à 2,0 g/kg avec doses de 35-40 g/repas pour vaincre la résistance anabolique.",
     "proteines besoin grammes kilo hypertrophie seche endurance leucine"),
    ("Seuil de Leucine et répartition des protéines sur la journée", "Macronutriments",
     "Chaque prise protéique doit atteindre ~2,5 à 3 g de leucine pour déclencher pleinement la voie anabolique mTORC1.",
     "FRÉQUENCE OPTIMALE : 3 à 5 prises de 0,30 à 0,55 g de protéines/kg espacées de 3 à 5 heures (soit ~25 à 45 g de protéines par repas).\n"
     "SEUIL DE LEUCINE : ~25-30 g de whey/viande/œufs suffisent ; avec des protéines végétales (moins riches en leucine), visez 35-40 g par repas ou combinez pois + riz/soja.\n"
     "MYTHE DES 30 G MAX : Le corps absorbe virtuellement 100% des protéines ingérées (étude Trommelen 2023 même à 100 g/repas), mais répartir en 3-4 prises reste plus pratique et confortable digestivement.",
     "leucine mtor synthese proteique repas repartition fenetre anabolique"),
    ("Whey vs Caséine vs Protéines Végétales : comment choisir", "Suppléments prouvés",
     "Comparatif objectif de vitesse d'absorption, digestibilité, teneur en leucine et cas d'usage.",
     "• WHEY CONCENTRATE (75-80% P) : Meilleur rapport qualité/prix, digestion rapide (60-90 min), riche en leucine (~11%).\n"
     "• WHEY ISOLATE (85-90% P) : <1% lactose et lipides, idéale si sensibilité digestive ou sèche stricte.\n"
     "• CASÉINE MICELLAIRE : Coagule doucement dans l'estomac, diffuse les acides aminés pendant 6-7h -> parfaite au coucher (30-40 g).\n"
     "• BLEND POIS + RIZ (70/30) ou SOJA ISOLAT : Efficacité hypertrophique identique à la whey à dose de leucine égale (ajouter ~20% de poudre si besoin).",
     "whey isolate caseine proteine vegetale pois riz lactose digestion"),
    ("Surplus calorique propre (Lean Bulk / Prise de masse sèche)", "Gestion du poids",
     "Maximiser le gain musculaire tout en limitant la prise de masse grasse grâce à un surplus contrôlé.",
     "SURPLUS RECOMMANDÉ : +200 à +350 kcal/jour (+10 à 15% au-dessus de la TDEE).\n"
     "RYTHME CIBLE DE PRISE DE POIDS :\n"
     "• Débutant (<1 an) : +0,5 à 1,0% du poids de corps par mois.\n"
     "• Intermédiaire (1-3 ans) : +0,25 à 0,5% par mois (~200-400 g/mois pour 75 kg).\n"
     "• Avancé : +0,15 à 0,25% par mois.\n"
     "POURQUOI PAS +1000 KCAL : La synthèse musculaire a un plafond biologique — au-delà d'un surplus modéré, l'excès est majoritairement stocké en tissu adipeux et oblige ensuite à une sèche longue.",
     "prise de masse lean bulk surplus calorique hypertrophie tdee"),
    ("Déficit calorique intelligent (Sèche sans perte de muscle)", "Gestion du poids",
     "Stratégie scientifique pour perdre du tissu adipeux tout en préservant force, performances et métabolisme.",
     "DÉFICIT OPTIMAL : -300 à -500 kcal/jour (-15 à 20% sous TDEE), visant une perte de 0,5 à 0,8% du poids de corps par semaine.\n"
     "LES 4 PILIERS ANTI-CATABOLISME :\n"
     "1. Maintenir l'intensité (charges sur la barre !) en musculation — le volume peut baisser de 20-30%, jamais l'intensité.\n"
     "2. Protéines hautes (2,0 à 2,4 g/kg).\n"
     "3. Sommeil >= 7h30 (étude Nedeltcheva : dormir 5h30 au lieu de 8h30 fait perdre 55% moins de gras et 60% plus de muscle !).\n"
     "4. NEAT élevé (8 000 à 12 000 pas/jour) et aliments à haute satiété (pommes de terre, légumes, skyr, fruits rouges).",
     "seche perte de gras deficit calorique catabolisme neat satiete"),
    ("Recomposition corporelle (Perdre du gras et gagner du muscle)", "Gestion du poids",
     "Possible et scientifiquement prouvée (Barakat et al. 2020) sous certaines conditions précises.",
     "QUI PEUT RÉUSSIR UNE RECOMPOSITION :\n"
     "• Débutants en musculation.\n"
     "• Pratiquants en reprise (mémoire musculaire via myonucléi conservés).\n"
     "• Personnes avec un taux de masse grasse modéré à élevé (>18% hommes / >26% femmes).\n"
     "• Sportifs optimisant drastiquement protéines + sommeil + surcharge progressive.\n"
     "PROTOCOLE : Calories à maintenance ou très léger déficit (-150 à -250 kcal), protéines >= 2,0 g/kg, surcharge progressive rigoureuse sur 4 séances/semaine, suivi par tour de taille + performances plutôt que la seule balance.",
     "recomposition corporelle perdre gras gagner muscle maintenance"),
    ("Diet Break et Refeed : casser la fatigue métabolique en sèche", "Gestion du poids",
     "Pauses caloriques programmées à maintenance restaurant leptine, glycogène, performances et adhérence psychologique.",
     "REFEED (1-2 jours) : Remontez à maintenance calorique exclusivement via les glucides (+100 à 200 g glucides), lipides bas, protéines stables. Idéal la veille d'une grosse séance jambes.\n"
     "DIET BREAK (1-2 semaines après 6-8 semaines de sèche — étude MATADOR) : Retour complet à maintenance calorique -> recharge le glycogène, restaure l'énergie mentale, améliore la qualité d'entraînement et prévient les craquages.",
     "refeed diet break seche leptine metabolisme adaptation"),
    ("Glucides péri-entraînement : pré, intra et post-séance", "Timing nutritionnel",
     "Carburant préférentiel des efforts modérés à intenses (>65% VO2max) et de la musculation à haut volume.",
     "• PRÉ-SÉANCE (2-3h avant) : Repas complet 1-1,5 g glucides/kg + protéines modérées, lipides/fibres modérés.\n"
     "• PRÉ-SÉANCE EXPRESS (30-60 min avant) : 30-50 g glucides rapides digestes (banane, crème de riz, galettes de riz + miel).\n"
     "• INTRA-SÉANCE : Inutile sous 60 min (un simple rinçage buccal sucré active déjà les capteurs centraux !). Pour efforts 1h-2h30 : 30-60 g/h. Pour ultra-endurance >2h30 : 60-90 g/h (voire 100-120 g/h avec entraînement digestif) en mix glucose:fructose 2:1 ou 1:0,8.\n"
     "• POST-SÉANCE : Urgent (1-1,2 g/kg/h) UNIQUEMENT si vous avez une deuxième séance dans les <8h. Sinon le total glucidique sur 24h prime.",
     "glucides timing pre entrainement intra post glycogene fructose"),
    ("Lipides essentiels et équilibre hormonal du sportif", "Macronutriments",
     "Indispensables à la synthèse des hormones stéroïdiennes (testostérone, œstrogènes), membranes cellulaires et vitamines A/D/E/K.",
     "APPORT MINIMUM SANTÉ : Ne descendez JAMAIS durablement sous 0,6 à 0,8 g/kg/jour (environ 20% des calories totales) sous peine de chute hormonale, sécheresse articulaire et fatigue.\n"
     "APPORT OPTIMAL : 0,8 à 1,2 g/kg/jour.\n"
     "QUALITÉ : Priorité aux mono-insaturés (huile d'olive, avocat, amandes) et oméga-3 (poissons gras, noix, lin). Limitez les graisses trans industrielles (0%) et l'excès d'oméga-6 raffinés (huiles tournesol/soja industrielles).",
     "lipides graisses hormones testosterone omega 3 huile olive"),
    ("Oméga-3 EPA/DHA : récupération, articulations et cerveau", "Suppléments prouvés",
     "Acides gras polyinsaturés marins modulant la résolution de l'inflammation et la sensibilité anabolique musculaire.",
     "DOSAGE EFFICACE : 2 à 3 g par jour d'EPA + DHA combinés (soit 3 portions de poissons gras/semaine ou gélules huile de poisson/algues vérifiées faible oxydation TOTOX < 15 et label Epax/IFOS).\n"
     "BÉNÉFICES SPORTIFS : Réduit la raideur articulaire, atténue les courbatures excessives, protège la santé cardiovasculaire et améliore la sensibilité musculaire aux acides aminés (études Smith et al.). Conserver les capsules au réfrigérateur !",
     "omega 3 epa dha inflammation articulations recuperation totox"),
    ("Vitamine D3 + K2 : force, immunité et os du sportif", "Micronutriments",
     "Hormone stéroïdienne déficitaire chez >75% des Européens entre octobre et avril, impactant directement force et immunité.",
     "CIBLE SANGUINE (25-OH-Vitamine D) : 40 à 60 ng/mL (100 à 150 nmol/L) — faites un bilan sanguin annuel.\n"
     "SUPPLÉMENTATION : 2 000 à 4 000 UI/jour de Vitamine D3 (cholécalciférol) prise avec un repas contenant des lipides (liposoluble !), idéalement associée à 90-100 µg de Vitamine K2 (MK-7) pour orienter le calcium vers les os et non les artères.\n"
     "IMPACT : Réduit les fractures de fatigue, les infections hivernales et soutient la fonction des fibres musculaires rapides.",
     "vitamine d d3 k2 soleil immunite fracture fatigue force hiver"),
    ("Magnésium : crampes, sommeil et production d'ATP", "Micronutriments",
     "Cofacteur de plus de 300 réactions enzymatiques dont chaque molécule d'ATP (Mg-ATP) et la relaxation neuromusculaire.",
     "BESOIN SPORTIF : 400 à 500 mg/jour (pertes sudorales augmentées à l'effort).\n"
     "MEILLEURES FORMES : Bisglycinate de magnésium (calmant, idéal le soir, excellente tolérance digestive) ou Citrate/Malate (énergie journée). ÉVITEZ l'oxyde et le magnésium marin bon marché (absorption <4%, effet laxatif !).\n"
     "SOURCES ALIMENTAIRES : Graines de courge, chanvre, cacao pur, amandes, épinards, chocolat noir 85%, eau Hépar/Rozana.",
     "magnesium bisglycinate crampes sommeil atp recuperation fatigue"),
    ("Hydratation & Électrolytes : calcul exact des pertes sudorales", "Hydratation",
     "Dès -2% de perte hydrique corporelle, la puissance aérobie et les fonctions cognitives chutent de 5 à 15%.",
     "HYDRATATION QUOTIDIENNE : 35 à 40 ml/kg/jour hors sport + compensation de 125 à 150% du poids perdu pendant la séance.\n"
     "TEST DU TAUX DE SUDATION : Pesez-vous nu avant/après 1h d'effort. Chaque kg perdu = 1 L de sueur (+ ce que vous avez bu).\n"
     "SODIUM (ÉLECTROLYTE CLÉ) : La sueur contient 500 à 1 500 mg de sodium par litre ! Boire uniquement de l'eau plate en ultra-endurance chaude provoque l'hyponatrémie dangereuse. Ajoutez 500-800 mg sodium/L (soit 1,2 à 2 g de sel/L) sur efforts >1h15 ou forte chaleur.",
     "hydratation electrolytes sodium sueur crampes hyponatremie eau"),
    ("Collagène + Vitamine C : protocole tendineux Keith Baar", "Suppléments prouvés",
     "Seul protocole nutritionnel validé pour stimuler la synthèse de collagène dans les tendons et ligaments.",
     "PROTOCOLE EXACT (Shaw & Baar, 2017) :\n"
     "• Prendre 15 g de peptides de collagène hydrolysé (ou gélatine) + 50 à 100 mg de vitamine C (un verre de jus d'orange ou acérola) 45 à 60 minutes AVANT une courte séance de mise en charge tendineuse (5-10 min d'isométriques/sauts légers/rééducation).\n"
     "POURQUOI : Les tendons sont peu vascularisés ; ils absorbent les acides aminés (glycine, proline) par diffusion mécanique au moment de la mise en tension !",
     "collagene vitamine c tendon tendinite ligament keith baar reeducation"),
    ("Fer et Ferritine chez le sportif d'endurance", "Micronutriments",
     "Essentiel au transport d'oxygène (hémoglobine) et à la respiration mitochondriale ; carence fréquente chez les coureurs et femmes.",
     "MARQUEUR CLÉ : Dosez la FERRITINE (réserves) et la CRP, pas seulement l'hémoglobine ! Une ferritine < 30-35 ng/mL altère déjà la VO2max chez l'athlète même sans anémie franche.\n"
     "ABSORPTION : Le fer héminique (viande rouge, abats, moules) est absorbé à 20-25% vs 3-8% pour le fer végétal. Associez toujours vitamine C (citron/poivron) et éloignez thé/café d'au moins 1h autour des repas riches en fer ! Ne supplémentez JAMAIS sans bilan sanguin.",
     "fer ferritine anemie endurance fatigue course femme heminique"),
    ("Zinc : immunité, réparation cellulaire et axe hormonal", "Micronutriments",
     "Minéral essentiel à la synthèse protéique, cicatrisation, fonction thyroïdienne et maintien d'une testostérone normale.",
     "BESOIN : 11 à 15 mg/jour (jusqu'à 20 mg chez les athlètes en fort volume).\n"
     "SOURCES : Huîtres (champion absolu), bœuf, graines de courge, lentilles, œufs.\n"
     "PRÉCAUTION : En supplément, préférez bisglycinate ou picolinate 15 mg/j max. Des doses >40 mg/j sur la durée induisent une carence en cuivre !",
     "zinc testosterone immunite recuperation huitres cuivre"),
    ("Bicarbonate de sodium : tampon anaérobie de compétition", "Suppléments prouvés",
     "Tampon extracellulaire améliorant les performances maximales de 1 à 8 minutes (aviron, 800-1500m, natation, CrossFit, boxe).",
     "DOSAGE : 0,2 à 0,3 g/kg de poids de corps 90 à 150 minutes avant l'effort, en gélules gastro-résistantes ou hydrogel (type Maurten Bicarb) avec un repas glucidique.\n"
     "ATTENTION : Risque important de diarrhée/ballonnements avec la poudre brute -> testez TOUJOURS plusieurs fois à l'entraînement, jamais le jour J !",
     "bicarbonate sodium tampon lactique competition 800m rameur"),
    ("Carb Loading (Recharge glucidique) avant Marathon / Trail / Cyclosportive", "Endurance",
     "Sature les réserves de glycogène musculaire (+30 à 50%) pour repousser le 'mur' du 30e km.",
     "PROTOCOLE MODERNE (36-48h avant départ — sans phase de déplétion archaïque) :\n"
     "• Visez 8 à 10 g de glucides/kg/jour pendant les 2 jours précédant l'épreuve (ex: 560-700 g/j pour 70 kg).\n"
     "• Réduisez nettement fibres (légumes crus, légumineuses, pain complet) et lipides pour éviter tout trouble digestif en course.\n"
     "• Privilégiez riz blanc, pâtes blanches, pain blanc, pommes de terre, compotes, bananes, jus, miel.\n"
     "• Gain de 1 à 1,5 kg sur la balance = NORMAL et positif (1 g glycogène stocke ~3 g d'eau intra-musculaire !).",
     "carb loading recharge glucidique marathon trail glycogène mur 30km"),
    ("Entraînement du système digestif (Gut Training) en endurance", "Endurance",
     "L'intestin est un organe entraînable capable de doubler sa capacité d'absorption glucidique à l'effort.",
     "POURQUOI : Les transporteurs SGLT1 (glucose, ~60 g/h) et GLUT5 (fructose, ~30-60 g/h) s'expriment davantage lorsqu'ils sont stimulés régulièrement à l'entraînement.\n"
     "PROTOCOLE : Sur 6-8 semaines avant objectif, pratiquez au moins 1-2 séances/semaine à allure cible en ingérant progressivement 40 -> 60 -> 80 -> 90 g glucides/h avec les produits exacts du jour de course.",
     "gut training digestion gel endurance sglt1 glut5 marathon trail"),
    ("Alimentation végétarienne et végane de haut niveau", "Régimes spécifiques",
     "Atteindre 100% du potentiel force/hypertrophie/endurance avec une alimentation végétale bien structurée.",
     "POINTS CLÉS INCONTOURNABLES :\n"
     "1. Protéines totales : viser +10-15% (1,8-2,2 g/kg) pour compenser la digestibilité et la leucine légèrement inférieures ; privilégier tofu, tempeh, PST, seitan, lentilles, edamame, protéines pois+riz.\n"
     "2. Vitamine B12 : supplémentation OBLIGATOIRE (cyanocobalamine 1000 µg 2x/semaine).\n"
     "3. Oméga-3 DHA/EPA : huile d'algue directe (la conversion ALA -> DHA est <5%).\n"
     "4. Créatine (3-5 g/j) : gain encore PLUS spectaculaire chez les végans car leurs réserves de base sont 20-30% plus basses !\n"
     "5. Surveiller fer, zinc, iode, calcium et vitamine D.",
     "vegan vegetarien musculation b12 creatine leucine proteines vegetales"),
    ("Jeûne intermittent (16/8) et musculation : ce que dit la science", "Régimes spécifiques",
     "Outil pratique de contrôle calorique, mais sous-optimal pour l'hypertrophie maximale comparé à 3-4 prises protéiques.",
     "CE QUE MONTRENT LES ÉTUDES (Tinsley, Moro) :\n"
     "• Pour la perte de gras : résultats identiques à un déficit classique à calories et protéines égales.\n"
     "• Pour la prise de masse maximale : concentrer 180 g de protéines et 3200 kcal sur 8h limite le nombre de pics de synthèse protéique et nuit au confort digestif.\n"
     "• Si vous pratiquez le 16/8 : placez l'entraînement en fin de fenêtre de jeûne (ou prenez 25 g whey avant/après) et assurez-vous d'atteindre 1,8-2,2 g/kg de protéines dans la fenêtre alimentaire.",
     "jeune intermittent fasting 16/8 musculation seche hypertrophie"),
    ("Nutrition et Sommeil : optimiser la récupération nocturne", "Récupération",
     "Les choix alimentaires du soir influencent directement la latence d'endormissement et l'architecture du sommeil profond.",
     "• GLUCIDES LE SOIR : Un dîner riche en glucides complexes (riz, patate douce, avoine) 2-3h avant le coucher augmente le rapport tryptophane/LNAA au cerveau et favorise sérotonine et mélatonine.\n"
     "• ALIMENTS PRO-SOMMEIL PROUVÉS : 2 kiwis 1h avant coucher (étude Lin), jus de cerise griotte Montmorency, 30-40 g caséine/skyr, bisglycinate de magnésium (300 mg), glycine (3 g).\n"
     "• ENNEMIS DU SOMMEIL : Caféine tardive, alcool (détruit le sommeil paradoxal et fait chuter la HRV), repas très gras/épicé <90 min avant le lit.",
     "sommeil nutrition soir glucides kiwi cerise caseine glycine alcool"),
    ("Index Glycémique (IG) vs Charge Glycémique (CG)", "Macronutriments",
     "Pourquoi la charge glycémique et la matrice du repas comptent bien plus que l'IG isolé d'un aliment.",
     "DÉFINITIONS : L'IG (0-100) mesure la vitesse d'élévation glycémique pour 50 g de glucides d'un aliment seul à jeun. La Charge Glycémique (CG = IG x grammes de glucides de la portion / 100) reflète l'impact RÉEL (ex: la pastèque a un IG de 72 mais une CG minuscule de 5 !).\n"
     "EN PRATIQUE : Ajouter protéines, fibres, légumes et un filet d'huile ou vinaigre abaisse massivement la réponse glycémique de n'importe quel féculent. L'IG haut est un OUTIL précieux autour de l'effort, pas un poison.",
     "index glycemique charge glycemique insuline glucides repas"),
    ("Fibres alimentaires et microbiote de l'athlète", "Digestion & Santé",
     "25 à 38 g/jour d'un mix fibres solubles et insolubles optimisent satiété, immunité (acides gras à chaîne courte) et glycémie.",
     "• FIBRES SOLUBLES (avoine, pommes, chia, légumineuses, psyllium) : forment un gel ralentissant l'absorption du glucose et nourrissant les bactéries productrices de butyrate.\n"
     "• FIBRES INSOLUBLES (légumes, céréales complètes) : accélèrent le transit.\n"
     "• TIMING SPORTIF : Réduisez les fibres dans les 2-3h précédant une séance intense ou une compétition pour prévenir ballonnements et urgences gastriques.",
     "fibres microbiote digestion prebiotiques probiotiques transit"),
    ("Électrolytes complets : Sodium, Potassium, Magnésium, Calcium", "Hydratation",
     "L'équilibre hydrominéral gouverne la contraction musculaire, l'influx nerveux et la prévention des crampes.",
     "• SODIUM : 2 000 à 4 000+ mg/jour chez le sportif qui transpire beaucoup (contre 2 300 mg chez le sédentaire).\n"
     "• POTASSIUM : 3 500 à 4 700 mg/jour (pommes de terre, avocat, bananes, épinards, abricots secs, légumineuses) — équilibre le sodium et protège la tension.\n"
     "• CALCIUM : 1 000 à 1 200 mg/jour (produits laitiers, sardines avec arêtes, eaux minérales calciques, tofu au calcium) — contraction musculaire et prévention fracture de fatigue.\n"
     "• MAGNÉSIUM : 400 à 500 mg/jour.",
     "electrolytes sodium potassium calcium magnesium crampes contraction"),
    ("Alcool et performance sportive : impact réel chiffré", "Récupération",
     "L'éthanol altère directement la synthèse protéique myofibrillaire, le sommeil et la récupération hormonale.",
     "DONNÉES SCIENTIFIQUES (Parr et al. 2014) :\n"
     "• Une consommation élevée post-entraînement (>1 g/kg, ~5-6 verres) réduit la synthèse protéique musculaire de 24 à 37%, même avec un apport protéique suffisant !\n"
     "• Même 2 verres le soir abaissent la qualité du sommeil (REM), augmentent la FC nocturne (+4-8 bpm) et font chuter la VFC/HRV.\n"
     "• STRATÉGIE PRAGMATIQUE : Privilégiez bières 0,0%, limitez à 1-2 verres espacés des grosses séances, buvez 1 grand verre d'eau + électrolytes par verre d'alcool et mangez des protéines avant.",
     "alcool biere vin recuperation synthese proteique sommeil hrv"),
    ("Glycine : collagène, sommeil profond et longévité", "Suppléments prouvés",
     "Acide aminé semi-essentiel bon marché aux effets prouvés sur le sommeil et la synthèse du collagène/glutathion.",
     "DOSAGE : 3 g de glycine pure 30-45 minutes avant le coucher (goût légèrement sucré agréable dans une tisane).\n"
     "EFFETS : Abaisse légèrement la température corporelle centrale (signal physiologique d'endormissement), réduit la somnolence matinale et fournit la brique principale du collagène tendineux (33% des acides aminés du collagène).",
     "glycine sommeil collagene glutathion recuperation"),
    ("Ashwagandha (KSM-66) : gestion du stress et cortisol", "Suppléments prouvés",
     "Plante adaptogène réduisant le stress perçu et le cortisol basal, avec gains modestes documentés en force et VO2max.",
     "DOSAGE : 300 à 600 mg/jour d'extrait standardisé (KSM-66 ou Sensoril) pendant des cycles de 8 à 12 semaines suivis de 2-4 semaines de pause.\n"
     "PRÉCAUTIONS : Éviter en cas d'hyperthyroïdie, grossesse ou maladie auto-immune. Surveiller l'apparition rare d'anhédonie (émoussement émotionnel) qui impose l'arrêt.",
     "ashwagandha ksm66 cortisol stress adaptogene sommeil force"),
    ("L-Théanine + Caféine : focus laser sans nervosité", "Suppléments prouvés",
     "Synergie nootropique issue du thé vert atténuant les tremblements et le crash de la caféine.",
     "PROTOCOLE : 100 à 200 mg de L-Théanine associés à 100-200 mg de caféine (ratio 1:1 à 2:1) 45 min avant une séance technique exigeante ou un entraînement tardif modéré.",
     "theanine cafeine concentration focus preworkout nootropique"),
    ("BCAA vs EAA vs Protéines complètes : ne gaspillez plus", "Suppléments prouvés",
     "Pourquoi les BCAA isolés sont obsolètes dès que vos apports protéiques quotidiens sont corrects.",
     "SCIENCE (Wolfe 2017) : Les 3 BCAA (leucine, isoleucine, valine) activent le signal mTOR mais ne peuvent PAS construire de muscle sans les 6 autres acides aminés essentiels (EAA) !\n"
     "VERDICT : Remplacez les BCAA par 25-30 g de Whey ou un repas complet (plus efficace et moins cher). Les EAA complets (10 g) n'ont un léger intérêt que lors d'une séance longue totalement à jeun.",
     "bcaa eaa whey acides amines essentiels marketing"),
    ("Pré-workout maison optimal (efficace et 4x moins cher)", "Suppléments prouvés",
     "Formule transparente aux dosages cliniques exacts sans 'proprietary blends' sous-dosés.",
     "RECETTE PRÉ-SÉANCE (45 min avant) :\n"
     "• Caféine : 150-250 mg (ou double expresso).\n"
     "• L-Citrulline pure : 6-8 g.\n"
     "• Sel marin (sodium) : 1 à 1,5 g dans 500 ml d'eau (congestion & volume plasmatique immédiats).\n"
     "• Optionnel glucides : 30 g crème de riz ou banane.\n"
     "• (Créatine 5 g + Bêta-alanine 3,2 g fonctionnent par saturation chronique — peu importe l'heure !).",
     "preworkout maison citrulline cafeine sel sodium congestion"),
    ("Stratégie Nutritionnelle en Période de Blessure", "Récupération",
     "Éviter l'erreur classique de trop couper les calories pendant une immobilisation, qui accélère l'atrophie musculaire.",
     "RÈGLES D'OR PENDANT UNE BLESSURE :\n"
     "1. CALORIES : Restez à maintenance (ou léger surplus si chirurgie/fracture : la cicatrisation augmente le métabolisme de +10-20% !).\n"
     "2. PROTÉINES TRÈS HAUTES : 2,0 à 2,5 g/kg avec doses de 35-40 g/repas pour contrer la résistance anabolique d'immobilisation.\n"
     "3. CRÉATINE (5 g/j) : Réduit significativement la perte de masse et de force pendant l'immobilisation.\n"
     "4. COLLAGÈNE (15 g + vit C) + OMÉGA-3 (3 g) + VITAMINE D + CALCIUM (si os).",
     "blessure nutrition immobilisation atrophie collagene creatine cicatrisation"),
    ("RED-S (Déficit Énergétique Relatif dans le Sport)", "Santé & Prévention",
     "Syndrome grave de sous-alimentation chronique chez les sportifs (femmes et hommes) détruisant os, hormones et performances.",
     "SIGNES D'ALERTE : Fatigue chronique, blessures à répétition (périostites, fractures de fatigue), aménorrhée / cycles irréguliers chez la femme, baisse libido/testostérone chez l'homme, frilosité, stagnation ou régression malgré un gros volume.\n"
     "SEUIL CRITIQUE : Disponibilité énergétique (EA = [Apport kcal - Dépense sport] / kg masse maigre) < 30 kcal/kg FFM/j. Cible santé : >= 45 kcal/kg FFM/j. Solution : remonter les calories et glucides sans délai et consulter un médecin du sport.",
     "reds deficit energetique amenorrhee fracture fatigue hormones surentrainement"),
    ("Cycle menstruel et nutrition/entraînement de la sportive", "Nutrition féminine",
     "Adapter apports en fer, glucides, hydratation et gestion de la fatigue selon les phases folliculaire et lutéale.",
     "• PHASE MENSTRUELLE : Pertes en fer accrues -> accentuer fer héminique + vitamine C + oméga-3 / gingembre anti-inflammatoires.\n"
     "• PHASE FOLLICULAIRE (J1-J14) : Bonne sensibilité à l'insuline et utilisation des glucides, souvent propice aux grosses charges et records.\n"
     "• PHASE LUTÉALE (J15-J28) : Température basale +0,3-0,5°C, métabolisme de repos légèrement augmenté (+50-150 kcal), catabolisme protéique un peu plus marqué -> augmenter légèrement protéines (+10-15 g/j), hydratation/sodium et glucides péri-séance si fatigue ressentie.\n"
     "RÈGLE : Individualisez toujours selon VOS symptômes (l'auto-régulation prime sur tout dogme rigide).",
     "cycle menstruel femme sport nutrition fer luteale folliculaire"),
    ("Calculer précisément sa TDEE (Dépense Énergétique Totale)", "Gestion du poids",
     "Formule de Mifflin-St Jeor + multiplicateur d'activité + calibration réelle sur 14 jours.",
     "1. MÉTABOLISME DE BASE (BMR Mifflin-St Jeor) :\n"
     "• Homme : 10 x poids(kg) + 6,25 x taille(cm) - 5 x âge + 5.\n"
     "• Femme : 10 x poids(kg) + 6,25 x taille(cm) - 5 x âge - 161.\n"
     "2. FACTEUR D'ACTIVITÉ : Sédentaire x1,2 | 1-3 séances x1,375 | 3-5 séances x1,55 | 6-7 séances/métier actif x1,725 | Athlète bi-quotidien x1,9.\n"
     "3. CALIBRATION RÉELLE : Toute formule a ±10-15% de marge. Pesez-vous 4-7x/semaine à jeun, calculez la moyenne hebdomadaire sur 2 semaines et ajustez de ±150-200 kcal selon l'évolution réelle !",
     "tdee calories calcul bmr mifflin st jeor metabolisme base"),
    ("Satiété en sèche : comment manger beaucoup pour peu de calories", "Gestion du poids",
     "L'approche 'Volume Eating' basée sur l'indice de satiété (Holt 1995) pour réussir sa sèche sans souffrir de faim.",
     "LES CHAMPIONS DE SATIÉTÉ PAR CALORIE :\n"
     "1. Pommes de terre bouillies (indice 323% — 3x plus rassasiantes que le pain blanc !).\n"
     "2. Poissons blancs maigres (cabillaud, colin) et blancs de poulet.\n"
     "3. Porridge d'avoine + pommes/oranges/fraises/framboises.\n"
     "4. Soupes de légumes, courgettes, brocolis, champignons, concombres.\n"
     "5. Skyr 0%, fromage blanc 0%, œufs entiers, légumineuses.\n"
     "ASTUCE : Mangez lentement (20 min), commencez par protéines + légumes, limitez les calories liquides (jus, huiles non mesurées).",
     "satiete faim seche volume eating pomme de terre proteines fibres"),
    ("Meal Prep sportif en 90 minutes pour toute la semaine", "Organisation",
     "Système simple en 4 blocs pour sécuriser 90% de ses apports sans passer sa vie en cuisine.",
     "MÉTHODE 4 BLOCS (Dimanche 90 min) :\n"
     "• BLOC PROTÉINES (2 sources) : 1 kg blancs de poulet marinés au four + 800 g chili bœuf 5% ou œufs durs.\n"
     "• BLOC GLUCIDES (2 sources) : 500 g riz basmati (cuiseur) + 1 kg patates douces rôties au four.\n"
     "• BLOC LÉGUMES : 2 plaques de brocolis/courgettes/poivrons rôtis + sachets salades/épinards prêts.\n"
     "• BLOC SAUCES & COLLATIONS : 1 sauce yaourt grec-moutarde + 4 pots overnight oats / skyr.\n"
     "RÈGLE : Variez les épices (curry, mexicain, soja-gingembre, herbes de Provence) pour ne jamais vous lasser !",
     "meal prep organisation cuisine semaine tupperware batch cooking"),
    ("Nutrition en Déplacement / Restaurant sans ruiner ses objectifs", "Organisation",
     "Règles simples pour gérer restaurants, voyages d'affaires et vacances tout en gardant ses résultats.",
     "• RESTAURANT : Choisissez une pièce de viande/poisson grillée + féculent simple (riz/pomme de terre) + légumes, sauce à part. Évitez le panier de pain + beurre en entrée.\n"
     "• SUPERMARCHÉ EXPRESS : Blanc de poulet rôti, thon/maquereau, skyr, galettes de riz, bananes, amandes, salade prête.\n"
     "• LOI DU 80/20 : 1 repas social libre sur 21 repas/semaine ne ruine JAMAIS une progression ; c'est le 'tout ou rien' qui fait échouer.",
     "restaurant voyage deplacement nutrition vacances 80 20"),
    ("Petit-déjeuner sportif : sucré vs salé vs pas de petit-déjeuner", "Timing nutritionnel",
     "Adapter le premier repas de la journée à votre horaire d'entraînement et votre profil glycémique.",
     "• PETIT-DÉJEUNER SALÉ (œufs + avocat/fromage + pain complet levain) : Glycémie ultra-stable, zéro fringale à 11h, focus cognitif optimal.\n"
     "• PETIT-DÉJEUNER SUCRÉ PROTÉINÉ (porridge avoine + whey/skyr + myrtilles + graines) : Parfait si vous vous entraînez le matin.\n"
     "• ERREUR À ÉVITER : Jus d'orange + pain blanc + confiture/viennoiserie (0 protéine, pic glycémique suivi d'hypoglycémie réactionnelle à 10h30).",
     "petit dejeuner matin sale sucre oeufs porridge glycemie"),
    ("Alimentation anti-inflammatoire du sportif", "Récupération",
     "Réduire l'inflammation chronique bas-grade sans bloquer l'inflammation aiguë utile à l'adaptation musculaire.",
     "NUANCE SCIENTIFIQUE IMPORTANTE : L'inflammation aiguë juste après la séance déclenche l'adaptation (évitez les mégadoses de vitamines C/E >1000 mg ou AINS juste après l'entraînement !).\n"
     "EN REVANCHE, l'alimentation anti-inflammatoire de fond protège tendons, vaisseaux et articulations :\n"
     "• Poissons gras 3x/semaine, huile d'olive extra vierge, noix, baies (myrtilles, cerises), légumes crucifères, curcuma+poivre, gingembre, thé vert.\n"
     "• Réduire : sucres ultra-transformés, huiles raffinées oméga-6 en excès, alcool, fritures.",
     "anti inflammatoire alimentation tendons articulations omega 3 curcuma baies"),
    ("FODMAPs et troubles digestifs du coureur", "Digestion & Santé",
     "Prévenir points de côté, ballonnements et diarrhées du coureur grâce au protocole pauvre en FODMAPs pré-course.",
     "POURQUOI : À l'effort, le sang quitte l'intestin (-60 à -80%) vers les muscles, rendant la fermentation des FODMAPs très mal tolérée.\n"
     "À ÉVITER 24-36h AVANT COMPÉTITION : Ail, oignon, pommes, poires, blé complet en excès, légumineuses, lait (lactose), édulcorants polyols (sorbitol/maltitol).\n"
     "ALIMENTS SÛRS PRÉ-COURSE : Riz blanc, banane peu mûre, myrtilles, flocons d'avoine, œufs, poulet, pommes de terre, pain au levain sans excès, lait sans lactose.",
     "fodmap digestion course diarrhee coureur ventre marathon"),
    ("Vitamines du groupe B : le moteur énergétique cellulaire", "Micronutriments",
     "B1, B2, B3, B5, B6, B9 et B12 convertissent glucides, lipides et acides aminés en ATP utilisable.",
     "• B1 (Thiamine) : Métabolisme glucidique (porc, avoine, graines tournesol).\n"
     "• B6 (Pyridoxine) : Métabolisme protéique et neurotransmetteurs (poulet, saumon, banane, pomme de terre).\n"
     "• B9 (Folates) : Synthèse ADN et globules rouges (légumes verts foncés, lentilles).\n"
     "• B12 (Cobalamine) : Système nerveux et globules rouges — uniquement dans les produits animaux (supplément obligatoire pour végans/végétariens stricts).",
     "vitamines b b12 b6 b9 energie metabolisme atp"),
    ("Vitamine C : collagène, fer et immunité sans bloquer l'hypertrophie", "Micronutriments",
     "Cofacteur indispensable de la synthèse du collagène et de l'absorption du fer, à doser intelligemment.",
     "BESOIN SPORTIF : 150 à 250 mg/jour facilement couverts par l'alimentation (1 poivron rouge + 2 kiwis = 300+ mg !).\n"
     "ATTENTION AUX MÉGADOSES : Des doses de 1 000 mg juste autour de la musculation/endurance peuvent émousser légèrement la signalisation mitochondriale/hypertrophique (études Paulsen 2014). Privilégiez les fruits/légumes frais ou 100-200 mg loin des séances.",
     "vitamine c collagene immunite antioxydant poivron kiwi"),
    ("Électrolytes & Crampes musculaires : mythes vs réalité", "Hydratation",
     "Les crampes à l'effort ont une double origine : fatigue neuromusculaire (principale) et déplétion hydrosodée.",
     "1. CAUSE #1 : Fatigue neuromusculaire locale (réflexe moteur hyperactif quand un muscle est poussé au-delà de sa préparation spécifique) -> prévention par renforcement musculaire spécifique + pacing réaliste.\n"
     "2. CAUSE #2 : Pertes massives en eau + sodium chez les 'salty sweaters' (traces blanches de sel sur les vêtements) -> 700-1200 mg sodium/L.\n"
     "3. ASTUCE TERRAIN : Un shoot de jus de cornichon / vinaigre / moutarde déclenche un réflexe nerveux oropharyngé (récepteurs TRP) stoppant certaines crampes en 60-90 secondes !",
     "crampes sodium magnesium fatigue neuromusculaire jus cornichon"),
    ("Lecture d'étiquettes nutritionnelles : déjouer les pièges marketing", "Organisation",
     "5 règles en 15 secondes pour évaluer n'importe quel produit au supermarché.",
     "1. LISTE D'INGRÉDIENTS : Classés par ordre décroissant de poids. Fuyez si sucre/sirop de glucose/huile de palme figurent dans les 3 premiers.\n"
     "2. RATIO PROTÉINES/CALORIES : Un produit dit 'Protéiné' doit idéalement contenir >= 10 g de protéines pour 100 kcal (ou >= 20% des calories en protéines).\n"
     "3. SUCRES AJOUTÉS : Comparez 'Glucides' et 'dont sucres' (dans un yaourt nature, 4-5 g = lactose naturel normal ; 15 g = 10 g de sucre ajouté).\n"
     "4. FIBRES : Pour pains/céréales, visez au moins 6 g fibres / 100 g.\n"
     "5. PORTION PIÈGE : Toujours comparer les valeurs POUR 100 G.",
     "etiquette nutritionnelle marketing proteines sucres fibres supermarche"),
    ("Nutrition et Force Maximale (Powerlifting / Haltérophilie)", "Sports spécifiques",
     "Maximiser les réserves ATP-PC, le glycogène local et le levier corporel pour les charges maximales.",
     "• GLUCIDES : 4 à 6 g/kg/j pour alimenter des séances longues de 90-120 min (le manque de glycogène fait chuter la vitesse de barre et le recrutement nerveux).\n"
     "• PROTÉINES : 1,8 à 2,2 g/kg/j.\n"
     "• SUPPLÉMENTS ROIS : Créatine monohydrate 5 g/j + Caféine 3-5 mg/kg sur les grosses séances + Sodium 1-1,5 g pré-séance.\n"
     "• GESTION CATÉGORIE DE POIDS : Privilégiez une descente lente en graisse corporelle ; évitez les water-cuts extrêmes sans expérience (perte de force et risque rénal).",
     "powerlifting force nutrition glucides creatine sodium categorie poids"),
    ("Nutrition Hyrox & Sports Hybrides (Force + Endurance)", "Sports spécifiques",
     "Concilier développement musculaire et gros volumes de course à pied sans basculer en déficit énergétique.",
     "• BESOIN ÉNERGÉTIQUE ÉLEVÉ : Souvent 3 000 à 4 200 kcal/j chez un athlète hybride — le sous-apport glucidique est l'erreur n°1.\n"
     "• GLUCIDES : 5 à 8 g/kg/j (périodisés : plus hauts les jours de course longue/Hyrox, plus modérés les jours de repos).\n"
     "• PROTÉINES : 1,8 à 2,2 g/kg/j (récupération musculaire + prévention catabolisme).\n"
     "• STACK EFFICACE : Créatine 5 g/j + Bêta-alanine 4-6 g/j (effort lactique stations) + Nitrates (betterave) + Caféine + 40-60 g glucides pendant la course si >70 min.",
     "hyrox hybride nutrition glucides beta alanine creatine endurance"),
    ("Nutrition Sports de Combat (Boxe, MMA, JJB, Judo)", "Sports spécifiques",
     "Explosivité répétée sur rounds intenses, récupération nerveuse et gestion intelligente du poids de combat.",
     "• FILIÈRE DOMINANTE : Glycolytique lactique + alactique -> glucides suffisants (5-7 g/kg/j) + Bêta-alanine (tampon acidose des rounds de 3-5 min) + Créatine (attention +1-2% d'eau si pesée limite).\n"
     "• POST-PESÉE : Réhydratation prioritaire (eau + sodium 1000 mg/L + glucose) puis glucides digestes faibles en fibres (riz blanc, compotes, pâtes blanches) sur 12-24h.",
     "boxe mma jjb judo nutrition combat poids beta alanine"),
    ("Nutrition Sports Collectifs (Football, Rugby, Basketball, Handball)", "Sports spécifiques",
     "Sprints répétés de haute intensité sur 60-90 minutes exigeant des réserves pleines de glycogène et d'électrolytes.",
     "• J-1 & JOUR DE MATCH : 6-8 g glucides/kg à J-1 ; repas pré-match 3h avant (riz/pâtes blanches + poulet/poisson maigre, peu de gras/fibres).\n"
     "• MI-TEMPS : 20-30 g glucides rapides (gel, compote, banane, boisson isotonique) + 250-400 ml eau/électrolytes pour maintenir la vitesse de sprint dans les 20 dernières minutes !\n"
     "• POST-MATCH : 1 g glucides/kg + 0,4 g protéines/kg + 150% pertes hydriques dans les 2h.",
     "football rugby basket handball match mi-temps nutrition glucides"),
    ("Nutrition du coureur de 10 km, Semi-Marathon et Marathon", "Sports spécifiques",
     "Guide pratique par distance pour ne jamais manquer d'énergie ni subir de troubles digestifs.",
     "• 10 KM (<50 min) : Aucun gel nécessaire pendant la course ; petit-déjeuner digeste 2h30-3h avant + caféine optionnelle.\n"
     "• SEMI-MARATHON (1h15-2h) : 1 à 2 gels (30-45 g glucides/h) aux km 7-8 et 14-15 + gorgées d'eau aux ravitos.\n"
     "• MARATHON (2h30-4h30) : Carb-loading 48h avant + 60 à 90 g glucides/h dès le 25e-30e minute (NE PAS attendre d'avoir faim !) + 500-800 mg sodium/h.",
     "marathon semi 10km course ravitaillement gel glucides"),
    ("Nutrition Ultra-Trail & Cyclisme Longue Distance (>4h)", "Sports spécifiques",
     "Maintenir un flux calorique continu, alterner sucré/salé et éviter la saturation gustative.",
     "• APPORT HORAIRE : 60-90 g glucides/h (jusqu'à 100-120 g/h en cyclisme élite) + 500-1000 mg sodium/h + 500-800 ml eau/h selon météo.\n"
     "• ANTI-ÉCŒUREMENT AU-DELÀ DE 5H : Alternez gels/boissons neutres (maltodextrine) avec du solide salé (bouillon chaud, riz salé/rice cakes, pommes de terre salées, bretzels, fromage, jambon).\n"
     "• PROTÉINES EN ULTRA (>6h) : Ajouter 5-10 g protéines ou acides aminés toutes les 2-3h limite la dégradation musculaire.",
     "ultra trail cyclisme longue distance ravitaillement sale bouillon"),
    ("Sodium et Performance en Musculation : l'arme secrète", "Hydratation",
     "Pourquoi 1 à 1,5 g de sel 30-45 min avant une séance améliore congestion, force et contraction nerveuse.",
     "MÉCANISME : Le sodium augmente le volume plasmatique sanguin, facilite le co-transport SGLT1 du glucose et est l'ion déclencheur du potentiel d'action musculaire.\n"
     "PRATIQUE : Les sportifs mangeant très 'clean' (aliments bruts non transformés) sont souvent TROP BAS en sodium ! Salez normalement vos plats et testez 1 g de sel marin dans 500 ml d'eau 40 min avant séance (sauf hypertension artérielle médicale).",
     "sodium sel congestion pompe musculation force volume plasmatique"),
    ("Synthèse Protéique Nocturne : le protocole pré-sommeil", "Timing nutritionnel",
     "Études Van Loon / Trommelen : 30-40 g de protéines lentes avant le coucher boostent l'anabolisme nocturne de +22%.",
     "PROTOCOLE : 30 à 45 minutes avant le lit, consommez 30 à 40 g de caséine micellaire ou 350 g de Skyr / Cottage Cheese / Fromage blanc 0%.\n"
     "EFFET : Aucun stockage gras supplémentaire si inclus dans votre budget calorique total, meilleure récupération musculaire et réduction des courbatures matinales.",
     "caseine nuit sommeil synthese proteique skyr cottage cheese"),
    ("Protéines et Reins : le verdict scientifique définitif", "Mythes nutrition",
     "Les régimes hyperprotéinés (jusqu'à 2,5-3 g/kg/j) n'abîment PAS des reins sains.",
     "PREUVES (Antonio et al., méta-analyse Devries 2018) : L'augmentation du débit de filtration glomérulaire (DFG) observée sous régime riche en protéines est une adaptation physiologique normale (comme le cœur qui bat plus fort à l'effort), pas une pathologie.\n"
     "EXCEPTION UNIQUE : En cas d'insuffisance rénale chronique préexistante diagnostiquée, un suivi néphrologique et un apport protéique restreint sont obligatoires.",
     "proteines reins danger mythe creatinine filtration glomerulaire"),
    ("Anabolisme et Fenêtre Anabolique : mythe des 30 minutes", "Mythes nutrition",
     "La synthèse protéique reste élevée pendant 24 à 48h après une séance — inutile de paniquer à la minute près.",
     "NUANCE PRATIQUE (Aragon & Schoenfeld 2013) :\n"
     "• Si vous avez mangé un repas protéiné 1-2h avant l'entraînement, la fenêtre post-séance s'étire confortablement sur 2-3h.\n"
     "• Si vous vous êtes entraîné totalement À JEUN le matin, mangez 30-40 g de protéines dans les 45-60 min suivant la séance pour inverser rapidement la balance protéique négative.",
     "fenetre anabolique 30 minutes mythe whey post entrainement"),
    ("Sucre vs Édulcorants vs Miel : faits scientifiques objectifs", "Mythes nutrition",
     "Remettre chaque source sucrée à sa juste place sans diabolisation ni naïveté.",
     "• SUCRE DE TABLE (saccharose) : 50% glucose / 50% fructose. À limiter hors effort (<10% calories) car non rassasiant, mais très utile PENDANT un marathon ou effort intense.\n"
     "• MIEL / SIROP D'ÉRABLE : Gardent des traces d'antioxydants et enzymes, mais restent des sucres caloriques (~300 kcal/100 g).\n"
     "• ÉDULCORANTS (stévia, sucralose, aspartame, érythritol) : Sûrs dans les doses journalières admissibles (EFSA) et utiles comme pont d'adhérence en sèche, à consommer modérément pour ne pas entretenir l'appétence excessive au goût sucré.",
     "sucre edulcorants miel stevia aspartame seche insuline"),
    ("Manger des Glucides le Soir fait-il Grossir ?", "Mythes nutrition",
     "NON : la perte ou prise de graisse dépend du bilan énergétique sur 24h et la semaine, pas de l'horloge.",
     "SCIENTIFIQUEMENT : Des essais randomisés (Sofer et al.) montrent même une meilleure adhérence, moins de faim et un meilleur sommeil lorsqu'une part importante des glucides est placée au dîner (surtout si vous vous entraînez en fin d'après-midi/soir !).",
     "glucides soir grossir mythe diner sommeil bilan calorique"),
    ("Cholestérol alimentaire et Œufs entiers : fin d'un vieux mythe", "Mythes nutrition",
     "Chez la grande majorité des individus, manger 2 à 4 œufs entiers par jour n'augmente pas le risque cardiovasculaire.",
     "SCIENCE : 80% du cholestérol sanguin est produit par le foie ; l'apport alimentaire est régulé à la baisse. De plus, l'étude van Vliet (2017) a démontré qu'après la musculation, les œufs ENTIERS stimulent la synthèse protéique de +40% de plus que les seuls blancs d'œufs à quantité de protéines égale ! (Les acides gras saturés en excès, pas le cholestérol de l'œuf, montent le LDL chez la plupart des gens).",
     "oeufs cholesterol jaune anabolisme sante coeur mythe"),
    ("Café, Thé et Absorption du Fer / Calcium", "Micronutriments",
     "Les tanins et polyphénols du café et du thé peuvent réduire de 40 à 70% l'absorption du fer végétal pris au même repas.",
     "ASTUCE SIMPLE : Laissez 45 à 60 minutes entre un repas riche en fer (ou votre complément de fer) et votre tasse de café/thé noir/thé vert — particulièrement important pour les coureurs, femmes et végétariens !",
     "cafe the fer absorption tanins carence ferritine"),
    ("Cuisson des Aliments : préserver vitamines et limiter composés toxiques", "Digestion & Santé",
     "Comment cuisiner viandes, poissons et légumes pour maximiser la densité nutritionnelle.",
     "• LÉGUMES : Vapeur douce ou wok rapide (l'eau bouillante lessive 40-60% de la vitamine C et B9 — buvez le bouillon si vous faites une soupe !).\n"
     "• VIANDES / POISSONS : Évitez le carbonisé noir au barbecue (amines hétérocycliques) ; mariner au romarin/citron/curcuma réduit les composés toxiques de >70% !\n"
     "• HUILES : Olive/avocat/ghee pour cuire ; colza/lin/noix strictement à froid.",
     "cuisson vapeur vitamines huiles point fumee barbecue marinade"),
    ("Périodisation Nutritionnelle (Fuel for the Work Required)", "Timing nutritionnel",
     "Ajuster les glucides au jour le jour selon l'intensité de la séance prévue.",
     "EXEMPLE SUR LA SEMAINE :\n"
     "• Jours intenses (Jambes lourd, HIIT, Sortie longue >90 min) : Glucides HAUTS (5-7 g/kg), lipides plus bas.\n"
     "• Jours modérés (Haut du corps, Zone 2 45 min) : Glucides MODÉRÉS (3-4 g/kg).\n"
     "• Jours de repos complet : Glucides BAS-MODÉRÉS (2-3 g/kg), lipides sains un peu plus hauts, protéines constantes tous les jours (1,8-2,2 g/kg).",
     "periodisation nutritionnelle carb cycling glucides jours repos"),
    ("Bilan Sanguin Annuel Recommandé du Sportif", "Santé & Prévention",
     "Les biomarqueurs clés à vérifier 1 fois par an avec son médecin pour optimiser santé et performance.",
     "CHECK-LIST BILAN SPORTIF :\n"
     "• NFS (Globules rouges/hémoglobine) + FERRITINE + CRP ultrasensible.\n"
     "• Vitamine D (25-OH-D) et Vitamine B12 / Folates.\n"
     "• Glycémie à jeun + HbA1c + Bilan lipidique (HDL, LDL, Triglycérides, ApoB).\n"
     "• TSH (Thyroïde) + Transaminases (ASAT/ALAT — attention : les grosses séances de musculation élèvent temporairement ASAT et CPK pendant 48-72h sans maladie du foie ! Faites la prise de sang après 48h de repos).",
     "bilan sanguin prise de sang ferritine vitamine d cpk transaminases tsh"),
    ("Gestion des Fringales et Grignotages Émotionnels", "Gestion du poids",
     "Stratégies comportementales et nutritionnelles pour éliminer les craquages du soir.",
     "1. ENVIRONNEMENT : Ne stockez pas de déclencheurs hyper-palatables à portée de main ; préparez des alternatives prêtes (skyr + fruits rouges + cacao).\n"
     "2. VÉRIFIEZ LA BASE : 80% des fringales de 21h viennent d'un manque de protéines au petit-déjeuner/déjeuner ou d'un déficit trop agressif (>600 kcal).\n"
     "3. SOMMEIL : Une nuit <6h augmente la ghréline (faim) de +28% et baisse la leptine (satiété).\n"
     "4. RÈGLE DES 15 MINUTES : Buvez un grand verre d'eau/tisane chaude et attendez 15 min avant de décider.",
     "fringales grignotage sucre soir seche comportement ghréline"),
    ("Nutrition et Prise de Masse pour 'Hardgainers' (Faible Appétit)", "Gestion du poids",
     "Comment manger 3 200+ kcal sainement sans se sentir gavé toute la journée.",
     "• CALORIES LIQUIDES PROPRES : 1 smoothie quotidien (avoine 80 g + banane + lait + whey 30 g + beurre cacahuète 30 g = ~750 kcal très faciles à boire).\n"
     "• GLUCIDES MOINS VOLUMINEUX : Riz basmati, pâtes, bagels, pain d'épices, fruits secs (plutôt que 2 kg de brocolis et pommes de terre).\n"
     "• LIPIDES SAINS DENSES : +1 c.à.s huile d'olive sur les plats, purées d'oléagineux, avocat, saumon, œufs entiers.\n"
     "• 4 À 5 PRISES RÉGULIÈRES : Ne sautez jamais le petit-déjeuner.",
     "hardgainer prise de masse appétit calories liquides smoothie gainer"),
    ("Nutrition de Récupération entre Deux Séances le Même Jour", "Timing nutritionnel",
     "Protocole accéléré lorsque le délai de récupération entre 2 entraînements ou matchs est inférieur à 8 heures.",
     "1. DANS LES 30 MIN : 1,0 à 1,2 g glucides/kg (IG moyen-élevé : riz blanc, compote, banane, maltodextrine, lait chocolaté) + 0,3-0,4 g protéines/kg (whey/yaourt).\n"
     "2. HYDRATATION : 150% du poids perdu avec 600-800 mg sodium/L dans les 2 premières heures.\n"
     "3. SIESTE : 20-30 min de sieste (ou NSDR) restaure le système nerveux central avant la 2e séance.",
     "bi quotidien recuperation rapide glycogene deux seances tournoi"),
    ("Microbiote, Probiotiques et Immunité hivernale du sportif", "Digestion & Santé",
     "70% du système immunitaire réside dans l'intestin : protéger la barrière intestinale réduit les rhumes en plein bloc d'entraînement.",
     "• ALIMENTS FERMENTÉS QUOTIDIENS (1-2 portions/j) : Kéfir, yaourt vivant, choucroute crue, kimchi, miso, tempeh (étude Stanford Sonnenburg 2021 : augmente la diversité microbienne et réduit 19 marqueurs inflammatoires).\n"
     "• 30 VÉGÉTAUX DIFFÉRENTS / SEMAINE : Fruits, légumes, épices, herbes, noix, graines, céréales complètes.",
     "microbiote probiotiques fermentes immunite kefir choucroute intestin"),
    ("Sodium Bicarbonate, Beta-Alanine, Créatine, Caféine, Nitrates : le Top 5 Scientifique AIS", "Suppléments prouvés",
     "Les 5 seuls compléments ergogéniques classés Groupe A (preuves scientifiques solides) par l'Institut Australien du Sport (AIS).",
     "CLASSEMENT AIS GROUPE A (EFFICACITÉ PROUVÉE) :\n"
     "1. CAFÉINE : Force, endurance, vigilance (3-6 mg/kg).\n"
     "2. CRÉATINE MONOHYDRATE : Force, hypertrophie, sprints répétés, cognition (3-5 g/j).\n"
     "3. NITRATES (JUS DE BETTERAVE) : Économie d'oxygène endurance (350-500 mg).\n"
     "4. BÊTA-ALANINE : Efforts intenses 1-4 min (3,2-6,4 g/j chronique).\n"
     "5. BICARBONATE DE SODIUM : Efforts anaérobies 1-8 min (0,2-0,3 g/kg).\n"
     "Tout le reste (tribulus, boosters testostérone, brûleurs de graisse miracles) = 95% marketing sans effet prouvé !",
     "supplements prouves ais groupe a top 5 creatine cafeine nitrates"),
]


def build_nutrition():
    """Assemble 1 030+ aliments réels + 70 guides scientifiques = 1 100+ entrées nutrition."""
    entries = []
    seen = set()

    all_foods = FOODS_PART_A + EXTRA_FOODS_PART_A + FOODS_PART_B + CIQUAL_EXTENDED_CATALOG + CIQUAL_DEEP_CATALOG
    for name, subcat, kcal, prot, gluc, lip, fib, ig, micros, timing in all_foods:
        key = name.strip().lower()
        if key in seen:
            continue
        seen.add(key)

        ig_label = "Nul (0)" if ig == 0 else (f"Bas ({ig})" if ig <= 40 else (f"Modéré ({ig})" if ig <= 60 else f"Élevé ({ig})"))
        summary = f"{subcat} · {kcal} kcal | P {prot}g · G {gluc}g · L {lip}g · Fibres {fib}g (/100 g)"
        body = (
            f"VALEURS NUTRITIONNELLES POUR 100 G :\n"
            f"• Énergie : {kcal} kcal\n"
            f"• Protéines : {prot} g\n"
            f"• Glucides : {gluc} g (Index Glycémique : {ig_label})\n"
            f"• Lipides : {lip} g\n"
            f"• Fibres : {fib} g\n\n"
            f"MICRONUTRIMENTS & ATOUTS CLÉS :\n{micros}\n\n"
            f"MOMENT IDÉAL & USAGE SPORTIF :\n{timing}"
        )
        meta = {
            "type": "aliment",
            "kcal_100g": kcal,
            "proteines_g": prot,
            "glucides_g": gluc,
            "lipides_g": lip,
            "fibres_g": fib,
            "index_glycemique": ig,
            "micronutriments": micros,
            "moment_ideal": timing,
        }
        tags = f"nutrition,aliment,{subcat.lower()},macros,calories,proteines"
        entries.append({
            "category": "nutrition",
            "subcategory": subcat,
            "title": name,
            "summary": summary,
            "body": body,
            "tags": tags,
            "metadata_json": json.dumps(meta, ensure_ascii=False),
        })

    for title, subcat, summary, body, kw in NUTRITION_SCIENCE_GUIDES:
        key = title.strip().lower()
        if key in seen:
            continue
        seen.add(key)
        meta = {"type": "guide_science", "mots_cles": kw}
        entries.append({
            "category": "nutrition",
            "subcategory": subcat,
            "title": title,
            "summary": summary,
            "body": body,
            "tags": f"nutrition,science,{subcat.lower()},{kw}",
            "metadata_json": json.dumps(meta, ensure_ascii=False),
        })

    return entries


if __name__ == "__main__":
    n = build_nutrition()
    print(f"Total nutrition entries: {len(n)}")
