"""Serveur WebSocket : diffuse l'état FL + l'analyse audio au téléphone, reçoit ses commandes."""

from __future__ import annotations

import asyncio
import json
import logging
import socket
import time

import numpy as np

from websockets.asyncio.server import broadcast, serve
from websockets.exceptions import ConnectionClosed

from .analysis import BarGrid, HarmonyAnalyzer
from .protocol import FlState

log = logging.getLogger("scribup")

PROTOCOL_VERSION = 1
SERVICE_TYPE = "_scribup._tcp.local."
FL_TIMEOUT_S = 3.0
# Au-delà de ce retard en attente d'envoi (~200 ms de son), on saute des blocs plutôt que d'accumuler du décalage
MAX_AUDIO_BACKLOG = 80_000


def local_ip() -> str:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("10.255.255.255", 1))
            return s.getsockname()[0]
        except OSError:
            return "127.0.0.1"


class BridgeServer:
    def __init__(self, state: FlState, analyzer: HarmonyAnalyzer, commander, pin: str | None,
                 latency_ms: float = 80.0, rate_hz: float = 20.0, name: str | None = None,
                 audio_rate: int = 48000, audio_source=None):
        self.state = state
        self.analyzer = analyzer
        self.commander = commander  # objet avec send_command(action, bar) / command(action, bar)
        self.pin = pin
        self.latency_ms = latency_ms
        self.rate_hz = rate_hz
        self.name = name or socket.gethostname()
        self.grid = BarGrid()
        self.clients: set = set()
        self.audio_clients: set = set()  # téléphones en mode « retour casque »
        self.audio_rate = audio_rate
        self.audio_source = audio_source  # fonction : d'où vient le son en ce moment (plugin, windows, demo)
        self._loop: asyncio.AbstractEventLoop | None = None
        self._last_grid_bar: int | None = None

    # --- connexions -------------------------------------------------------

    async def handler(self, ws) -> None:
        try:
            hello = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        except (asyncio.TimeoutError, ValueError, ConnectionClosed):
            return
        if hello.get("type") != "hello" or (self.pin and str(hello.get("pin", "")) != self.pin):
            await ws.send(json.dumps({"type": "error", "message": "PIN incorrect"}))
            await ws.close()
            return
        await ws.send(json.dumps({"type": "welcome", "version": PROTOCOL_VERSION, "name": self.name}))
        await ws.send(self._grid_message())
        self.clients.add(ws)
        log.info("Téléphone connecté : %s", ws.remote_address)
        try:
            async for raw in ws:
                self._on_client_message(ws, raw)
        except ConnectionClosed:
            pass
        finally:
            self.clients.discard(ws)
            self.audio_clients.discard(ws)
            log.info("Téléphone déconnecté : %s", ws.remote_address)

    def _on_client_message(self, ws, raw) -> None:
        try:
            msg = json.loads(raw)
        except ValueError:
            return
        kind = msg.get("type")
        if kind == "cmd":
            action = msg.get("action")
            if action not in ("play", "stop", "toggle", "record", "goto"):
                return
            bar = msg.get("bar")
            bar = int(bar) if isinstance(bar, (int, float)) else None
            sender = getattr(self.commander, "send_command", None) or getattr(self.commander, "command", None)
            if sender is None or not sender(action, bar):
                log.warning("Commande '%s' ignorée : pas de sortie MIDI vers FL", action)
        elif kind == "grid_reset":
            self.grid.reset()
            broadcast(self.clients, self._grid_message())
        elif kind == "key_reset":
            self.analyzer.reset_key()
        elif kind == "audio":
            if msg.get("enabled"):
                self.audio_clients.add(ws)
                broadcast([ws], json.dumps({"type": "audio_format", "rate": self.audio_rate,
                                            "channels": 2, "encoding": "s16le"}))
                log.info("Retour casque activé pour %s", ws.remote_address)
            else:
                self.audio_clients.discard(ws)

    # --- retour casque ----------------------------------------------------

    def push_audio(self, stereo: np.ndarray) -> None:
        """Appelé depuis le fil de capture avec un bloc stéréo float32 ; envoie le son aux téléphones abonnés."""
        loop = self._loop
        if loop is None or not self.audio_clients:
            return
        pcm = (np.clip(stereo, -1.0, 1.0) * 32767).astype("<i2").tobytes()
        loop.call_soon_threadsafe(self._send_audio, pcm)

    def _send_audio(self, pcm: bytes) -> None:
        for ws in list(self.audio_clients):
            transport = getattr(ws, "transport", None)
            if transport is not None and transport.get_write_buffer_size() > MAX_AUDIO_BACKLOG:
                continue  # Wi-Fi saturé : on lâche ce bloc pour garder le retard au minimum
            broadcast([ws], pcm)

    # --- diffusion --------------------------------------------------------

    def _grid_message(self) -> str:
        return json.dumps({"type": "grid", "bars": {str(k): v for k, v in self.grid.chords().items()}})

    def _state_message(self) -> str:
        fl = self.state
        if fl.connected and time.monotonic() - fl.updated_at > FL_TIMEOUT_S:
            fl.connected = False
        audio = self.analyzer.snapshot()
        audio["source"] = self.audio_source() if self.audio_source else None
        return json.dumps({"type": "state", "fl": fl.to_json(), "audio": audio})

    def _update_grid(self) -> bool:
        accumulated = self.analyzer.take_accumulated()
        if not (self.state.connected and self.state.playing):
            return False
        bar = self.state.position(latency_ms=self.latency_ms)["bar"]
        return self.grid.add(bar, accumulated)

    async def tick_loop(self) -> None:
        period = 1.0 / self.rate_hz
        while True:
            changed = self._update_grid()
            if self.clients:
                broadcast(self.clients, self._state_message())
                if changed:
                    broadcast(self.clients, self._grid_message())
            await asyncio.sleep(period)

    # --- découverte réseau --------------------------------------------------

    async def _advertise(self, port: int):
        try:
            from zeroconf import ServiceInfo
            from zeroconf.asyncio import AsyncZeroconf
        except ImportError:
            log.info("zeroconf absent : saisis l'IP à la main dans l'app")
            return None
        info = ServiceInfo(
            SERVICE_TYPE,
            f"ScribUp {self.name}.{SERVICE_TYPE}",
            addresses=[socket.inet_aton(local_ip())],
            port=port,
            properties={"v": str(PROTOCOL_VERSION), "pin": "1" if self.pin else "0"},
        )
        zc = AsyncZeroconf()
        await zc.async_register_service(info)
        return zc, info

    async def run(self, host: str, port: int) -> None:
        self._loop = asyncio.get_running_loop()
        async with serve(self.handler, host, port, ping_interval=10, ping_timeout=10,
                         compression=None):
            advert = await self._advertise(port)
            log.info("Pont ScribUp prêt : ws://%s:%d %s", local_ip(), port,
                     f"(PIN {self.pin})" if self.pin else "(sans PIN)")
            try:
                await self.tick_loop()
            finally:
                if advert:
                    zc, info = advert
                    await zc.async_unregister_service(info)
                    await zc.async_close()
