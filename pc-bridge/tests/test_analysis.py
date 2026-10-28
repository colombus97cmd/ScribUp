import numpy as np
import pytest

from bridge.analysis import BarGrid, HarmonyAnalyzer, detect_key, key_name
from bridge.protocol import FlState, apply_fl_update, encode_command, parse_fl_sysex
from bridge.synth import render_chord

SR = 48000


def feed(analyzer, audio, block=2048):
    for i in range(0, len(audio), block):
        analyzer.process(audio[i:i + block])


@pytest.mark.parametrize("chord", ["C", "Am", "F", "G", "Dm", "E", "Bb", "F#m", "G7", "Cmaj7", "Am7"])
def test_detects_chord(chord):
    analyzer = HarmonyAnalyzer(samplerate=SR)
    feed(analyzer, render_chord(chord, 1.5, SR, noise=0.01))
    snap = analyzer.snapshot()
    assert snap["active"]
    assert snap["chord"].split("/")[0] == chord, snap


def test_silence_has_no_chord():
    analyzer = HarmonyAnalyzer(samplerate=SR)
    feed(analyzer, np.zeros(SR, dtype=np.float32))
    snap = analyzer.snapshot()
    assert not snap["active"]
    assert snap["chord"] is None


def test_slash_chord_from_bass():
    analyzer = HarmonyAnalyzer(samplerate=SR)
    audio = render_chord("C", 1.5, SR, with_bass=False)
    t = np.arange(audio.size) / SR
    audio = audio + 0.3 * np.sin(2 * np.pi * 82.41 * t).astype(np.float32)  # E2 à la basse
    feed(analyzer, audio)
    assert analyzer.snapshot()["chord"] == "C/E"


@pytest.mark.parametrize("progression,expected", [
    (["Am", "F", "C", "G"], {"A minor", "C major"}),
    (["D", "G", "A", "Bm"], {"D major", "B minor"}),
    (["Cm", "Ab", "Eb", "Bb"], {"C minor", "Eb major"}),
])
def test_key_detection_on_progression(progression, expected):
    analyzer = HarmonyAnalyzer(samplerate=SR, key_time_constant=5.0)
    for _ in range(3):
        for chord in progression:
            feed(analyzer, render_chord(chord, 1.0, SR, noise=0.02))
    tonic, mode, _ = detect_key(analyzer.long_chroma)
    # une progression diatonique : la tonalité ou son relatif
    assert key_name(tonic, mode) in expected


def test_key_does_not_flip_between_relatives():
    # Am-F-C-G est ambigu (La mineur / Do majeur) : une fois choisie, la tonalité ne doit plus osciller.
    analyzer = HarmonyAnalyzer(samplerate=SR)
    keys = set()
    for loop in range(5):
        for chord in ["Am", "F", "C", "G"]:
            feed(analyzer, render_chord(chord, 2.0, SR))
            snap = analyzer.snapshot()
            if loop >= 2:
                keys.add(snap["key"])
    assert len(keys) == 1 and keys <= {"A minor", "C major"}, keys


def test_bar_grid_accumulates():
    analyzer = HarmonyAnalyzer(samplerate=SR)
    grid = BarGrid()
    for bar, chord in enumerate(["Am", "F", "C", "G"], start=1):
        feed(analyzer, render_chord(chord, 2.0, SR))
        grid.add(bar, analyzer.take_accumulated())
    assert grid.chords() == {1: "Am", 2: "F", 3: "C", 4: "G"}


def test_fl_sysex_roundtrip():
    payload = [0x7D, 0x53, 0x55] + [ord(c) for c in "p=1;r=0;t=140.000;k=1152;q=96;b=384;m=1"]
    update = parse_fl_sysex(payload)
    state = FlState()
    apply_fl_update(state, update, now=100.0)
    pos = state.position(now=100.0)
    assert state.connected and state.playing and state.bpm == 140.0
    assert pos == {"bar": 4, "beat": 1, "beatFrac": 0.0, "beatsPerBar": 4}
    # 0,5 s plus tard à 140 BPM : +1,1667 temps
    assert state.position(now=100.5)["beat"] == 2


def test_parse_rejects_foreign_sysex():
    assert parse_fl_sysex([0x00, 0x20, 0x29, 1, 2]) is None


def test_encode_command():
    data = encode_command("goto", 9)
    assert bytes(data[3:]).decode() == "cmd=goto&bar=9"
