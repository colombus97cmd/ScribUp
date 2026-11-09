"""Fait répondre Claude avec le Claude Code (CLI `claude`) installé sur le PC.

Le pont lance `claude -p` en mode streaming : c'est ton abonnement Claude (connexion
faite une fois avec `claude` puis /login) qui sert, pas une clé API payée au jeton.
Chaque demande est indépendante : l'historique de la conversation est remis dans le message.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import shutil
import tempfile
from pathlib import Path
from typing import Awaitable, Callable

log = logging.getLogger("scribup")

WORKDIR = Path.home() / ".scribup_bridge_claude"
TIMEOUT_S = 300


def find_claude() -> list[str] | None:
    """Commande pour lancer le CLI Claude, ou None s'il n'est pas installé."""
    found = shutil.which("claude")
    if found:
        return [found]
    home = Path.home()
    for candidate in (
        home / ".local" / "bin" / "claude.exe",  # installateur natif Windows
        home / ".local" / "bin" / "claude",
        home / ".claude" / "local" / "claude",
        Path(os.environ.get("APPDATA", home)) / "npm" / "claude.cmd",  # npm install -g
    ):
        if candidate.is_file():
            return [str(candidate)]
    return None


def model_alias(model: str | None) -> str | None:
    """« claude-opus-5-5 » -> « opus » : l'alias marche quelle que soit la version du CLI."""
    if not model:
        return None
    for alias in ("opus", "sonnet", "haiku"):
        if alias in model.lower():
            return alias
    return model


def build_prompt(messages: list[dict]) -> str:
    """Remet l'historique (alternance user / assistant) dans un seul message pour `claude -p`."""
    turns = [m for m in messages if str(m.get("content", "")).strip()]
    if not turns:
        return ""
    *previous, last = turns
    if not previous:
        return str(last["content"])
    parts = ["Voici notre conversation jusqu'ici :", "<conversation>"]
    for m in previous:
        who = "moi" if m.get("role") == "user" else "toi"
        parts.append(f'<message de="{who}">\n{m["content"]}\n</message>')
    parts += ["</conversation>", "", "Mon nouveau message, auquel tu réponds :", str(last["content"])]
    return "\n".join(parts)


class ClaudeError(Exception):
    pass


def _child_env() -> dict:
    env = dict(os.environ)
    # Sans ça, une clé API présente sur le PC serait utilisée (et facturée) à la place de l'abonnement.
    for key in ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"):
        env.pop(key, None)
    return env


class ClaudeCli:
    def __init__(self, command: list[str] | None = None):
        self.command = command if command is not None else find_claude()

    @property
    def available(self) -> bool:
        return bool(self.command)

    async def reply(self, system: str, messages: list[dict], model: str | None,
                    on_delta: Callable[[str], Awaitable[None] | None]) -> str:
        """Lance `claude -p`, appelle [on_delta] à chaque morceau de texte, renvoie la réponse complète.

        Annuler la tâche asyncio arrête le processus.
        """
        if not self.command:
            raise ClaudeError("Claude Code n'est pas installé sur le PC (commande « claude » introuvable).")
        prompt = build_prompt(messages)
        if not prompt:
            raise ClaudeError("Message vide.")

        WORKDIR.mkdir(exist_ok=True)  # dossier vide : aucun CLAUDE.md de projet ne s'en mêle
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".txt", delete=False,
                                         dir=WORKDIR) as f:
            f.write(system or "")
            system_file = f.name
        args = [
            *self.command, "-p",
            "--output-format", "stream-json", "--include-partial-messages", "--verbose",
            "--tools", "",  # simple conversation : pas d'outils (fichiers, commandes…)
            "--strict-mcp-config",
            "--no-session-persistence",
            "--system-prompt-file", system_file,
        ]
        alias = model_alias(model)
        if alias:
            args += ["--model", alias]

        proc = await asyncio.create_subprocess_exec(
            *args, cwd=str(WORKDIR), env=_child_env(),
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        text: list[str] = []
        final: str | None = None
        error: str | None = None
        try:
            proc.stdin.write(prompt.encode("utf-8"))
            await proc.stdin.drain()
            proc.stdin.close()

            async def read_events():
                nonlocal final, error
                async for raw in proc.stdout:
                    try:
                        event = json.loads(raw)
                    except ValueError:
                        continue
                    kind = event.get("type")
                    if kind == "stream_event":
                        inner = event.get("event") or {}
                        if inner.get("type") == "message_start" and text:
                            text.clear()  # nouvelle tentative du CLI : on repart de zéro
                            await _call(on_delta, None)
                        delta = inner.get("delta") or {}
                        if inner.get("type") == "content_block_delta" and delta.get("type") == "text_delta":
                            piece = delta.get("text", "")
                            if piece:
                                text.append(piece)
                                await _call(on_delta, piece)
                    elif kind == "result":
                        if event.get("is_error"):
                            error = str(event.get("result") or event.get("subtype") or "erreur inconnue")
                        else:
                            final = event.get("result")

            await asyncio.wait_for(read_events(), timeout=TIMEOUT_S)
            stderr = (await proc.stderr.read()).decode("utf-8", "replace").strip()
            code = await proc.wait()
        except BaseException:
            if proc.returncode is None:
                proc.kill()
            raise
        finally:
            try:
                os.unlink(system_file)
            except OSError:
                pass

        answer = "".join(text) or (final or "")
        if error:
            raise ClaudeError(_explain(error))
        if code != 0 and not answer:
            raise ClaudeError(_explain(stderr or f"claude s'est arrêté (code {code})"))
        return answer


async def _call(callback, value):
    result = callback(value)
    if asyncio.iscoroutine(result):
        await result


def _explain(message: str) -> str:
    low = message.lower()
    if "login" in low or "api key" in low or "authenticat" in low:
        return ("Claude Code n'est pas connecté à ton compte sur le PC : ouvre un terminal, tape « claude » "
                "puis /login. (" + message[:200] + ")")
    if "limit" in low:
        return "Limite d'utilisation de ton abonnement atteinte pour l'instant. (" + message[:200] + ")"
    return "Claude Code : " + message[:400]
