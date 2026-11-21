# Newww — Suite d'Applications Android Haut de Gamme 100 % Hors-Ligne (v1.2.0)

Ce dépôt contient **deux applications Android natives distinctes** (`Kotlin + Jetpack Compose + NDK C++`), conçues sans aucun serveur, sans compte, sans abonnement et sans dépendance cloud.

---

## 1. KINETIX Coach (`kinetix-coach/` — v1.2.0)
**Vision Biomécanique 3D Multi-Angles + Détection Auto d'Exercices + Calories + 42 Vidéos + Coach IA Local**

- **Package** : `com.kinetix.coach` (Android 10+ / `arm64-v8a`, 100 % hors-ligne, zéro requête réseau)
- **Vision Biomécanique 3D Multi-Angles** (`PoseLandmarkerHelper` `worldLandmarks` + `OneEuroFilter` + `CameraViewpointDetector`) :
  - Mesure les vrais angles articulaires en 3D métrique sous **tous les angles de caméra** (`Face 0°`, `3/4 G/D 45°`, `Profil G/D 90°`, `Dos 180°`), symétrie gauche/droite, valgus 3D et alertes de cadrage.
  - **Corrections visuelles & vocales au degré près** : arcs rapporteurs colorés aux articulations, ligne fantôme pointillée de la posture cible idéale, flèches directionnelles sur le squelette et consigne chiffrée (`114° → ≤95°`).
- **Détection Automatique de l’Exercice (`AutoExerciseDetector`)** : identifie en direct l’exercice pratiqué parmi les 16 mouvements (mode `AUTO` / `FIXE` 1-tap, hystérésis anti-clignotement, cumul multi-exercices).
- **Calories Dépensées en Direct (`CalorieEngine`)** : calcul MET corrigé (Mifflin-St Jeor sur le profil athlète) + travail mécanique ajusté par la ROM % et le tempo, suivi live dans le HUD, historique SQLite v2 et débrief Coach IA.
- **42 Vidéos Explicatives Biomécaniques MP4 H.264 Hors-Ligne** (`assets/videos/`, `2,5 Mo`, 30 FPS) couvrant **100 % des exercices** (16/16 Vision + **911/911 Encyclopédie**), lecteur natif **ralenti `0.5× / 1.0× / 1.5×`**, minuteur de repos intelligent + multi-séries.
- **LLM Local Prêt à l’Emploi (intégré dans l’APK)** : `Gemma-3 1B Instruct Q4_K_M` via `llama.cpp` ARM64 + **2 683 fiches uniques** SQLite FTS5 (`~2,6 ms`).

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
