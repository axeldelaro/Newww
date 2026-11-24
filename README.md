# Newww — Suite d'Applications Android Haut de Gamme 100 % Hors-Ligne

Ce dépôt contient **deux applications Android natives distinctes** (`Kotlin + Jetpack Compose + NDK C++`), conçues sans aucun serveur, sans compte, sans abonnement et sans dépendance cloud.

---

## 1. KINETIX Coach (`kinetix-coach/` — v2.5.0)
**Coach Caméra IA Studio Pro + Progression Athlète E→S & Quêtes Vérifiées Caméra + VBT Rep Max + Podomètre Matériel + Coach IA Local**

- **Package** : `com.kinetix.coach` (Android 10+ / `arm64-v8a`, 100 % hors-ligne, zéro requête réseau, zéro pub, zéro boutique)
- **Progression Athlète Pro, Quêtes Procédurales & Épreuves de Rang Caméra (`RpgProgressionEngine`, `ProceduralQuestEngine`, `ProgressionRepository`, SQLite v5)** :
  - **Niveaux & Rangs `E → S` sans aucune pénalité** : XP pondérée par la qualité biomécanique réelle (posture + VBT), **zéro perte d'XP à minuit** et zéro punition. Atteindre 100 % d'XP de rang débloque une **véritable Épreuve de Passage vérifiée par la caméra** (Squats, Pompes, Planche, *Bip & Go* Militaire) qui valide la promotion (`E → D → C → B → A → S`).
  - **3 Quêtes Quotidiennes Procédurales Déterministes** : générées chaque matin selon la date, le Rang et votre matériel (`Exercice × Modificateur × Format`) — lancées en 1 tap sur la caméra et **auto-validées** par vos vraies séries (fallback discret hors-caméra inclus).
  - **Cockpit Athlète Pro (`Suivi` : `Quêtes` / `Progression` / `Historique`)** : header compact Rang/Niveau/Titre/Streak, **Streak 7 cases visuel** (+150 XP bonus semaine 7/7), **Podomètre matériel hors-ligne** (`Sensor.TYPE_STEP_COUNTER` + palier 10 000 pas + graphique 7 jours), **Radar biomécanique 5 axes** (`Force`, `Endurance`, `Agilité`, `Vitalité`, `Maîtrise`), **Palmarès cumulé à vie**, **12 Badges** et **10 Titres équipables au mérite** (zéro boutique, zéro monnaie virtuelle).
  - **Coach IA Local connecté à votre progression** : `Gemma-3 1B` connaît automatiquement votre Rang, Streak, pas du jour, axe faible du radar et quêtes ouvertes.
- **Démarrage 100 % Contrôlé & Comptage IA Anti-Fausses-Reps Blindé (`BiomechanicsEngine`, `PoseSanityFilter`, `OneEuroFilter`)** :
  - **Prévisualisation `IDLE` à l'ouverture** (zéro auto-lancement), bouton **`Démarrer`** explicite (`0s / 5s / 8s / 10s`), **Tractions (`PULL_UP`) par physique réelle de barre** (ancrage poignets + élévation épaules), garde anti-marche/dérive `0,7 s`, rejeu rétroactif `2,4 s` en mode `AUTO`, auto-calibration monoculaire 3D des pompes.
- **Interface Caméra Studio Minimaliste Pro + VBT Rep Max + Écosystème complet** :
  - **~85 % d'image caméra libre**, **16 exercices & variantes** (`ExerciseVariantDetector`), VBT (`VelocityEstimator`), **7 Défis caméra** + 4 musiques PCM + **import MP3 local**, **42 vidéos MP4 H.264**, **LLM local `Gemma-3 1B Q4_K_M`** + **2 683 fiches SQLite FTS5** (**77/77 tests unitaires**).

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

## 3. ZAAGFORGE (`zaagforge/` — v1.0.0)
**Studio Automatique de Remix Hardcore / Uptempo / Unicorn On Ketamine (Piep) / Noiseflow (Krach) / Zaag — Moteur DSP C++20**

- **Package** : `com.zaagforge.studio` (`arm64-v8a`, Android 10+, `157/157` assertions C++20 + JVM tests)
- **8 Presets Studio Extrêmes (`155 → 225 BPM`)** : *UOK Piep Madness*, *Noiseflow Krach*, *Pure Zaag Chainsaw*, *Bounce Uptempo*, *GPF Piep Terror*, *Xtra Rawstyle*, *Frenchcore Euphoric*, *Schranz Industrial*.
- **8 Topologies de Kicks Synthétisées en C++20** : `PIEP_UOK`, `KRACH_NOISEFLOW`, `ZAAG_CHAINSAW`, `UPTEMPO_BOUNCE`, `ZAAG_PIEP_HYBRID`, `RAWSTYLE_GATED`, `FRENCHCORE_MELODIC`, `SCHRANZ_INDUSTRIAL`, avec screeches, risers, build-up rolls, stutter vocal chops, WSOLA time-stretch/pitch-shift, crossover Linkwitz-Riley 4e ordre, sidechain, structure complète (*Intro → Break → Build-Up → Fake-Drop → Drop 1 → Kick Switch → Drop 2*) et master 3-bandes OTT/Hard-Clipper.
- **Bridge Hybride MP3 & Streaming** : import local `MP3/WAV/FLAC/M4A/OGG`, recherche API publique **Deezer** (aperçus officiels 30s remixables immédiatement), résolution de liens partagés **Spotify / Deezer / SoundCloud** (`oEmbed` + matching fuzzy MP3 local), écoute A/B instantanée et exports `M4A AAC 256k` / `WAV 16-bit` dans `Music/ZaagForge/`.

---

## 4. StrikeCam Arcade (`strikecam/` — v1.0.0)
**Jeu d'Arcade Rythme-Boxe Full-Body Caméra (Beat Saber × BoxVR) — 60–120 FPS + FFT C++20**

- **Package** : `com.strikecam.arcade` (`arm64-v8a`, Android 10+, MediaPipe Pose + CameraX `4:3` pixel-perfect)
- **Gameplay Rythme-Boxe Caméra** : jabs poing gauche rouge / droit cyan, **Slashes directionnels (`↑ ↓ ← →`, bonus `×1.5`)**, **Double Power Strikes (`×1.5`)**, **Murs Laser d'Esquive** (*Baisse-toi !*, *Esquive ← / →*), auto-calibration `3-2-1`, combo multiplicateur `1× → 8× FEVER`, modes **Solo Arcade**, **Survie (3 vies + accélération jusqu'à `1.60×`)** et **Duel 2 Joueurs**.
- **Synergie ZAAGFORGE & Streaming** : 5 pistes synthétisées C++20 (*Neon Velocity 135* → *Piep Boss 215 BPM*), **détection automatique prioritaire de vos remixes `Music/ZaagForge/`**, import MP3 local avec beatmap FFT 3-bandes automatique, recherche **Deezer 30s FFT**, et **Mode Live Sync Spotify / Deezer / SoundCloud** (*Tap-Sync*, phase `±50ms`, *Micro Beat Tracker C++20*, télécommande média).

---

## Installation des APK Release Signés
Les APK prêts à installer (signés v2, `arm64-v8a`) sont publiés dans **[Releases](https://github.com/axeldelaro/Newww/releases)**.
