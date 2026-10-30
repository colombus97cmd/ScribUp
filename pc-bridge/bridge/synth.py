"""Petit synthé d'accords : sert au mode démo (--demo) et aux tests."""

from __future__ import annotations

import numpy as np

from .analysis import CHORD_QUALITIES, NOTE_NAMES

_QUALITIES = {suffix: intervals for suffix, intervals, _ in CHORD_QUALITIES}
_HARMONICS = ((1, 1.0), (2, 0.5), (3, 0.25), (4, 0.12))


def chord_notes(name: str) -> list[int]:
    """'Am' -> numéros MIDI des notes (fondamentale vers l'octave 3/4)."""
    for root_len in (2, 1):
        root, suffix = name[:root_len], name[root_len:]
        if root in NOTE_NAMES and suffix in _QUALITIES:
            base = 48 + NOTE_NAMES.index(root)
            return [base + iv for iv in _QUALITIES[suffix]]
    raise ValueError(f"accord inconnu : {name}")


def render_chord(name: str, seconds: float, samplerate: int = 48000, with_bass: bool = True,
                 noise: float = 0.0, start_phase: float = 0.0) -> np.ndarray:
    t = start_phase + np.arange(int(seconds * samplerate)) / samplerate
    out = np.zeros_like(t)
    notes = chord_notes(name)
    if with_bass:
        notes = [notes[0] - 12] + notes
    for note in notes:
        f = 440.0 * 2 ** ((note - 69) / 12)
        for h, amp in _HARMONICS:
            out += amp * np.sin(2 * np.pi * f * h * t)
    # Normalisation fixe (pas par bloc) pour que des blocs consécutifs s'enchaînent sans saut de volume
    out *= 0.6 / (len(notes) * sum(amp for _, amp in _HARMONICS))
    if noise:
        out += noise * np.random.default_rng(0).standard_normal(out.size)
    return out.astype(np.float32)
