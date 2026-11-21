# Newww — Suite d'Applications Android Haut de Gamme 100 % Hors-Ligne

Ce dépôt contient **deux applications Android natives distinctes** (`Kotlin + Jetpack Compose + NDK C++`), conçues sans aucun serveur, sans compte, sans abonnement et sans dépendance cloud.

---

## 1. KINETIX Coach (`kinetix-coach/` — v2.0.0)
**Vision Biomécanique 3D Anti-Glitch 60 FPS + 7 Défis & Musique + 42 Vidéos + Coach IA Local**

- **Package** : `com.kinetix.coach` (Android 10+ / `arm64-v8a`, 100 % hors-ligne, zéro requête réseau)
- **Détection Pro Anti-Glitch & Haute Fluidité** (`PoseSanityFilter`, `CameraViewpointTracker`, `AutoExerciseDetector`, `BiomechanicsEngine`) :
  - Rejet physique des téléportations MediaPipe, maintien sur micro-occlusions (`< 180 ms`), garde de déplacement (*« Stabilisation… »*), hystérésis `±6°` sur l’angle caméra, garde physique de posture, durée minimale de répétition, verrouillage asymétrique anti-faux-switch (*Pompes ↔ Planche*, faux *Burpees* éliminés) + **cadenas 1-tap**.
  - `ImageAnalysis` `640×480`, `PreviewView` matériel `PERFORMANCE`, tampon Bitmap **zéro GC**, backpressure `isInferring`, miroir caméra frontale anatomiquement exact, `pipelineLock` thread-safe, overlay squelette isolé haute fréquence, `FLAG_KEEP_SCREEN_ON` en Vision.
- **Pompes multi-signaux (6 variantes) & Audit Complet des 16 Exercices** (`Adaptatif` / `Strict`) : Classiques, Larges, Diamant, Genoux, Piquées et Archer + 15 autres exercices calibrés terrain.
- **7 Défis Caméra & Moteur Audio/Musique Hors-Ligne** : *Bip & Go*, *Luc Léger*, *Gainage Musical*, *25/50/100 Reps*, *AMRAP*, *Tabata*, *EMOM* + Records SQLite v3 + 4 musiques PCM 44,1 kHz + **import MP3/M4A/WAV/OGG local**.
- **Calories personnalisées**, corrections visuelles/vocales au degré près (ligne fantôme, flèches, rapporteurs), **42 Vidéos MP4 H.264** (911/911 exos), **LLM local `Gemma-3 1B Q4_K_M`** + **2 683 fiches SQLite FTS5**.

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
