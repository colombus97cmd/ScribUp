"""Claude via le CLI du PC, et l'écran live (miroir du téléphone)."""

import asyncio
import json
import socket
import sys
import urllib.request
from pathlib import Path

from websockets.asyncio.client import connect

from bridge.analysis import HarmonyAnalyzer
from bridge.claude_cli import ClaudeCli, ClaudeError, build_prompt, model_alias
from bridge.protocol import FlState
from bridge.server import BridgeServer
from bridge.sources import AudioFanout, DemoSource

FAKE = [sys.executable, str(Path(__file__).with_name("fake_claude.py"))]


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def test_build_prompt_keeps_history():
    assert build_prompt([{"role": "user", "content": "salut"}]) == "salut"
    prompt = build_prompt([
        {"role": "user", "content": "écris un refrain"},
        {"role": "assistant", "content": "voilà le refrain"},
        {"role": "user", "content": "plus court"},
    ])
    assert "écris un refrain" in prompt and "voilà le refrain" in prompt
    assert prompt.rstrip().endswith("plus court")


def test_model_alias():
    assert model_alias("claude-opus-5-5") == "opus"
    assert model_alias("claude-haiku-5-5") == "haiku"
    assert model_alias(None) is None


def test_cli_streams_and_reports_errors(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-ne-pas-utiliser")
    cli = ClaudeCli(FAKE)
    pieces = []
    answer = asyncio.run(cli.reply("SYS", [{"role": "user", "content": "bonjour"}], "claude-sonnet-5-5",
                                   lambda p: pieces.append(p)))
    assert answer == "[sonnet|SYS] bonjour"
    assert "".join(pieces) == answer and len(pieces) > 1
    try:
        asyncio.run(cli.reply("SYS", [{"role": "user", "content": "ERREUR"}], None, lambda p: None))
    except ClaudeError as exc:
        assert "/login" in str(exc)
    else:
        raise AssertionError("une erreur était attendue")


def test_missing_cli():
    cli = ClaudeCli([])
    assert not cli.available


async def scenario(port: int):
    state = FlState()
    fanout = AudioFanout(HarmonyAnalyzer(samplerate=48000))
    demo = DemoSource(state, fanout)
    server = BridgeServer(state, fanout.analyzer, demo, pin="1234", claude=ClaudeCli(FAKE))
    server._advertise = lambda port: asyncio.sleep(0)
    task = asyncio.create_task(server.run("127.0.0.1", port))
    await asyncio.sleep(0.5)
    try:
        page = await asyncio.to_thread(lambda: urllib.request.urlopen(f"http://127.0.0.1:{port}/").read())
        async with connect(f"ws://127.0.0.1:{port}") as phone, connect(f"ws://127.0.0.1:{port}") as display:
            await phone.send(json.dumps({"type": "hello", "pin": "1234"}))
            welcome = json.loads(await phone.recv())
            # l'écran live sur le PC lui-même : pas de PIN
            await display.send(json.dumps({"type": "hello", "client": "display"}))
            assert json.loads(await display.recv())["type"] == "welcome"

            await phone.send(json.dumps({"type": "mirror", "tab": "write",
                                         "song": {"title": "Test", "lyrics": "[Refrain]\nla la"}, "cursorLine": 1}))
            await phone.send(json.dumps({"type": "claude", "id": "r1", "system": "S", "model": "claude-opus-5-5",
                                         "messages": [{"role": "user", "content": "un refrain"}]}))
            deltas, done = [], None
            while done is None:
                msg = json.loads(await asyncio.wait_for(phone.recv(), 10))
                if msg["type"] == "claude_delta":
                    deltas.append(msg["text"])
                elif msg["type"] in ("claude_done", "claude_error"):
                    done = msg
            mirror, display_done = None, None
            while display_done is None:
                msg = json.loads(await asyncio.wait_for(display.recv(), 10))
                if msg["type"] == "mirror":
                    mirror = msg
                elif msg["type"] == "claude_done":
                    display_done = msg
    finally:
        task.cancel()
    return page, welcome, deltas, done, mirror, display_done


def test_server_claude_and_live_screen():
    page, welcome, deltas, done, mirror, display_done = asyncio.run(scenario(free_port()))
    assert b"ScribUp Live" in page
    assert welcome["version"] == 3 and welcome["claude"] is True
    assert done == {"type": "claude_done", "id": "r1", "text": "[opus|S] un refrain"}
    assert deltas[-1] == done["text"] and len(deltas) > 1
    assert mirror["song"]["title"] == "Test"
    assert display_done["text"] == done["text"]
