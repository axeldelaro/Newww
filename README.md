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

## 3. ZAAGFORGE (`zaagforge/` — v4.0.0 Mainstage Banger Pro)
**Studio Automatique de Remix Mainstage — Hardtek Darktek/Maissouille / Vrai Frenchcore Dr. Peacock / Rebelion PVC-Zaag / Cosy Nightmare / UOK Piep / Hard Techno / Techno Normale / Gabber 909 — Moteur Multi-Cœurs C++20**

- **Package** : `com.zaagforge.studio` (`arm64-v8a`, Android 10+, session audio native C++ zéro OOM jusqu'à 8 min, `361/361` assertions C++20 + `8/8` tests JVM)
- **Zéro « Brouillon » — Qualité Mainstage Festival Automatisée** :
  - **`CenterVocalIsolator` Anti-Brouillon en Drop** (curseur `ISOLATION VOIX EN DROP`) : isole la voix centrale, coupe jusqu'à `-10 dB` d'instruments stéréo latéraux et `-8,5 dB` de bas-médiums (`bassCut..680 Hz`) pendant les drops (zéro double-batterie ni bouillie sonore) tout en conservant l'Intro et les Breaks en stéréo pleine largeur.
  - **Suivi d'Accords Mesure par Mesure (`barRootOffsets`) & 7 Kicks Diatoniques en Cache** : analyse harmonique FFT rapide de la basse de votre MP3 pour que les kicks **Dr. Peacock / Sefa**, **Darktek / Maissouille** et **Cosy Nightmare** ainsi que les stabs rave Hardtek suivent exactement les vrais accords du morceau (zéro fausse note).
  - **Vraie Dynamique Festival (Respiration & Tension)** : **Pre-Drop Vocal Solo Breath** (coupure nette du build-up 1 à 1,5 temps avant le Drop 1 + `ReverseCrash` + voix sèche au 1er plan), **Beat-4 Kick-Cuts** avec réouverture automatique du sidechain réel sur l'enveloppe du kick, et transitions `ReverseCrash` + `SubImpact` sur chaque Kick-Switch.
- **7 Vrais Thèmes Artistes, 16 Presets Têtes d'Affiche, 15 Kicks C++20 & Customisation Totale** : **Hardtek / Tribecore** (*Darktek*, *Maissouille*), **Vrai Frenchcore** (*Dr. Peacock / Sefa* kick 2-stage boisé + tail harmonique), **Overdose Rawstyle** (*Rebelion PVC-Zaag*, *Dual Damage*), **Uptempo Mainstage** (*Cosy Nightmare*, *Unicorn On Ketamine*, *Noiseflow*, *Dimitri K*, *GPF*), **Vrai Gabber 909** (*Angerfist*), **Hard Techno** (*Sara Landry*, *Klangkuenstler*) et **Techno Normale** (*Charlotte de Witte*, *999999999*), avec thèmes LED dynamiques et **sauvegarde de Presets Perso**.
- **Moteur Multi-Cœurs Ultra-Turbo (< 2 s sur 4 min) & Vitesse Jamais Ralentie (`>= 1.00×`)** : décodage `MediaCodec` non-bloquant, rendu fusionné **L2-cache-hot jusqu'à 6 cœurs ARM64**, compensation `0,00 ms` de latence du limiter Lookahead `O(1)`, barre de progression live `0–100 %`, overlay anti-clic-à-travers + `ANNULER` instantané, et exports `M4A 256k` / `WAV 16-bit` vers `Music/ZaagForge/`.

---

## 4. StrikeCam Arcade (`strikecam/` — v1.0.0)
**Jeu d'Arcade Rythme-Boxe Full-Body Caméra (Beat Saber × BoxVR) — 60–120 FPS + FFT C++20**

- **Package** : `com.strikecam.arcade` (`arm64-v8a`, Android 10+, MediaPipe Pose + CameraX `4:3` pixel-perfect)
- **Gameplay Rythme-Boxe Caméra** : jabs poing gauche rouge / droit cyan, **Slashes directionnels (`↑ ↓ ← →`, bonus `×1.5`)**, **Double Power Strikes (`×1.5`)**, **Murs Laser d'Esquive** (*Baisse-toi !*, *Esquive ← / →*), auto-calibration `3-2-1`, combo multiplicateur `1× → 8× FEVER`, modes **Solo Arcade**, **Survie (3 vies + accélération jusqu'à `1.60×`)** et **Duel 2 Joueurs**.
- **Synergie ZAAGFORGE & Streaming** : 5 pistes synthétisées C++20 (*Neon Velocity 135* → *Piep Boss 215 BPM*), **détection automatique prioritaire de vos remixes `Music/ZaagForge/`**, import MP3 local avec beatmap FFT 3-bandes automatique, recherche **Deezer 30s FFT**, et **Mode Live Sync Spotify / Deezer / SoundCloud** (*Tap-Sync*, phase `±50ms`, *Micro Beat Tracker C++20*, télécommande média).

---

## 5. CleanForge Pro (`cleanforge/` — v1.0.0 Windows 10/11 64-bit `.exe`)
**Nettoyeur Profond Multi-Disques, Explorateur Total, Mode Fin d'Études & Optimiseur Windows 100 % Sûr et Réversible**

- **Exécutable Portable** : `CleanForge-Pro-v1.0.0-win-x64.exe` (C# / .NET 8 WPF autonome, zéro installation, zéro runtime requis, `76/76` tests xUnit + `--self-test` live validé sur Windows réel)
- **Tout Détecter & Nettoyer Bien Mieux que Windows** :
  - **Explorateur Total du PC & Recherche Universelle** : cartographie multi-cœurs de tous les disques (`C:\`, `D:\`, etc.), arborescence proportionnelle par taille réelle, recherche instantanée par nom/extension/taille et Top 250 des plus gros fichiers.
  - **60+ Zones de Caches Profonds** : Windows Temp/Update/CrashDumps/ThumbCache, **Shaders GPU** (NVIDIA/AMD/Intel), **caches Navigateurs seuls** (Chrome/Edge/Brave/Opera/Vivaldi/Firefox — **zéro mot de passe, cookie, favori ou historique touché**), Discord, Spotify, Steam, Epic, OBS, Adobe, VS Code, npm/pip/Gradle/Maven/NuGet.
  - **Mode Spécial « Fin d'Études / Nouveau Départ »** : repère `node_modules`, `.gradle`, `venv`, `__pycache__`, `target`, `bin/obj`, vieux installeurs `.exe/.msi/.iso` oubliés et anciens documents `> 6 mois` (**revue 100 % manuelle**, jamais cochés par défaut).
  - **Doublons Exact SHA-256** (3 passes, original le plus propre automatiquement verrouillé), **Logiciels Fantômes & Orphelins** (audit 4 ruches Registre + dossiers inactifs `AppData`/`ProgramData`), **Raccourcis Morts `.lnk`/`.url`** (parseur binaire MS-SHLLINK sans faux positifs USB) et **Dossiers Vides**.
- **Forteresse `SafetyGuard` & Optimiseur Sûr (0 % Risque Matériel)** :
  - Bloque au niveau moteur `System32`, `SysWOW64`, `WinSxS`, `Boot`, `EFI`, `Windows Defender`, pilotes, `NTUSER.DAT`, `.ssh`, `.kdbx`, mots de passe navigateurs, jonctions NTFS et fichiers Cloud OneDrive/iCloud.
  - **Corbeille Windows par défaut** (`SHFileOperationW FOF_ALLOWUNDO`) + **Mode Simulation (à blanc)** + journal d'audit.
  - **4 Profils d'Optimisation** (*Gaming & FPS*, *Silence & Autonomie*, *Studio Audio/Dev*, *Vie Privée & Zéro Pub*) + Gestionnaire de Démarrage sécurisé + **sauvegarde JSON auto & Restauration 1-clic** (zéro overclocking, zéro tension, zéro BIOS).

---

## Installation des Exécutables (APK Android & EXE Windows)
Les binaires prêts à lancer (APK Android ARM64 signés v2 et `.exe` Windows 64-bit portables) sont publiés dans **[Releases](https://github.com/axeldelaro/Newww/releases)**.
