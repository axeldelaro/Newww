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
