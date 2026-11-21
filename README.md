# Newww — Suite d'Applications Android Haut de Gamme 100 % Hors-Ligne (v1.1.0)

Ce dépôt contient **deux applications Android natives distinctes** (`Kotlin + Jetpack Compose + NDK C++`), conçues sans aucun serveur, sans compte, sans abonnement et sans dépendance cloud.

---

## 1. KINETIX Coach (`kinetix-coach/` — v1.1.0)
**Coach Sportif Vision IA + Vidéos Biomécaniques + Coach Textuel LLM Local + Base Colossale**

- **Package** : `com.kinetix.coach` (Android 10+ / `arm64-v8a`, 100 % hors-ligne, zéro requête réseau)
- **42 Vidéos Explicatives Biomécaniques MP4 H.264 Hors-Ligne** (`assets/videos/`, `2,5 Mo`, 30 FPS) :
  - Couverture **100 %** : 16/16 exercices Vision IA + **911/911 exercices** de l’Encyclopédie.
  - Silhouettes anatomiques volumétriques, muscles actifs surlignés en orange, matériel (barre/haltères/banc/poulie/kettlebell), angles articulaires dynamiques, phase, tempo et respiration.
  - Lecteur natif intégré avec **ralenti technique `0.5× / 1.0× / 1.5×`**, décomposition en 4 étapes et checklist *À faire / À éviter* dans **Vision**, **Encyclopédie** et **sources du Coach IA**.
- **LLM Local Prêt à l’Emploi (intégré dans l’APK)** : `Gemma-3 1B Instruct Q4_K_M` via `llama.cpp` ARM64 (`armv8.2-a+dotprod+fp16`), streaming fluide, rendu Markdown propre, import optionnel de `.gguf` externe.
- **Base de Connaissances Hors-Ligne Massive (`knowledge_base.db`)** : **2 683 fiches uniques en français** SQLite FTS5 (`~2,6 ms` / requête) + 156 synonymes + filtres par groupe musculaire.
- **Vision IA & Suivi** : `CameraX` + `MediaPipe Pose Landmarker`, 16 exercices analysés (reps, ROM %, score de forme, coach vocal TTS FR), **minuteur de repos intelligent + multi-séries**, calculateurs **1RM** & **TDEE / Macros**.

---

## 2. AstroPro 14U (`astropro-14u/` — v1.1.0)
**Appareil Photo Astrophotographie Dédié Xiaomi 14 Ultra — Interface HyperOS / Leica Pro**

- **Package** : `com.astropro.xiaomi14u` (Android 10+ / `arm64-v8a`, zéro permission réseau)
- **Guide Terrain & Exemples Photo par Mode (Style Xiaomi HyperOS)** :
  - **6 exemples photo astrophotographiques réalistes** (1 par mode : *Astro Pro*, *Ciel Profond*, *Lune & Planètes*, *Star Trails Comète*, *Timelapse*, *Calibration Avant/Après*), **4 points explicatifs brefs** et bouton 1-tap **« Appliquer ces réglages »** (100 % compatible Vision Nocturne OLED rouge).
- **Détection Multi-Objectifs Xiaomi 14 Ultra (`XiaomiLensProbe`)** : Scan 3 niveaux (`cameraIdList` + `physicalCameraIds` + IDs `0..64` HyperOS/CamX), bascule 3 paliers, ouverture variable `f/1.63–f/4.0` (23 mm 1" LYT-900), 12 mm UGA, 75 mm 3.2×, 120 mm 5×, diagnostic HAL JSON.
- **Interface Fidèle Xiaomi HyperOS Camera Pro** : Noir OLED pur `#000000`, Or Pro `#F5B82E`, molettes crantées haptiques (`WB`, `F` + `∞`, `S` + `NPF`, `ISO`, `f/`, `EV`), **Focus Peaking C++ temps réel (`0,3 ms`)**, anneau compte à rebours pose longue + ETA empilement, histogramme RGB live, niveau 2 axes, loupe 10× **FWHM / HFD**, déclenchement sans vibration, **Mode Vision Nocturne OLED Rouge** 1-tap et persistance automatique des préférences.
- **Moteur C++20 16-Bit Natif (`libastropro_native.so`)** : Astro Pro RAW DNG, Ciel Profond (Live Stacking alignement par astérismes + Kappa-Sigma), Lune & Planètes (Lucky Imaging + ondelettes À Trous), Star Trails (+ Comète), Timelapse MP4 matériel, Calibration Dark/Flat/Bias, **Labo 16-bit avec Presets 1-tap** + exports **TIFF 16-bit / FITS 32-bit / DNG / JPEG HD**, et Éphémérides Jean Meeus hors-ligne.

---

## Installation des APK Release Signés
Les 2 APK prêts à installer (signés v2, `arm64-v8a`) sont publiés dans **[Releases](https://github.com/axeldelaro/Newww/releases)**.
