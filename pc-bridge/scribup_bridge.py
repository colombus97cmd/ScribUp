"""Compagnon PC de ScribUp.

Relie FL Studio (via un port MIDI virtuel loopMIDI) et le son du PC à l'application
Android : position dans le morceau, tempo, accord joué, tonalité, grille d'accords.

    python scribup_bridge.py            # mode normal
    python scribup_bridge.py --demo     # simulateur, pour tester l'app sans FL Studio
    python scribup_bridge.py --list     # liste les ports MIDI et périphériques audio
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import random
from pathlib import Path

from bridge.analysis import HarmonyAnalyzer
from bridge.protocol import FlState
from bridge.server import PROTOCOL_VERSION, BridgeServer
from bridge.sources import AudioFanout, DemoSource, FlMidiLink, LoopbackCapture, PluginReceiver

CONFIG_PATH = Path.home() / ".scribup_bridge.json"


def load_pin(args) -> str | None:
    if args.no_pin:
        return None
    if args.pin:
        return args.pin
    config = {}
    if CONFIG_PATH.exists():
        try:
            config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        except ValueError:
            config = {}
    if not config.get("pin"):
        config["pin"] = f"{random.randint(0, 999999):06d}"
        CONFIG_PATH.write_text(json.dumps(config, indent=2), encoding="utf-8")
    return config["pin"]


def list_devices() -> None:
    try:
        import mido
        print("Entrées MIDI :", mido.get_input_names())
        print("Sorties MIDI :", mido.get_output_names())
    except ImportError:
        print("mido / python-rtmidi non installés")
    try:
        import soundcard as sc
        print("Sources audio (loopback incluses) :")
        for mic in sc.all_microphones(include_loopback=True):
            print("  -", mic.name)
        print("Sortie par défaut :", sc.default_speaker().name)
    except Exception as exc:
        print("soundcard indisponible :", exc)


def main() -> None:
    parser = argparse.ArgumentParser(description="Pont FL Studio <-> ScribUp (Android)")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--pin", help="PIN à saisir dans l'app (sinon généré et mémorisé)")
    parser.add_argument("--no-pin", action="store_true", help="désactive le PIN")
    parser.add_argument("--midi-in", default="ScribUp FL", help="port loopMIDI où FL envoie son état")
    parser.add_argument("--midi-out", default="ScribUp CMD", help="port loopMIDI vers lequel envoyer les commandes")
    parser.add_argument("--source", choices=["plugin", "windows"], default="plugin",
                        help="son analysé et envoyé au téléphone : « plugin » = uniquement le son de FL via le "
                             "plugin ScribUp Send (défaut) ; « windows » = tout ce que joue Windows")
    parser.add_argument("--audio-device", help="avec --source windows : nom (partiel) du périphérique à écouter")
    parser.add_argument("--latency-ms", type=float, default=80.0, help="latence audio pour aligner la grille d'accords")
    parser.add_argument("--demo", action="store_true", help="simule FL Studio et un beat")
    parser.add_argument("--list", action="store_true", help="liste ports MIDI et sources audio")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s  %(message)s", datefmt="%H:%M:%S")
    if args.list:
        list_devices()
        return

    log = logging.getLogger("scribup")
    sound = "simulateur" if args.demo else ("tout le son de Windows" if args.source == "windows"
                                            else "uniquement le son de FL (plugin ScribUp Send)")
    log.info("Pont ScribUp v%d — son : %s", PROTOCOL_VERSION, sound)

    state = FlState()
    analyzer = HarmonyAnalyzer(samplerate=48000)
    fanout = AudioFanout(analyzer)
    if args.demo:
        commander = DemoSource(state, fanout)
        source = commander
    else:
        commander = FlMidiLink(state, args.midi_in, args.midi_out)
        commander.open()
        if args.source == "windows":
            source = LoopbackCapture(fanout, device=args.audio_device)
        else:
            source = PluginReceiver(fanout)  # uniquement le son de FL, rien d'autre

    server = BridgeServer(state, analyzer, commander, load_pin(args), latency_ms=args.latency_ms,
                          audio_rate=fanout.samplerate, audio_source=fanout.active_source)
    fanout.listeners.append(server.push_audio)  # retour casque vers le téléphone
    source.start()
    workers = [source]
    try:
        asyncio.run(server.run(args.host, args.port))
    except KeyboardInterrupt:
        pass
    finally:
        for worker in workers:
            worker.stop()
        if not args.demo:
            commander.close()


if __name__ == "__main__":
    main()
