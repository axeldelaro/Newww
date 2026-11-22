# Newww — Suite d'Applications Android Haut de Gamme 100 % Hors-Ligne

Ce dépôt contient **deux applications Android natives distinctes** (`Kotlin + Jetpack Compose + NDK C++`), conçues sans aucun serveur, sans compte, sans abonnement et sans dépendance cloud.

---

## 1. KINETIX Coach (`kinetix-coach/` — v2.4.0)
**Interface Caméra Studio Minimaliste Pro + Démarrage Contrôlé + Comptage IA Anti-Fausses-Reps Blindé (Tractions, Pompes, 16 Exos) + VBT Rep Max + Coach IA Local**

- **Package** : `com.kinetix.coach` (Android 10+ / `arm64-v8a`, 100 % hors-ligne, zéro requête réseau)
- **Démarrage 100 % Contrôlé — Zéro Auto-Lancement à l'Ouverture (`VisionViewModel`, `VisionScreen`)** :
  - À l'ouverture de la caméra (et après changement d'exercice ou fermeture d'un bilan), l'app reste en mode **Prévisualisation Prêt (`IDLE`)** : cadrage squelette 60 FPS live, **zéro comptage, zéro décompte, zéro alerte**. La série ne démarre qu'au clic explicite sur **`Démarrer`** (avec choix rapide du délai d'installation `0s / 5s / 8s / 10s` et bouton `Annuler`).
- **Comptage IA Blindé Anti-Fausses-Reps (`BiomechanicsEngine`, `PoseSanityFilter`, `OneEuroFilter`)** :
  - **Tractions (`PULL_UP`) par physique réelle de barre** : porte posturale haute **toujours active** (même en cours de rep), **ancrage 2D des poignets sur la barre** (`pullupAnchorWindow`) + **élévation verticale réelle des épaules** (`pullupHangShoulderY`) + fin bras tendus au-dessus de la tête — attraper la barre, lâcher la barre ou marcher en baissant les bras ne compte plus **jamais** de fausse répétition.
  - **Garde anti-marche & anti-gestes parasites sur les 16 exercices** : détection de marche lente et de dérive cumulée (`0,7 s`) + approche caméra dans `PoseSanityFilter`, gel immédiat des frames hors-posture, ancrage mains `Dips` (`dipWristWindow`), arrêt sur décollement des mains `Pompes`, portes posturales strictes `Rowing` / `Développé militaire` / `Dips` / `Curl` / `Élévations latérales` / `Jumping Jack`.
  - **Rejeu rétroactif 2,4 s en mode `AUTO` (`switchExerciseWithReplay`)** + **auto-calibration monoculaire 3D des pompes** (`130°–148°` & complétion top-return) + filtre One-Euro réactif (`minCutoff = 1.65`, `beta = 0.12`).
- **Interface Caméra Pro Ultra-Épurée (`VisionScreen`, `SkeletonOverlay`)** :
  - **~85 % d'image caméra libre**, 1 seule ligne en haut (exercice/variante + caméra + réglages), squelette minimaliste sans rapporteurs ni bulles de degrés par défaut (mode expert dans *Réglages*), toast coach invisible sur les reps propres, dock verre compact (compteur géant animé + VBT `m/s` / `MAX ~X` dépliable + `Repos` / `Fin`).
- **16 Exercices, Variantes, VBT & Écosystème complet** : **9 variantes pompes** + variantes sur les **16 exercices** (`ExerciseVariantDetector`), coaching anti-spam (`PostureFeedbackCoach`), VBT complet (`VelocityEstimator` + SQLite v4), **7 Défis caméra** + 4 musiques PCM + **import MP3 local**, **42 vidéos MP4 H.264**, **LLM local `Gemma-3 1B Q4_K_M`** + **2 683 fiches SQLite FTS5** (**65/65 tests unitaires**).

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
