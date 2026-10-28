# ScribUp Android

Appli perso d'écriture (rap / chant, multilingue) avec Claude, synchronisée avec FL Studio.

## Installer l'APK sans Android Studio

Chaque push sur GitHub compile l'app (workflow **ScribUp (Android + pont PC)**) :

1. GitHub → onglet **Actions** → dernier run vert → artefact **scribup-apk** (zip contenant `app-debug.apk`).
2. Copie l'APK sur le téléphone, ouvre-le, autorise « installer des applis inconnues ».

Ou avec Android Studio : ouvre le dossier `android/` puis ▶ Run.

## Onglets d'un morceau

- **Écrire** : éditeur avec la syntaxe ScribUp (bouton ? pour l'aide) : `[Refrain]`, `[Couplet 2 @25]`,
  `@33 ligne`, `ligne ~2`, ad-libs `(yeah)`. Infos du morceau : titre, BPM, tonalité, langues, mesures par ligne.
- **Live** : les paroles défilent au rythme de FL Studio (mesure · temps), ligne en cours en gros,
  ad-libs en rose, accords de chaque mesure, nombre de syllabes. Toucher une ligne lance FL à cette mesure
  (avec pré-roll). Boutons lecture / stop / REC. Sans PC : horloge interne au BPM du morceau.
  L'écran reste allumé.
- **Claude** : chat avec le contexte du morceau (paroles, BPM, tonalité, grille). Raccourcis : rimes, ligne
  suivante, ad-libs, mixer les langues, flow, refrain, accords, topline, arrangement FL, corriger.
  Chaque réponse peut être copiée ou ajoutée au texte.
- **Accords** : accord et tonalité détectés en direct, chromagramme, gamme et accords de la tonalité,
  grille captée mesure par mesure (à enregistrer dans le morceau).

## Réglages

- Clé API Claude (console.anthropic.com) et modèle : Opus 5.5 (par défaut), Sonnet 5.5, Haiku 5.5.
- Pont PC : IP / port / PIN (voir `../pc-bridge/README.md`), détection automatique sur le Wi-Fi.
- Notes en Do Ré Mi, pré-roll, taille du texte en live.

Les morceaux sont des fichiers JSON dans les données privées de l'app.

## Développement

```bash
./gradlew testDebugUnitTest assembleDebug     # tests + APK
./gradlew connectedDebugAndroidTest           # test du SDK Claude sur appareil / émulateur
```
