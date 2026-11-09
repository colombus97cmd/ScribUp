# Plugins VST3 ScribUp pour FL Studio

Deux plugins, dans l'artefact `scribup-vst3` des builds GitHub Actions :

- **ScribUp Live** : l'écran live **dans FL Studio**. Sa fenêtre affiche en direct ce que tu fais sur le téléphone
  (ou la version web) : le texte que tu écris avec la ligne du curseur surlignée, le mode Live qui suit la lecture
  de FL, la conversation avec Claude (la réponse s'écrit en direct) et la grille d'accords. Plus besoin de passer
  d'une fenêtre à l'autre.
- **ScribUp Send** : envoie le son de FL au pont (analyse des accords, retour casque).

## ScribUp Live

1. Copie `ScribUp Live.vst3` dans `C:\Program Files\Common Files\VST3\`, puis FL Studio : **Options → Manage plugins → Find installed plugins**.
2. Mets-le dans n'importe quel emplacement d'effet du Mixer (il ne touche pas au son) et ouvre sa fenêtre.
   Tu peux l'agrandir (coin en bas à droite) et la détacher de FL (icône de la fenêtre du plugin) pour la mettre sur un autre écran.
3. Lance le pont (`pc-bridge/start_bridge.bat`) : le plugin s'y connecte tout seul (pas de PIN, c'est le même PC).

La fenêtre suit l'onglet ouvert sur le téléphone ; clique sur un onglet pour choisir toi-même (décoche « Suivre le téléphone »
pour revenir au suivi). A− / A+ règlent la taille du texte, gardée avec le projet FL.

# ScribUp Send

Petit plugin à placer sur la piste **Master** de FL Studio. Il ne modifie pas le son : il en envoie une copie
au pont ScribUp (`pc-bridge/`) sur le même PC. Le pont n'utilise alors **que le son de FL** (rien d'autre de Windows),
pour l'analyse des accords et le retour casque vers le téléphone.

Ça marche quel que soit le pilote audio de FL, y compris l'**ASIO d'une carte son (Focusrite…)**,
qui court-circuite Windows et que le pont ne pourrait pas entendre autrement.

## Installation

1. Récupère le dossier `ScribUp Send.vst3` (build GitHub Actions, artefact `scribup-send-vst3`).
2. Copie-le dans `C:\Program Files\Common Files\VST3\`.
3. FL Studio : **Options → Manage plugins → Find installed plugins**.
4. Mixer → piste **Master** → dernier emplacement d'effet → **ScribUp Send**.

Le bouton « Envoyer vers ScribUp » du plugin permet de couper l'envoi sans l'enlever.

## Technique

- Envoi UDP local vers `127.0.0.1:8766`, paquets de 256 frames stéréo float32 (format décrit dans `scribup_send/src/lib.rs`).
- Le fil audio ne fait que copier dans un tampon circulaire (pas d'allocation ni de réseau dans le fil audio).
- Construit avec [nih-plug](https://github.com/robbert-vdh/nih-plug) : `cargo xtask bundle scribup_send --release`.
  Les bindings VST3 de nih-plug sont sous GPLv3, le plugin l'est donc aussi.
