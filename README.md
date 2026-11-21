# Newww — Suite d'Applications Android Haut de Gamme 100 % Hors-Ligne

Ce dépôt contient **deux applications Android natives distinctes** (`Kotlin + Jetpack Compose + NDK C++`), conçues sans aucun serveur, sans compte, sans abonnement et sans dépendance cloud.

---

## 1. KINETIX Coach (`kinetix-coach/` — v1.3.0)
**Vision Biomécanique 3D Multi-Angles + 7 Défis & Musique + 42 Vidéos + Coach IA Local**

- **Package** : `com.kinetix.coach` (Android 10+ / `arm64-v8a`, 100 % hors-ligne, zéro requête réseau)
- **Pompes multi-signaux (6 variantes) & Audit Complet des 16 Exercices** (`BiomechanicsEngine` `Adaptatif` / `Strict`) :
  - Fusion flexion du bras actif + descente verticale épaules/poitrine vers la ligne des mains pour détecter sans faille les pompes **Classiques, Larges, Diamant, Genoux, Piquées et Archer** + seuils réels optimisés sur les 16 exercices (curls alternés, rowing unilatéral, tractions prise large, planche face/latérale/haute…).
- **7 Défis Caméra & Moteur Audio/Musique Hors-Ligne** (`ChallengeController`, `WorkoutAudioEngine`) :
  - **Pompes « Bip & Go »**, **Test Luc Léger** (12 paliers), **Gainage Chrono Musical**, **Défi 25/50/100 Reps**, **AMRAP**, **Tabata 20/10 × 8**, **EMOM** + Records personnels persistés (SQLite v3).
  - 6 bips de cadence + **4 musiques synthétisées PCM 44,1 kHz** (`Pulse 128`, `Focus 110`, `HIIT 142`, `Respiration 60`) + **import MP3/M4A/WAV/OGG local** depuis le téléphone.
- **Vision 3D Multi-Angles (`worldLandmarks` + One-Euro)** : détection auto des 16 exercices (`AUTO`/`FIXE`), calories personnalisées (Mifflin-St Jeor + travail mécanique), corrections visuelles/vocales au degré près (ligne fantôme, flèches, rapporteurs).
- **42 Vidéos Explicatives MP4 H.264** (100 % des 16 exos Vision + **911/911 Encyclopédie**, ralenti `0.5× / 1.0× / 1.5×`) + **LLM local `Gemma-3 1B Q4_K_M`** + **2 683 fiches SQLite FTS5** (`~2,6 ms`).

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
