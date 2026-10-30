"""Charge le VST3 compilé dans un vrai hôte audio (pedalboard) et vérifie, comme dans FL Studio :
- que le plugin laisse passer le son sans le modifier ;
- que le pont ScribUp reçoit ce son (et y reconnaît l'accord joué).

Usage : python ci/check_in_host.py "target/bundled/ScribUp Send.vst3"
"""

import sys
import time
from pathlib import Path

import numpy as np
from pedalboard import load_plugin

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "pc-bridge"))
from bridge.analysis import HarmonyAnalyzer  # noqa: E402
from bridge.sources import AudioFanout, PluginReceiver  # noqa: E402
from bridge.synth import render_chord  # noqa: E402

SAMPLE_RATE = 44100
BLOCK = 512


def main(plugin_path: str) -> None:
    fanout = AudioFanout(HarmonyAnalyzer(samplerate=48000))
    received = []
    fanout.listeners.append(received.append)
    receiver = PluginReceiver(fanout)
    receiver.start()
    time.sleep(0.3)

    plugin = load_plugin(str(Path(plugin_path).resolve()))
    print("Plugin chargé :", plugin.name)

    mono = render_chord("Am", 3.0, SAMPLE_RATE)
    stereo = np.stack([mono, mono])  # pedalboard : (canaux, échantillons)
    outputs = []
    for start in range(0, stereo.shape[1], BLOCK):
        outputs.append(plugin.process(stereo[:, start:start + BLOCK], SAMPLE_RATE, reset=False))
        time.sleep(BLOCK / SAMPLE_RATE)  # rythme temps réel, comme dans FL
    time.sleep(0.5)
    receiver.stop()

    output = np.concatenate(outputs, axis=1)
    assert np.allclose(output, stereo[:, :output.shape[1]], atol=1e-6), "le plugin modifie le son"

    frames = sum(len(block) for block in received)
    chord = fanout.analyzer.snapshot()["chord"]
    print(f"Pont : {frames} frames reçues ({frames / 48000:.2f} s), source {fanout.active_source()}, accord {chord}")
    assert frames > 2 * 48000, "le pont n'a pas reçu le son du plugin"
    assert fanout.active_source() == "plugin"
    assert chord == "Am", chord
    print("OK : le plugin transmet le son de FL au pont")


if __name__ == "__main__":
    main(sys.argv[1])
