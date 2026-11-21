# Newww — Suite d'Applications Android Haut de Gamme 100 % Hors-Ligne

Ce dépôt contient **deux applications Android natives distinctes** (`Kotlin + Jetpack Compose + NDK C++`), conçues sans aucun serveur, sans compte, sans abonnement et sans dépendance cloud.

---

## 1. KINETIX Coach (`kinetix-coach/` — v2.2.0)
**Vision Biomécanique 3D Pro 60 FPS + VBT Vitesse & Estimation Rep Max + Variantes sur 16 Exos + Coaching Anti-Spam + Coach IA Local**

- **Package** : `com.kinetix.coach` (Android 10+ / `arm64-v8a`, 100 % hors-ligne, zéro requête réseau)
- **Pompes Blindées & Audit Complet des 16 Exercices** (`BiomechanicsEngine`, `AutoExerciseDetector`, `PoseSanityFilter`, `PoseLandmarkerHelper`) :
  - Correction complète des pompes sous tous les angles (face posée au sol inclinée, 3/4, profil pur avec côté éloigné occlus, dos), suppression du blocage `adaptivePushupTopHeight` / `adaptiveDipTopHeight` (fenêtre glissante auto-calibrante + compression 3D bras + cross-check coudes 2D/3D + touch-and-go explosif + soft-lockout), `PoseSanityFilter` immunisé contre le raccourcissement en perspective, double-buffer zéro-GC `rowStride` Snapdragon + alignement CameraX 4:3, réveil rapide `Planche → Pompes` et **9 variantes pompes** (*Classique, Large, Serrée, Diamant, Genoux, Piquée, Archer, Inclinée, Déclinée*).
  - **Détection automatique de chaque variante sur les 16 exercices** (`ExerciseVariantDetector`) avec adaptation biomécanique réelle : *Sumo / Serré / ATG / Pistol / Sauté* (Squat, sans confusion Fentes), *Quadriceps / Fessiers* (Bulgare jambe avant au sol), *Conventionnel / Sumo / RDL / Unilatéral* (Soulevé de terre), *Strict / Alterné / Unilatéral / Push Press / Assis* (Développé militaire), *Triceps / Pectoraux / Banc* (Dips), *Haute / Avant-bras / Latérale / Genoux / 3 Appuis* (Planche), *Prise large / Serrée / Archer / L-Sit* (Tractions), *Pendlay / Yates / Unilatéral* (Rowing), *Unilatéral* (Pont fessier), *Suspendu / Alterné / Genoux fléchis* (Relevé de jambes), *Alterné / Oiseau / Frontale* (Élévations latérales).
- **Coaching Postural Anti-Spam Pro (`PostureFeedbackCoach` + `VoiceCoach`)** : persistance temporelle avant affichage (`≥ 360 ms` faute / `≥ 560 ms` vigilance), maintien anti-clignotement (`950 ms`), **zéro harcèlement en cours de descente**, priorité visuelle unique, cooldowns vocaux stricts (`≥ 9,5 s` global, `≥ 20 s` & `≥ 2 reps` même défaut, max `3/min`, silence après annonce de rep).
- **Système VBT — Vitesse d'Exécution Progressive & Estimation Rep Max (`VelocityEstimator`)** : mesure la vitesse concentrique moyenne et pic (`m/s`) par répétition (insensible aux pauses entre reps), calcule la perte de vitesse (`VL%`), la fraîcheur inter-séries, la zone de fatigue, le **RIR / RPE** et le **Rep Max estimé (`MAX ~X`)** avec sparkline live dans le HUD, bilan post-séance multi-séries, persistance SQLite v4 et débriefing Coach IA.
- **Écosystème complet** : **7 Défis caméra** (*Bip & Go*, *Luc Léger*, *Gainage Musical*, *25/50/100 Reps*, *AMRAP*, *Tabata*, *EMOM*) + 4 musiques PCM + **import MP3 local**, calories personnalisées, **42 vidéos MP4 H.264**, **LLM local `Gemma-3 1B Q4_K_M`** + **2 683 fiches SQLite FTS5** (**60/60 tests unitaires**).

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
