# Newww — Suite d'Applications Android Haut de Gamme 100 % Hors-Ligne

Ce dépôt contient **deux applications Android natives distinctes** (`Kotlin + Jetpack Compose + NDK C++`), conçues sans aucun serveur, sans compte, sans abonnement et sans dépendance cloud.

---

## 1. KINETIX Coach (`kinetix-coach/` — v2.3.0)
**Interface Caméra Studio Minimaliste Pro + Comptage IA Ultra-Fluide Zéro Rep Perdue + VBT Rep Max + Variantes sur 16 Exos + Coach IA Local**

- **Package** : `com.kinetix.coach` (Android 10+ / `arm64-v8a`, 100 % hors-ligne, zéro requête réseau)
- **Interface Caméra Pro Ultra-Épurée (`VisionScreen`, `SkeletonOverlay`)** :
  - **~85 % d'image caméra libre** : une seule ligne en haut (pilule d'exercice/variante + bouton bascule caméra + bouton réglages), un **décompte d'installation géant centré** lisible à 3 mètres, un **squelette épuré** sans rapporteurs ni bulles de degrés par défaut (activables en mode expert dans *Réglages*), un **toast coach conditionnel** totalement invisible quand l'exécution est propre, et un **dock verre compact** (compteur géant animé + retour haptique, pilule VBT 2 lignes `m/s` / `MAX ~X` dépliable au clic, boutons `Repos` & `Fin`). Tous les outils secondaires (*Défis, Démo, Reset, Auto/Fixe, Voix, Délai, Repos, Exigence*) sont rangés proprement dans la feuille *Réglages*.
- **Comptage Caméra Parfait — Zéro 1re Rep Perdue & Zéro Blocage (`BiomechanicsEngine`, `OneEuroFilter`, `VisionViewModel`)** :
  - **Rejeu rétroactif 2,4 s en mode `AUTO` (`switchExerciseWithReplay`)** : quand l'IA détecte votre exercice (ex. `Squat → Pompes` ou `Planche → Pompes`), elle rejoue les 2,4 dernières secondes en `< 0,5 ms` pour synchroniser ou créditer immédiatement votre **1re répétition**.
  - **Auto-calibration monoculaire 3D des pompes + complétion top-return** : calibre dynamiquement votre extension haute réelle (`130°–148°` & ratio bras) pour qu'un téléphone posé au sol de face/oblique ne bloque jamais une répétition en phase concentrique, avec soft-lockout calibré sur Squat / Soulevé / Curl / Dips et filtre One-Euro réactif (`minCutoff = 1.65`, `beta = 0.12`).
- **Zéro Fausse Rep pendant l'Installation & le Repos (`PoseSanityFilter`, `VisionViewModel`)** :
  - Chrono d'installation mains-libres (`0 / 5 / 8 / 10 s`, défaut `5 s`) avec prévisualisation squelette 60 FPS (`previewPoseOnly`), verrou position neutre (`requiresNeutralStart`), stabilisation `450 ms` post-transition et contrôle poignets 2D `X + Y`.
- **16 Exercices, Variantes & Coaching Anti-Spam (`ExerciseVariantDetector`, `PostureFeedbackCoach`, `VelocityEstimator`)** :
  - **9 variantes pompes** (*Classique, Large, Serrée, Diamant, Genoux, Piquée, Archer, Inclinée, Déclinée*) + détection automatique de toutes les variantes sur les **16 exercices**, coaching postural zéro spam (persistance temporelle + cooldowns vocaux), et **système VBT complet** (vitesse concentrique `m/s`, perte de vitesse `VL%`, fraîcheur inter-séries, RIR/RPE, **Rep Max estimé `MAX ~X`**, sparkline, SQLite v4 et débriefing Coach IA).
- **Écosystème complet** : **7 Défis caméra** (*Bip & Go*, *Luc Léger*, *Gainage Musical*, *25/50/100 Reps*, *AMRAP*, *Tabata*, *EMOM*) + 4 musiques PCM + **import MP3 local**, calories personnalisées, **42 vidéos MP4 H.264**, **LLM local `Gemma-3 1B Q4_K_M`** + **2 683 fiches SQLite FTS5** (**63/63 tests unitaires**).

---

## 2. AstroPro 14U (`astropro-14u/` — v2.0.0)
**Appareil Photo Astrophotographie Dédié Xiaomi 14 Ultra — Interface HyperOS / Leica Pro + Pipeline Automatique**

- **Package** : `com.astropro.xiaomi14u` (Android 10+ / `arm64-v8a`, zéro permission réseau)
- **Autofocus Stellaire Courbe en V (`AF ★`) + Infini Mémorisé par Objectif** : repère tout seul la meilleure étoile non saturée, balaie 12 pas autour de l’infini, verrouille le minimum FWHM/HFD et mémorise la calibration par objectif (`12 / 23 / 75 / 120 mm`).
- **AR Sky Overlay sur le Viseur (toggle `AR`)** : projette en direct la Voie Lactée, Polaris et les 12 cibles majeures selon le `FOV` optique réel + flèche de guidage vers la cible (100 % Vision Nocturne OLED rouge).
- **Paysage + Ciel Séparé 100 % Automatique** : détection auto de l’horizon en C++20 ; empile le ciel aligné sur les étoiles et le premier plan fixe sans flou de bougé (zéro tracé manuel).
- **Mode HDR Astro Automatique (`3 EV / 5 EV`)** + **Super-Résolution Drizzle 2×** + **Déconvolution Richardson-Lucy & Anti-Aberration Chromatique automatiques** + **Rejet Anti-Vibration / Vent automatique** (gyroscope + accéléromètre).
- **Pipeline Post-Capture Zéro Effort** : Auto-STF + retrait gradient 2D + Anti-CA + netteté appliqués automatiquement, JPEG HD directement dans `DCIM/AstroPro14U` + master 32-bit `.astro32`.
- **Guide Terrain & 7 Exemples Photo Réalistes par Mode** (1 par mode + bouton 1-tap *Appliquer ces réglages*), détection multi-objectifs Xiaomi 14 Ultra (`0..64` CamX, `f/1.63–f/4.0`), Focus Peaking C++ (`0,3 ms`), Live Stacking Kappa-Sigma, Lucky Imaging + ondelettes, Star Trails, Timelapse MP4, exports **TIFF 16-bit / FITS 32-bit / DNG / JPEG HD**, Éphémérides Jean Meeus.

---

## Installation des APK Release Signés
Les 2 APK prêts à installer (signés v2, `arm64-v8a`) sont publiés dans **[Releases](https://github.com/axeldelaro/Newww/releases)**.
