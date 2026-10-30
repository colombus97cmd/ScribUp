# ScribUp Send (plugin VST3 pour FL Studio)

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
