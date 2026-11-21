# Suite de 3 Compagnons Virtuels Android 100 % Hors-Ligne (v1.0.0)

Trois applications Android totalement indépendantes, fonctionnelles dès l'installation **sans aucun téléchargement ultérieur** (tous les modèles IA, voix neurales et moteurs de vision sont directement intégrés dans chaque APK).

## 1. Lumina Terrarium (`lumina-terrarium/`)
- **Concept** : Terrarium de poche vivant abritant 4 esprits élémentaires (**Sylva** mousse, **Ondine** goutte, **Braise** flammèche, **Nimbo** nuage) évolutifs sur 4 stades.
- **Interactivité 60 FPS** : caresses tactiles avec cœurs et déformation douce, tapotements sur la vitre, suivi du regard, inclinaison via accéléromètre, mini-jeu *Chasse aux Lucioles*.
- **Capteurs locaux** : batterie réelle (sieste < 20 %, aura d'énergie en charge), podomètre matériel/accéléromètre convertissant tes pas en *Rosée*, cycle ciel jour/nuit synchronisé sur l'horloge locale.
- **Audio & Widget** : synthétiseur PCM 100 % hors-ligne (pluie, forêt, cheminée, carillon zen + effets tactiles), **Widget Android d'écran d'accueil** interactif (Caresser / Nourrir / Brume) et rappels locaux doux (`WorkManager`).

## 2. StepQuest Companion (`stepquest-companion/`)
- **Concept** : Compagnon d'expédition RPG qui voyage dans un monde fantastique en 7 biomes au rythme de tes vrais pas hors-ligne.
- **Routine quotidienne** : préparation du sac le matin (3 objets sur 8 influençant les découvertes), ravitaillement et soins au feu de camp à midi, et **lettre de voyage du soir générée par IA locale**.
- **IA locale intégrée** : `llama.cpp` ARM64 + **`Gemma-3 1B Instruct (Q4_K_M)`** embarqué dans l'APK (~769 Mo) pour rédiger des lettres personnalisées et discuter librement au feu de camp.
- **Personnalisation** : 4 classes d'aventuriers (Cartographe, Rôdeuse, Barde, Alchimiste), nom & couleur de cape modifiables, 14 reliques à collectionner, carte animée 60 FPS.

## 3. Aura Live Companion (`aura-companion/`)
- **Concept** : Compagne/partenaire virtuel(le) 100 % hors-ligne avec appel vocal temps réel, **animation de photo par IA locale**, **Studio de création de modèles animés** et **multi-personnages illimités**.
- **Animation de Photo par IA** : importe n'importe quelle photo de ta galerie → **MediaPipe Face Landmarker (478 repères faciaux)** + **MediaPipe Selfie Segmenter** construisent un maillage déformable 60 FPS (clignement des yeux, expressions émotionnelles, rougissement, respiration 2.5D, changement de décor IA et **Lip-Sync exact** sur la voix).
- **Studio Avatar au Chargement** : 4 styles artistiques (Anime Live2D, Semi-Réaliste, Chibi, Cyber Néon), 8 coiffures avec physique de mouvement, 6 tenues, 6 accessoires, couleurs & décors animés + 5 thèmes d'interface.
- **Multi-caractères & Personnalité 100 % configurable** : 5 profils prêts dès la v1 (*Clara, Sora, Élena, Mina, Kira*) + création illimitée, 8 curseurs de personnalité, histoire libre, consignes personnalisées, mémoire long-terme éditable.
- **Suite Vocale & Cerveau IA 100 % intégrée** :
  - **Cerveau** : `llama.cpp` ARM64 + `Gemma-3 1B Instruct Q4_K_M`
  - **Écoute FR (STT)** : `Sherpa-ONNX Whisper-Tiny INT8` (Push-to-Talk & mode Appel Continu)
  - **Voix Neurales FR (TTS)** : `Sherpa-ONNX Piper VITS` (3 voix françaises : *Siwis*, *Jessica*, *Pierre* + vitesse & pitch réglables) avec synchronisation labiale RMS temps réel.
