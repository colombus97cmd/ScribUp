"""Analyse harmonique temps réel : chromagramme, accord courant, basse, tonalité.

Volontairement sans librosa : numpy suffit et le compagnon reste léger.
"""

from __future__ import annotations

import threading

import numpy as np

NOTE_NAMES = ["C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"]

# (suffixe, intervalles, pénalité) : les accords à 4 sons doivent clairement gagner
CHORD_QUALITIES = [
    ("", (0, 4, 7), 1.0),
    ("m", (0, 3, 7), 1.0),
    ("dim", (0, 3, 6), 0.93),
    ("sus2", (0, 2, 7), 0.92),
    ("sus4", (0, 5, 7), 0.92),
    ("7", (0, 4, 7, 10), 0.95),
    ("maj7", (0, 4, 7, 11), 0.95),
    ("m7", (0, 3, 7, 10), 0.95),
]

# Profils de Krumhansl-Kessler
MAJOR_PROFILE = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
MINOR_PROFILE = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17])

SILENCE_DB = -55.0


HARMONICS = [(1, 1.0), (2, 0.7), (3, 0.5), (4, 0.35), (5, 0.25), (6, 0.18)]


def _note_profile(pc: int) -> np.ndarray:
    """Chroma attendu d'une note réelle : fondamentale + harmoniques (quinte, tierce...)."""
    profile = np.zeros(12)
    for h, amp in HARMONICS:
        profile[(pc + round(12 * np.log2(h))) % 12] += amp
    return profile


def _build_templates():
    names, roots, mats, pure = [], [], [], []
    for root in range(12):
        for suffix, intervals, penalty in CHORD_QUALITIES:
            t = np.zeros(12)
            p = np.zeros(12)
            for i, iv in enumerate(intervals):
                t += _note_profile((root + iv) % 12) * (1.3 if i == 0 else 1.0)
                p[(root + iv) % 12] = 1.0
            t /= np.linalg.norm(t)
            names.append(NOTE_NAMES[root] + suffix)
            roots.append(root)
            mats.append(t * penalty)
            pure.append(p)
    return names, np.array(roots), np.array(mats), np.array(pure)


# CHORD_NOTES : notes de l'accord sans harmoniques (sert à la tonalité)
CHORD_NAMES, CHORD_ROOTS, CHORD_TEMPLATES, CHORD_NOTES = _build_templates()


def _best_chord(chroma: np.ndarray) -> tuple[int | None, float]:
    norm = np.linalg.norm(chroma)
    if norm < 1e-9:
        return None, 0.0
    scores = CHORD_TEMPLATES @ (chroma / norm)
    best = int(np.argmax(scores))
    return best, float(scores[best])


def match_chord(chroma: np.ndarray) -> tuple[str | None, float]:
    """Accord le plus proche d'un vecteur chroma (12 valeurs) et sa confiance (0-1)."""
    best, conf = _best_chord(chroma)
    return (CHORD_NAMES[best] if best is not None else None), conf


def detect_key(chroma: np.ndarray) -> tuple[int | None, str, float]:
    """Tonalité (tonique, 'major'|'minor', corrélation) par Krumhansl-Schmuckler."""
    if np.linalg.norm(chroma) < 1e-9:
        return None, "major", 0.0
    best = (None, "major", -2.0)
    for tonic in range(12):
        rotated = np.roll(chroma, -tonic)
        for mode, profile in (("major", MAJOR_PROFILE), ("minor", MINOR_PROFILE)):
            r = float(np.corrcoef(rotated, profile)[0, 1])
            if r > best[2]:
                best = (tonic, mode, r)
    return best


def key_name(tonic: int | None, mode: str) -> str | None:
    if tonic is None:
        return None
    return f"{NOTE_NAMES[tonic]} {'minor' if mode == 'minor' else 'major'}"


class HarmonyAnalyzer:
    """Reçoit des blocs audio mono et maintient l'état harmonique (thread-safe)."""

    def __init__(self, samplerate: int = 48000, fft_size: int = 8192,
                 chord_time_constant: float = 0.35, key_time_constant: float = 25.0):
        self.samplerate = samplerate
        self.fft_size = fft_size
        self._window = np.hanning(fft_size)
        self._buffer = np.zeros(fft_size, dtype=np.float32)
        self._lock = threading.Lock()
        self._chord_tc = chord_time_constant
        self._key_tc = key_time_constant

        freqs = np.fft.rfftfreq(fft_size, 1.0 / samplerate)
        with np.errstate(divide="ignore"):
            midi = 69 + 12 * np.log2(np.where(freqs > 0, freqs, 1) / 440.0)
        pcs = np.mod(np.round(midi), 12).astype(int)
        self._chroma_map = self._bin_map(freqs, pcs, 80.0, 4200.0)
        self._bass_map = self._bin_map(freqs, pcs, 35.0, 260.0)

        self.chroma = np.zeros(12)       # lissé court terme -> accord
        self.bass = np.zeros(12)
        self.long_chroma = np.zeros(12)  # notes des accords, lissé long terme -> tonalité
        self.level_db = -120.0
        self._accum = np.zeros(12)       # somme pondérée depuis le dernier take_accumulated()

    @staticmethod
    def _bin_map(freqs, pcs, fmin, fmax) -> np.ndarray:
        m = np.zeros((12, len(freqs)))
        sel = (freqs >= fmin) & (freqs <= fmax)
        m[pcs[sel], np.nonzero(sel)[0]] = 1.0
        return m

    def process(self, block: np.ndarray) -> None:
        """Ajoute un bloc d'échantillons mono (float, -1..1)."""
        block = np.asarray(block, dtype=np.float32).ravel()
        if block.size == 0:
            return
        n = min(block.size, self.fft_size)
        self._buffer = np.roll(self._buffer, -n)
        self._buffer[-n:] = block[-n:]

        rms = float(np.sqrt(np.mean(np.square(block)) + 1e-12))
        level_db = 20 * np.log10(rms + 1e-12)

        spectrum = np.abs(np.fft.rfft(self._buffer * self._window))
        spectrum = spectrum ** 0.75  # légère compression
        chroma = self._chroma_map @ spectrum
        bass = self._bass_map @ spectrum
        # Retire le plancher (bruit large bande) puis normalise
        chroma = chroma - chroma.min()
        bass = bass - bass.min()
        if chroma.max() > 0:
            chroma = chroma / chroma.max()
        if bass.max() > 0:
            bass = bass / bass.max()

        dt = block.size / self.samplerate
        a_chord = 1.0 - np.exp(-dt / self._chord_tc)
        a_key = 1.0 - np.exp(-dt / self._key_tc)
        with self._lock:
            self.level_db = level_db
            if level_db > SILENCE_DB:
                self.chroma += a_chord * (chroma - self.chroma)
                self.bass += a_chord * (bass - self.bass)
                self._accum += chroma * dt
                # La tonalité se déduit des notes des accords reconnus plutôt que du
                # spectre brut, dont les harmoniques tirent vers la dominante.
                best, conf = _best_chord(self.chroma)
                if best is not None and conf > 0.6:
                    self.long_chroma += a_key * (CHORD_NOTES[best] * conf - self.long_chroma)
            else:
                self.chroma *= 1.0 - a_chord
                self.bass *= 1.0 - a_chord

    def take_accumulated(self) -> np.ndarray:
        """Chroma accumulé depuis le dernier appel (pour la grille d'accords par mesure)."""
        with self._lock:
            out, self._accum = self._accum, np.zeros(12)
        return out

    def reset_key(self) -> None:
        with self._lock:
            self.long_chroma = np.zeros(12)

    def snapshot(self) -> dict:
        with self._lock:
            chroma = self.chroma.copy()
            bass = self.bass.copy()
            long_chroma = self.long_chroma.copy()
            level = self.level_db
        active = level > SILENCE_DB and chroma.max() > 0.05
        chord, conf = match_chord(chroma) if active else (None, 0.0)
        bass_note = NOTE_NAMES[int(np.argmax(bass))] if active and bass.max() > 0.3 else None
        if chord and bass_note and conf > 0.6:
            root = NOTE_NAMES[CHORD_ROOTS[CHORD_NAMES.index(chord)]]
            if bass_note != root:
                chord = f"{chord}/{bass_note}"
        tonic, mode, kconf = detect_key(long_chroma)
        return {
            "active": bool(active),
            "level": round(level, 1),
            "chord": chord,
            "chordConf": round(conf, 2),
            "bass": bass_note,
            "key": key_name(tonic, mode),
            "keyConf": round(max(0.0, kconf), 2),
            "chroma": [round(float(x), 3) for x in (chroma / chroma.max() if chroma.max() > 0 else chroma)],
        }


class BarGrid:
    """Grille d'accords mesure par mesure, consolidée à chaque passage (boucles)."""

    MIN_ENERGY = 0.15  # secondes "pleines" de chroma avant de nommer un accord

    def __init__(self):
        self._sums: dict[int, np.ndarray] = {}
        self._chords: dict[int, str] = {}

    def add(self, bar: int, chroma_sum: np.ndarray) -> bool:
        """Ajoute du chroma à une mesure. Renvoie True si l'accord de la mesure a changé."""
        if chroma_sum.sum() <= 0:
            return False
        total = self._sums.get(bar, np.zeros(12)) + chroma_sum
        self._sums[bar] = total
        if total.sum() / 12 < self.MIN_ENERGY * 0.5:
            return False
        chord, conf = match_chord(total)
        if chord is None or conf < 0.55:
            return False
        if self._chords.get(bar) != chord:
            self._chords[bar] = chord
            return True
        return False

    def chords(self) -> dict[int, str]:
        return dict(sorted(self._chords.items()))

    def reset(self) -> None:
        self._sums.clear()
        self._chords.clear()
