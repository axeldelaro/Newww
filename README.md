# Newww — Suite d'Applications Android Haut de Gamme 100 % Hors-Ligne

Ce dépôt contient **deux applications Android natives distinctes** (`Kotlin + Jetpack Compose + NDK C++`), conçues sans aucun serveur, sans compte, sans abonnement et sans dépendance cloud.

---

## 1. KINETIX Coach (`kinetix-coach/`)
**Coach Sportif Vision IA + Coach Textuel LLM Local + Base Colossale de Connaissances**

- **Package** : `com.kinetix.coach` (Android 10+ / `arm64-v8a`, 100 % hors-ligne, zéro requête réseau)
- **LLM Local Prêt à l’Emploi (intégré dans l’APK)** : `Gemma-3 1B Instruct Q4_K_M` propulsé par `llama.cpp` compilé ARM64 (`armv8.2-a+dotprod+fp16` + repack CPU), streaming fluide token par token, UTF-8 sûr pour les accents français, possibilité d’importer un `.gguf` externe dans les réglages.
- **Base de Connaissances Hors-Ligne Massive (`knowledge_base.db`)** : **2 683 fiches uniques en français** indexées SQLite FTS5 (`~2,6 ms` / requête) + 156 synonymes FR/EN :
  - `1 103` fiches nutrition (1 031 aliments CIQUAL/USDA + 72 guides nutrition sportive & suppléments)
  - `911` exercices détaillés (muscles agonistes/synergistes, biomécanique, erreurs & corrections, progressions)
  - `288` articles de biomécanique & physiologie de l’effort
  - `217` protocoles prévention blessures, préhab, réathlétisation & mobilité
  - `164` programmes & périodisations complètes
- **Vision IA & Biomécanique Temps Réel** : `CameraX` + `MediaPipe Pose Landmarker` (33 points) + lissage EMA + **16 exercices analysés en direct** (comptage reps par hystérésis, ROM %, score de forme 0–100, détection de fautes posturales, coach vocal TTS français, transfert direct de la séance vers le Coach IA).
- **Suivi & Outils** : Historique des séances avec courbe de progression, profil athlète injecté dans le RAG, calculateurs **1RM** (Epley/Brzycki) et **TDEE / Macros** (Mifflin-St Jeor).

### Reconstruction locale
```bash
cd kinetix-coach
python3 scripts/build_knowledge_base.py
# Télécharger les modèles open-source dans app/src/main/assets/models/ :
# - gemma-3-1b-it-Q4_K_M.gguf (unsloth/gemma-3-1b-it-GGUF)
# - pose_landmarker_full.task (MediaPipe Pose Landmarker)
gradle assembleRelease
```

---

## 2. AstroPro 14U (`astropro-14u/`)
**Appareil Photo Astrophotographie Dédié Xiaomi 14 Ultra — Interface HyperOS / Leica Pro**

- **Package** : `com.astropro.xiaomi14u` (Android 10+ / `arm64-v8a`, zéro permission réseau)
- **Détection Multi-Objectifs Xiaomi 14 Ultra (`XiaomiLensProbe`)** : Scan complet en 3 étapes (`cameraIdList` + `physicalCameraIds` + sonde IDs numériques `0..64` pour HyperOS/CamX), bascule 3 niveaux (Direct physique → Flux physique logique → Zoom Ratio), support ouverture variable `f/1.63–f/4.0` du capteur 1" LYT-900, 12mm UGA, 75mm 3.2× et 120mm 5×, plus écran Diagnostic HAL avec export JSON.
- **Interface Fidèle Xiaomi HyperOS Camera Pro** : Noir OLED pur `#000000`, accents Or Pro `#F5B82E`, molettes crantées horizontales avec retour haptique (`WB`, `F` avec snap `∞`, `S` avec snap `NPF`, `ISO`, `f/`, `EV`), sélecteur de focales flottant (`0.5 · 1 · 3.2 · 5`), histogramme RGB live, niveau à bulle 2 axes, loupe 10× avec calcul **FWHM / HFD** en temps réel, déclenchement sans vibration (retardateur + boutons Volume / télécommande Bluetooth) et **Mode Vision Nocturne OLED Rouge** 1-tap sur toute l’application.
- **Moteur C++20 16-Bit Natif (`libastropro_native.so`)** :
  - **Astro Pro** : Capture manuelle RAW 16-bit (`DNG`) + règle NPF automatique.
  - **Ciel Profond (Live Stacking)** : Démosaïquage linéaire Malvar-He-Cutler 16-bit, calibration Dark/Flat/Bias, alignement stellaire sub-pixel par astérismes de triangles + RANSAC (`< 0.05 px` RMS), empilement **Kappa-Sigma Clipping** anti-avions/satellites.
  - **Lune & Planètes (Lucky Imaging)** : Rafale rapide, tri netteté Tenengrad, alignement planétaire sub-pixel et accentuation par ondelettes **À Trous B3-Spline**.
  - **Star Trails** : Empilement Maximum/Lighten temps réel + mode **Comète** à fondu réglable.
  - **Timelapse & Intervallomètre** : Séquences programmables + encodage MP4 H.264 matériel local (`MediaCodec`).
  - **Labo Astro 16-bit** : Auto-STF PixInsight, Arcsinh stretch, extraction de gradient pollution lumineuse polynomiale 2D, neutralisation fond de ciel, SCNR, ondelettes, débruitage stellaire, exports **TIFF 16-bit**, **FITS 32-bit** (Siril/PixInsight), **DNG 16-bit** et **JPEG HD**.
  - **Éphémérides Hors-Ligne** : Algorithmes de Jean Meeus (crépuscules, phase/score lunaire, centre galactique de la Voie Lactée, boussole stellaire magnétique, 20 cibles avec objectif Xiaomi recommandé).

### Reconstruction locale
```bash
cd astropro-14u
gradle testDebugUnitTest assembleRelease
```

---

## Installation des APK Release Signés
Les 2 APK prêts à installer (signés v2, `arm64-v8a`) sont publiés dans l’onglet **[Releases](https://github.com/axeldelaro/Newww/releases)** de ce dépôt.
