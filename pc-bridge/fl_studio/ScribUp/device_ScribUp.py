# name=ScribUp Bridge
# url=https://github.com/colombus97cmd/ScribUp
#
# Script MIDI FL Studio pour ScribUp.
#
# - Envoie l'état du transport (lecture, enregistrement, tempo, position,
#   signature rythmique) au compagnon PC via SysEx sur le port MIDI de sortie.
# - Reçoit les commandes du téléphone (lecture, stop, enregistrement, aller à
#   une mesure) via SysEx sur le port MIDI d'entrée.
#
# Le Python embarqué de FL Studio est limité : ce script n'utilise que les
# modules fournis par FL (pas de json, pas de time).
#
# Format des messages (ASCII entre l'en-tête et F7) :
#   FL -> PC : F0 7D 53 55 "p=1;r=0;t=140.000;k=1920;q=96;b=384;m=1" F7
#   PC -> FL : F0 7D 53 55 "cmd=play" F7   (play|stop|toggle|record|goto&bar=9)

import device
import general
import midi
import mixer
import transport

HEADER = (0xF0, 0x7D, 0x53, 0x55)  # 0x7D = ID fabricant "non commercial", puis "SU"
SEND_EVERY = 2         # OnIdle est appelé ~toutes les 20 ms -> ~25 messages/s max
HEARTBEAT_EVERY = 50   # renvoie l'état même inchangé (~1 s) pour signaler que FL est vivant

# Constantes du module midi (avec valeur de repli si une version de FL ne les expose pas)
ABSTICKS = getattr(midi, "SONGLENGTH_ABSTICKS", 2)

_idle_count = 0
_last_payload = ""
_since_sent = 0


def _safe(fn, default):
    try:
        return fn()
    except Exception:
        return default


def _tempo():
    t = _safe(mixer.getCurrentTempo, 120000)
    # Selon la version de l'API, le tempo est renvoyé en BPM * 1000.
    return t / 1000.0 if t > 1000 else float(t)


def _state_payload():
    ppq = _safe(general.getRecPPQ, 96) or 96
    ppb = _safe(general.getRecPPB, ppq * 4) or ppq * 4
    return "p=%d;r=%d;t=%.3f;k=%d;q=%d;b=%d;m=%d" % (
        1 if _safe(transport.isPlaying, False) else 0,
        1 if _safe(transport.isRecording, False) else 0,
        _tempo(),
        int(_safe(lambda: transport.getSongPos(ABSTICKS), 0)),
        int(ppq),
        int(ppb),
        int(_safe(transport.getLoopMode, 1)),
    )


def _send(payload):
    if not device.isAssigned():
        return
    data = bytearray(HEADER)
    data.extend(ord(c) & 0x7F for c in payload)
    data.append(0xF7)
    device.midiOutSysex(bytes(data))


def OnInit():
    global _last_payload
    _last_payload = ""
    print("ScribUp Bridge : prêt (port de sortie assigné : %s)" % device.isAssigned())


def OnDeInit():
    pass


def OnIdle():
    global _idle_count, _last_payload, _since_sent
    _idle_count += 1
    if _idle_count < SEND_EVERY:
        return
    _idle_count = 0
    payload = _state_payload()
    _since_sent += 1
    if payload != _last_payload or _since_sent >= HEARTBEAT_EVERY:
        _send(payload)
        _last_payload = payload
        _since_sent = 0


def _parse(text):
    params = {}
    for part in text.split("&"):
        if "=" in part:
            key, value = part.split("=", 1)
            params[key] = value
    return params


def _goto_bar(bar):
    ppb = _safe(general.getRecPPB, 384) or 384
    ticks = max(0, (bar - 1) * ppb)
    try:
        transport.setSongPos(ticks, ABSTICKS)
    except Exception:
        # Anciennes versions de l'API : position relative (0.0 - 1.0)
        length = _safe(lambda: transport.getSongLength(ABSTICKS), 0)
        if length > 0:
            transport.setSongPos(min(1.0, ticks / float(length)))


def OnSysEx(event):
    raw = event.sysex
    if raw is None or len(raw) < 6 or tuple(raw[:4]) != HEADER:
        return
    event.handled = True
    params = _parse("".join(chr(b) for b in raw[4:-1]))
    cmd = params.get("cmd", "")
    if cmd == "play":
        if not transport.isPlaying():
            transport.start()
    elif cmd == "stop":
        transport.stop()
    elif cmd == "toggle":
        transport.start()  # start() bascule lecture / pause dans FL
    elif cmd == "record":
        transport.record()
    elif cmd == "goto":
        try:
            _goto_bar(int(params.get("bar", "1")))
        except ValueError:
            pass
    # Renvoie l'état tout de suite pour que le téléphone réagisse sans attendre.
    _send(_state_payload())
