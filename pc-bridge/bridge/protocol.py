"""Messages échangés entre FL Studio (SysEx) et le compagnon, et calcul de la position musicale."""

from __future__ import annotations

from dataclasses import dataclass, field
import time

SYSEX_HEADER = (0x7D, 0x53, 0x55)  # sans F0/F7 (mido les retire)


@dataclass
class FlState:
    connected: bool = False
    playing: bool = False
    recording: bool = False
    bpm: float = 120.0
    ticks: int = 0
    ppq: int = 96
    ppb: int = 384
    song_mode: bool = True
    updated_at: float = field(default_factory=time.monotonic)

    @property
    def beats_per_bar(self) -> int:
        return max(1, round(self.ppb / self.ppq)) if self.ppq else 4

    def position(self, latency_ms: float = 0.0, now: float | None = None) -> dict:
        """Position (mesure, temps) en extrapolant depuis le dernier message reçu.

        FL n'envoie qu'une vingtaine de messages par seconde ; on extrapole avec le tempo
        pour une position fluide, puis on retire la latence audio pour aligner l'analyse.
        """
        now = time.monotonic() if now is None else now
        ticks = float(self.ticks)
        if self.playing:
            elapsed = max(0.0, min(now - self.updated_at, 0.5))
            ticks += elapsed * self.bpm / 60.0 * self.ppq
        ticks -= latency_ms / 1000.0 * self.bpm / 60.0 * self.ppq
        ticks = max(0.0, ticks)
        ppb = self.ppb or self.ppq * 4
        bar = int(ticks // ppb) + 1
        in_bar = ticks - (bar - 1) * ppb
        beat_float = in_bar / self.ppq
        return {
            "bar": bar,
            "beat": int(beat_float) + 1,
            "beatFrac": round(beat_float - int(beat_float), 3),
            "beatsPerBar": self.beats_per_bar,
        }

    def to_json(self, latency_ms: float = 0.0) -> dict:
        data = {
            "connected": self.connected,
            "playing": self.playing,
            "recording": self.recording,
            "bpm": round(self.bpm, 3),
            "songMode": self.song_mode,
        }
        data.update(self.position(latency_ms=0.0))
        return data


def parse_fl_sysex(data: bytes | list[int]) -> dict | None:
    """Décode le corps d'un SysEx envoyé par le script FL (sans F0/F7)."""
    data = list(data)
    if tuple(data[:3]) != SYSEX_HEADER:
        return None
    text = "".join(chr(b) for b in data[3:])
    values: dict[str, str] = {}
    for part in text.split(";"):
        if "=" in part:
            key, value = part.split("=", 1)
            values[key] = value
    try:
        return {
            "playing": values.get("p") == "1",
            "recording": values.get("r") == "1",
            "bpm": float(values.get("t", "120")),
            "ticks": int(values.get("k", "0")),
            "ppq": int(values.get("q", "96")) or 96,
            "ppb": int(values.get("b", "384")) or 384,
            "song_mode": values.get("m", "1") == "1",
        }
    except ValueError:
        return None


def apply_fl_update(state: FlState, update: dict, now: float | None = None) -> None:
    for key, value in update.items():
        setattr(state, key, value)
    state.connected = True
    state.updated_at = time.monotonic() if now is None else now


def encode_command(action: str, bar: int | None = None) -> list[int]:
    """Commande téléphone -> FL, au format SysEx (sans F0/F7)."""
    text = f"cmd={action}"
    if bar is not None:
        text += f"&bar={int(bar)}"
    return list(SYSEX_HEADER) + [ord(c) & 0x7F for c in text]
