# Pont PC ScribUp (FL Studio ⇄ téléphone)

Le pont tourne sur le PC où tu utilises FL Studio. Il :

- lit la **position, le tempo, la signature, lecture/enregistrement** de FL Studio grâce à un petit script MIDI ;
- **écoute le son du PC** (ce que FL joue) et détecte l'**accord**, la **basse** et la **tonalité** en direct ;
- construit une **grille d'accords mesure par mesure** (plus la boucle tourne, plus elle est fiable) ;
- envoie tout ça à l'app Android par le Wi-Fi, et reçoit les commandes du téléphone (lecture, stop, REC, aller à une mesure).

```
FL Studio ──(script MIDI, port loopMIDI "ScribUp FL")──▶ scribup_bridge.py ──(Wi-Fi, WebSocket)──▶ téléphone
FL Studio ◀─(port loopMIDI "ScribUp CMD")── scribup_bridge.py ◀── commandes du téléphone
son du PC ──(capture « loopback » Windows)──▶ analyse des accords / tonalité
```

## 1. Créer deux ports MIDI virtuels (loopMIDI)

1. Installe [loopMIDI](https://www.tobias-erichsen.de/software/loopmidi.html) (gratuit).
2. Crée deux ports nommés exactement :
   - `ScribUp FL` (FL → PC)
   - `ScribUp CMD` (PC → FL)

## 2. Installer le script dans FL Studio

1. Copie le dossier `fl_studio/ScribUp` dans
   `Documents\Image-Line\FL Studio\Settings\Hardware\`
   (tu dois avoir `...\Hardware\ScribUp\device_ScribUp.py`).
2. Ouvre FL Studio → **Options → MIDI settings** :
   - Dans **Input**, sélectionne `ScribUp CMD`, active-le, choisis **Controller type : ScribUp Bridge** et mets un **Port** (ex. `10`).
   - Dans **Output**, sélectionne `ScribUp FL` et mets **le même numéro de port** (`10`).
3. Dans **View → Script output** tu dois voir « ScribUp Bridge : prêt ».

## 3. Lancer le pont

Il faut Python 3.10+ ([python.org](https://www.python.org/downloads/), coche « Add to PATH »).

- Windows : double-clique sur `start_bridge.bat` (le premier lancement installe les dépendances).
- Ou à la main :

```bash
pip install -r requirements.txt
python scribup_bridge.py
```

Au démarrage il affiche l'adresse et le **PIN** :

```
Pont ScribUp prêt : ws://192.168.1.20:8765 (PIN 482913)
```

Options utiles :

| Option | Rôle |
|---|---|
| `--demo` | simule FL + un beat (Am F C G à 90 BPM) pour tester l'app sans FL |
| `--list` | liste les ports MIDI et les sources audio |
| `--audio-device "Speakers"` | écoute un périphérique précis (par défaut : la sortie par défaut de Windows) |
| `--latency-ms 80` | décale l'analyse pour aligner la grille sur les mesures |
| `--pin 1234` / `--no-pin` | choisir / désactiver le PIN |
| `--port 8765` | port réseau |

Le PIN est mémorisé dans `%USERPROFILE%\.scribup_bridge.json`.

> Pare-feu Windows : au premier lancement, autorise Python sur les **réseaux privés**, sinon le téléphone ne pourra pas se connecter.

## 4. Dans l'app

Réglages → section **PC / FL Studio** : l'app cherche le PC sur le Wi-Fi (sinon tape l'IP), entre le PIN, **Connecter**.
Le badge passe au vert « FL » quand FL Studio est détecté.

## Notes

- La détection d'accords marche par comparaison du spectre à des modèles d'accords (majeur, mineur, 7, maj7, m7, dim, sus2, sus4, renversements par la basse). Sur un beat chargé (808 saturée, gros drums) elle peut hésiter : la grille par mesure, cumulée sur plusieurs boucles, est plus fiable que l'accord instantané.
- La capture audio utilise le « loopback » WASAPI (Windows). Sous macOS il faut un périphérique virtuel type BlackHole et `--audio-device BlackHole`.
- Tests : `python -m pytest tests`.
