"""Le serveur complet en mode démo : PIN, état FL, grille, retour casque."""

import asyncio
import json
import socket

from websockets.asyncio.client import connect

from bridge.analysis import HarmonyAnalyzer
from bridge.protocol import FlState
from bridge.server import BridgeServer
from bridge.sources import AudioFanout, DemoSource


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


async def scenario(port: int):
    state = FlState()
    fanout = AudioFanout(HarmonyAnalyzer(samplerate=48000))
    demo = DemoSource(state, fanout)
    server = BridgeServer(state, fanout.analyzer, demo, pin="1234", audio_rate=48000,
                          audio_source=fanout.active_source)
    server._advertise = lambda port: asyncio.sleep(0)  # pas de mDNS pendant les tests
    fanout.listeners.append(server.push_audio)
    demo.start()
    task = asyncio.create_task(server.run("127.0.0.1", port))
    await asyncio.sleep(0.5)
    try:
        async with connect(f"ws://127.0.0.1:{port}") as ws:
            await ws.send(json.dumps({"type": "hello", "pin": "1234"}))
            assert json.loads(await ws.recv())["type"] == "welcome"
            await ws.send(json.dumps({"type": "audio", "enabled": True}))
            audio_bytes, audio_format, fl, source = 0, None, None, None
            loop = asyncio.get_running_loop()
            end = loop.time() + 1.5
            while loop.time() < end:
                msg = await ws.recv()
                if isinstance(msg, bytes):
                    assert len(msg) % 4 == 0  # stéréo 16 bits
                    audio_bytes += len(msg)
                else:
                    data = json.loads(msg)
                    if data["type"] == "audio_format":
                        audio_format = data
                    elif data["type"] == "state":
                        fl = data["fl"]
                        source = data["audio"]["source"]
        async with connect(f"ws://127.0.0.1:{port}") as ws:
            await ws.send(json.dumps({"type": "hello", "pin": "0000"}))
            refused = json.loads(await ws.recv())
    finally:
        task.cancel()
        demo.stop()
    return audio_bytes, audio_format, fl, source, refused


def test_demo_server_streams_state_and_audio():
    audio_bytes, audio_format, fl, source, refused = asyncio.run(scenario(free_port()))
    assert audio_format == {"type": "audio_format", "rate": 48000, "channels": 2, "encoding": "s16le"}
    # ~1,5 s de son stéréo 16 bits à 48 kHz = ~288 ko ; on laisse de la marge pour le démarrage
    assert audio_bytes > 150_000
    assert fl["connected"] and fl["playing"] and fl["bpm"] == 90.0
    assert source == "demo"
    assert refused == {"type": "error", "message": "PIN incorrect"}
