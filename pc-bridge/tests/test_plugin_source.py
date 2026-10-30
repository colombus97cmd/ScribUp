"""Son de FL reçu du plugin ScribUp Send (fl-plugin/) : format des paquets, rééchantillonnage, réception UDP."""

import socket
import struct
import time

import numpy as np

from bridge.analysis import HarmonyAnalyzer
from bridge.sources import AudioFanout, LinearResampler, PluginReceiver, parse_plugin_packet
from bridge.synth import render_chord

# Même paquet de référence que le test Rust de fl-plugin/scribup_send/src/lib.rs
REFERENCE_PACKET = bytes.fromhex("5343524244ac000002000100070000000000003f000080be")


def make_packet(rate: int, seq: int, stereo: np.ndarray) -> bytes:
    frames, channels = stereo.shape
    return struct.pack("<4sIHHI", b"SCRB", rate, channels, frames, seq) + stereo.astype("<f4").tobytes()


def test_reference_packet_from_plugin():
    rate, samples = parse_plugin_packet(REFERENCE_PACKET)
    assert rate == 44100
    assert samples.tolist() == [[0.5, -0.25]]


def test_rejects_garbage():
    assert parse_plugin_packet(b"hello") is None
    assert parse_plugin_packet(REFERENCE_PACKET[:-1]) is None
    assert parse_plugin_packet(b"XXXX" + REFERENCE_PACKET[4:]) is None


def test_resampler_is_continuous_across_blocks():
    src, dst, f = 44100, 48000, 440.0
    signal = np.sin(2 * np.pi * f * np.arange(src) / src).astype(np.float32)[:, None]
    resampler = LinearResampler(src, dst)
    sizes = [256, 113, 512, 64, 1000]
    out, i, k = [], 0, 0
    while i < len(signal):
        n = sizes[k % len(sizes)]
        out.append(resampler.process(signal[i:i + n]))
        i, k = i + n, k + 1
    result = np.concatenate(out)[:, 0]
    assert abs(len(result) - dst) <= 2
    expected = np.sin(2 * np.pi * f * np.arange(len(result)) / dst)
    assert np.max(np.abs(result - expected)) < 0.01


def test_receives_fl_sound_over_udp():
    fanout = AudioFanout(HarmonyAnalyzer(samplerate=48000))
    received = []
    fanout.listeners.append(received.append)
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    receiver = PluginReceiver(fanout, port=port)
    receiver.start()
    time.sleep(0.2)
    try:
        audio = render_chord("Am", 1.5, 44100)
        stereo = np.stack([audio, audio], axis=1)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sender:
            for seq, start in enumerate(range(0, len(stereo), 256)):
                sender.sendto(make_packet(44100, seq, stereo[start:start + 256]), ("127.0.0.1", port))
                time.sleep(0.0005)
        deadline = time.monotonic() + 3
        while sum(len(b) for b in received) < 60000 and time.monotonic() < deadline:
            time.sleep(0.05)
    finally:
        receiver.stop()
        receiver.join(timeout=2)
    total = sum(len(b) for b in received)
    assert total > 60000  # ~1,5 s rééchantillonnée à 48 kHz (72 000 frames), avec une marge pour l'UDP
    assert fanout.active_source() == "plugin"
    assert fanout.analyzer.snapshot()["chord"] == "Am"
