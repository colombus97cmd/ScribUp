"""Entrées du compagnon : MIDI (FL Studio), son de FL (plugin), son de Windows, simulateur démo."""

from __future__ import annotations

import logging
import socket
import struct
import threading
import time

import numpy as np

from .protocol import FlState, apply_fl_update, encode_command, parse_fl_sysex
from .synth import render_chord

log = logging.getLogger("scribup")


class FlMidiLink:
    """Lit l'état FL (SysEx) sur un port MIDI virtuel et y renvoie les commandes."""

    def __init__(self, state: FlState, in_name: str, out_name: str):
        self.state = state
        self.in_name = in_name
        self.out_name = out_name
        self._in = None
        self._out = None
        self._lock = threading.Lock()

    @staticmethod
    def _find(names: list[str], wanted: str) -> str | None:
        wanted = wanted.lower()
        return next((n for n in names if wanted in n.lower()), None)

    def open(self) -> bool:
        try:
            import mido
        except ImportError:
            log.warning("mido / python-rtmidi absents : pas de liaison FL Studio")
            return False
        ins, outs = mido.get_input_names(), mido.get_output_names()
        in_port = self._find(ins, self.in_name)
        out_port = self._find(outs, self.out_name)
        if not in_port:
            log.warning("Port MIDI d'entrée '%s' introuvable. Ports disponibles : %s", self.in_name, ins)
            return False
        self._in = mido.open_input(in_port, callback=self._on_message)
        log.info("FL Studio -> PC : écoute sur '%s'", in_port)
        if out_port:
            self._out = mido.open_output(out_port)
            log.info("PC -> FL Studio : commandes sur '%s'", out_port)
        else:
            log.warning("Port MIDI de sortie '%s' introuvable : le téléphone ne pourra pas piloter FL", self.out_name)
        return True

    def _on_message(self, msg) -> None:
        if msg.type != "sysex":
            return
        update = parse_fl_sysex(msg.data)
        if update is not None:
            apply_fl_update(self.state, update)

    def send_command(self, action: str, bar: int | None = None) -> bool:
        if self._out is None:
            return False
        import mido
        with self._lock:
            self._out.send(mido.Message("sysex", data=encode_command(action, bar)))
        return True

    def close(self) -> None:
        for port in (self._in, self._out):
            if port is not None:
                port.close()


class AudioFanout:
    """Distribue chaque bloc audio : analyse harmonique + retour casque vers le téléphone."""

    ACTIVE_S = 1.0  # une source est « active » si elle a envoyé du son dans la dernière seconde

    def __init__(self, analyzer):
        self.analyzer = analyzer
        self.samplerate = analyzer.samplerate
        self.listeners: list = []  # fonctions appelées avec un bloc stéréo float32 (frames, 2)
        self._source: str | None = None
        self._last_push = 0.0

    def push(self, block: np.ndarray, source: str) -> None:
        self._source = source
        self._last_push = time.monotonic()
        block = np.asarray(block, dtype=np.float32)
        if block.ndim == 1:
            block = block[:, None]
        self.analyzer.process(block.mean(axis=1))
        if self.listeners:
            stereo = np.repeat(block, 2, axis=1) if block.shape[1] == 1 else block[:, :2]
            for listener in self.listeners:
                listener(stereo)

    def active_source(self) -> str | None:
        """« plugin », « windows » ou « demo » si du son arrive en ce moment, sinon None."""
        return self._source if time.monotonic() - self._last_push < self.ACTIVE_S else None


# --- Son de FL via le plugin ScribUp Send -------------------------------------------------

PLUGIN_PORT = 8766
_PLUGIN_HEADER = struct.Struct("<4sIHHI")  # « SCRB », fréquence, canaux, frames, numéro


def parse_plugin_packet(data: bytes) -> tuple[int, np.ndarray] | None:
    """Décode un paquet du plugin : (fréquence, échantillons float32 de forme (frames, canaux))."""
    if len(data) < _PLUGIN_HEADER.size:
        return None
    magic, rate, channels, frames, _seq = _PLUGIN_HEADER.unpack_from(data)
    if magic != b"SCRB" or channels == 0 or rate == 0:
        return None
    expected = _PLUGIN_HEADER.size + frames * channels * 4
    if len(data) != expected:
        return None
    samples = np.frombuffer(data, dtype="<f4", offset=_PLUGIN_HEADER.size).reshape(frames, channels)
    return rate, samples


class LinearResampler:
    """Rééchantillonnage en continu (interpolation linéaire), bloc par bloc, sans saut entre blocs."""

    def __init__(self, src_rate: int, dst_rate: int):
        self.src_rate = src_rate
        self.dst_rate = dst_rate
        self._step = src_rate / dst_rate
        self._pos = 0.0     # position (en échantillons source) de la prochaine sortie, depuis _tail
        self._tail = None   # dernier échantillon du bloc précédent

    def process(self, block: np.ndarray) -> np.ndarray:
        if self.src_rate == self.dst_rate:
            return block
        buf = block if self._tail is None else np.concatenate([self._tail, block])
        last = len(buf) - 1
        count = int(np.floor((last - self._pos) / self._step)) + 1 if last >= self._pos else 0
        positions = self._pos + np.arange(count) * self._step
        index = np.floor(positions).astype(int)
        frac = (positions - index)[:, None]
        out = buf[index] * (1 - frac) + buf[np.minimum(index + 1, last)] * frac
        self._pos += count * self._step - last
        self._tail = buf[-1:]
        return out.astype(np.float32)


class PluginReceiver(threading.Thread):
    """Reçoit le son envoyé par le plugin ScribUp Send placé sur le Master de FL Studio."""

    def __init__(self, fanout: AudioFanout, port: int = PLUGIN_PORT):
        super().__init__(daemon=True, name="plugin-fl")
        self.fanout = fanout
        self.port = port
        self._stop = threading.Event()
        self._resampler: LinearResampler | None = None

    def handle(self, data: bytes) -> bool:
        packet = parse_plugin_packet(data)
        if packet is None:
            return False
        rate, samples = packet
        if self._resampler is None or self._resampler.src_rate != rate:
            log.info("Son reçu du plugin FL (%d Hz)", rate)
            self._resampler = LinearResampler(rate, self.fanout.samplerate)
        block = self._resampler.process(samples)
        if len(block):
            self.fanout.push(block, source="plugin")
        return True

    def run(self) -> None:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.bind(("127.0.0.1", self.port))
            sock.settimeout(0.5)
            log.info("En attente du son de FL : place le plugin « ScribUp Send » sur le Master")
            while not self._stop.is_set():
                try:
                    data, _ = sock.recvfrom(65536)
                except socket.timeout:
                    continue
                except OSError:  # Windows signale parfois une erreur réseau sans gravité
                    continue
                self.handle(data)

    def stop(self) -> None:
        self._stop.set()


class LoopbackCapture(threading.Thread):
    """Capture tout ce que joue Windows (loopback WASAPI), par blocs de 10 ms.

    Seulement avec --source windows : sinon on n'utilise que le son de FL (plugin).
    Ne voit pas le son de FL quand FL utilise l'ASIO d'une carte son.
    """

    def __init__(self, fanout: AudioFanout, block: int = 480, device: str | None = None):
        super().__init__(daemon=True, name="audio-capture")
        self.fanout = fanout
        self.samplerate = fanout.samplerate
        self.block = block
        self.device = device
        self._stop = threading.Event()

    def _microphone(self):
        import soundcard as sc
        if self.device:
            mics = sc.all_microphones(include_loopback=True)
            match = next((m for m in mics if self.device.lower() in m.name.lower()), None)
            if match is None:
                raise RuntimeError(f"périphérique audio '{self.device}' introuvable : {[m.name for m in mics]}")
            return match
        speaker = sc.default_speaker()
        return sc.get_microphone(id=str(speaker.name), include_loopback=True)

    def run(self) -> None:
        try:
            mic = self._microphone()
        except Exception as exc:  # soundcard absent, pas de carte son, etc.
            log.error("Capture audio indisponible : %s", exc)
            return
        log.info("Capture audio : '%s'", mic.name)
        with mic.recorder(samplerate=self.samplerate, channels=2, blocksize=self.block) as rec:
            while not self._stop.is_set():
                self.fanout.push(rec.record(numframes=self.block), source="windows")

    def stop(self) -> None:
        self._stop.set()


class DemoSource(threading.Thread):
    """Simule FL Studio + un beat (Am F C G) pour tester l'app sans PC de studio."""

    PROGRESSION = ["Am", "F", "C", "G"]

    def __init__(self, state: FlState, fanout: AudioFanout, bpm: float = 90.0):
        super().__init__(daemon=True, name="demo")
        self.state = state
        self.fanout = fanout
        self.bpm = bpm
        self.playing = True
        self._ticks = 0.0
        self._stop = threading.Event()

    def command(self, action: str, bar: int | None = None) -> bool:
        if action in ("play",):
            self.playing = True
        elif action in ("stop",):
            self.playing = False
            self._ticks = 0.0
        elif action == "toggle":
            self.playing = not self.playing
        elif action == "goto" and bar:
            self._ticks = (bar - 1) * self.state.ppb
        return True

    def run(self) -> None:
        sr, block = self.fanout.samplerate, 480  # blocs de 10 ms, comme la vraie capture
        dt = block / sr
        phase = 0.0
        next_time = time.monotonic()
        while not self._stop.is_set():
            if self.playing:
                self._ticks += dt * self.bpm / 60.0 * self.state.ppq
                bar = int(self._ticks // self.state.ppb)
                chord = self.PROGRESSION[bar % len(self.PROGRESSION)]
                self.fanout.push(render_chord(chord, dt, sr, start_phase=phase), source="demo")
            else:
                self.fanout.push(np.zeros(block, dtype=np.float32), source="demo")
            phase += dt
            apply_fl_update(self.state, {
                "playing": self.playing, "recording": False, "bpm": self.bpm,
                "ticks": int(self._ticks), "ppq": 96, "ppb": 384, "song_mode": True,
            })
            next_time += dt
            time.sleep(max(0.0, next_time - time.monotonic()))

    def stop(self) -> None:
        self._stop.set()
