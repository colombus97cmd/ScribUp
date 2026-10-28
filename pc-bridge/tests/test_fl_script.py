"""Exécute le script FL Studio avec des modules FL simulés."""

import importlib.util
import sys
import types
from pathlib import Path

from bridge.protocol import encode_command, parse_fl_sysex

SCRIPT = Path(__file__).parent.parent / "fl_studio" / "ScribUp" / "device_ScribUp.py"


class FakeTransport:
    def __init__(self):
        self.playing = False
        self.calls = []

    def isPlaying(self): return self.playing
    def isRecording(self): return False
    def getSongPos(self, mode): return 1920
    def getLoopMode(self): return 1
    def start(self): self.calls.append("start"); self.playing = not self.playing
    def stop(self): self.calls.append("stop"); self.playing = False
    def record(self): self.calls.append("record")
    def setSongPos(self, value, mode=None): self.calls.append(("setSongPos", value, mode))


def load_script(monkeypatch):
    sent = []
    transport = FakeTransport()
    fakes = {
        "device": types.SimpleNamespace(isAssigned=lambda: True, midiOutSysex=lambda b: sent.append(bytes(b))),
        "general": types.SimpleNamespace(getRecPPQ=lambda: 96, getRecPPB=lambda: 384),
        "midi": types.SimpleNamespace(SONGLENGTH_ABSTICKS=2),
        "mixer": types.SimpleNamespace(getCurrentTempo=lambda: 140000),
        "transport": transport,
    }
    for name, module in fakes.items():
        monkeypatch.setitem(sys.modules, name, module)
    spec = importlib.util.spec_from_file_location("device_ScribUp", SCRIPT)
    script = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(script)
    return script, transport, sent


def test_sends_transport_state(monkeypatch):
    script, _, sent = load_script(monkeypatch)
    script.OnInit()
    for _ in range(4):
        script.OnIdle()
    assert sent, "aucun SysEx envoyé"
    assert sent[-1][0] == 0xF0 and sent[-1][-1] == 0xF7
    state = parse_fl_sysex(list(sent[-1][1:-1]))
    assert state == {"playing": False, "recording": False, "bpm": 140.0, "ticks": 1920,
                     "ppq": 96, "ppb": 384, "song_mode": True}


def test_handles_commands(monkeypatch):
    script, transport, _ = load_script(monkeypatch)

    class Event:
        def __init__(self, data):
            self.sysex = bytes([0xF0] + data + [0xF7])
            self.handled = False

    for action, bar in [("play", None), ("goto", 9), ("record", None), ("stop", None)]:
        event = Event(encode_command(action, bar))
        script.OnSysEx(event)
        assert event.handled
    assert transport.calls == ["start", ("setSongPos", 3072, 2), "record", "stop"]


def test_ignores_other_sysex(monkeypatch):
    script, transport, _ = load_script(monkeypatch)
    event = types.SimpleNamespace(sysex=bytes([0xF0, 0x00, 0x20, 0x29, 0x01, 0xF7]), handled=False)
    script.OnSysEx(event)
    assert not event.handled and transport.calls == []
