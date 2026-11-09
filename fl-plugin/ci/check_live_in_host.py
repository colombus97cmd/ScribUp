"""Mini-hôte VST3 (Windows) pour ScribUp Live : charge le plugin comme FL Studio, l'active,
et vérifie qu'il laisse passer le son et qu'il se connecte tout seul au pont ScribUp comme écran live.

Usage : python ci/check_live_in_host.py "target/bundled/ScribUp Live.vst3"
"""

import asyncio
import ctypes
import sys
import threading
import time
from ctypes import POINTER, byref, c_char_p, c_int32, c_uint8, c_uint64, c_void_p
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "pc-bridge"))
from check_in_host import (  # noqa: E402
    BLOCK, IID_IAUDIOPROCESSOR, IID_ICOMPONENT, K_RESULT_OK, SAMPLE_RATE, AudioBusBuffers, PClassInfo,
    ProcessData, ProcessSetup, channel_pointers, check, method,
)
from bridge.analysis import HarmonyAnalyzer  # noqa: E402
from bridge.claude_cli import ClaudeCli  # noqa: E402
from bridge.protocol import FlState  # noqa: E402
from bridge.server import BridgeServer  # noqa: E402
from bridge.sources import AudioFanout, DemoSource  # noqa: E402


def start_bridge() -> BridgeServer:
    state = FlState()
    fanout = AudioFanout(HarmonyAnalyzer(samplerate=48000))
    demo = DemoSource(state, fanout)
    server = BridgeServer(state, fanout.analyzer, demo, pin="4242", claude=ClaudeCli([]))
    server._advertise = lambda port: asyncio.sleep(0)
    demo.start()
    threading.Thread(target=lambda: asyncio.run(server.run("127.0.0.1", 8765)), daemon=True).start()
    time.sleep(1.0)
    return server


def main(bundle: str) -> None:
    server = start_bridge()
    bundle_path = Path(bundle).resolve()
    dll = ctypes.WinDLL(str(bundle_path / "Contents" / "x86_64-win" / bundle_path.name))
    dll.InitDll()
    dll.GetPluginFactory.restype = c_void_p
    factory = dll.GetPluginFactory()
    assert factory, "fabrique VST3 nulle"

    info = PClassInfo()
    check(method(factory, 5, c_int32, POINTER(PClassInfo))(0, byref(info)), "getClassInfo")
    print("Plugin :", info.name.decode(), "/", info.category.decode())
    component = c_void_p()
    cid = ctypes.string_at(ctypes.addressof(info), 16)
    check(method(factory, 6, c_char_p, c_char_p, POINTER(c_void_p))(cid, IID_ICOMPONENT, byref(component)),
          "createInstance(IComponent)")
    comp = component.value
    check(method(comp, 3, c_void_p)(None), "initialize")
    processor = c_void_p()
    check(method(comp, 0, c_char_p, POINTER(c_void_p))(IID_IAUDIOPROCESSOR, byref(processor)), "IAudioProcessor")
    proc = processor.value
    stereo = (c_uint64 * 1)(3)
    check(method(proc, 3, POINTER(c_uint64), c_int32, POINTER(c_uint64), c_int32)(stereo, 1, stereo, 1),
          "setBusArrangements(stéréo)")
    check(method(comp, 10, c_int32, c_int32, c_int32, c_uint8)(0, 0, 0, 1), "activateBus(entrée)")
    check(method(comp, 10, c_int32, c_int32, c_int32, c_uint8)(0, 1, 0, 1), "activateBus(sortie)")
    setup = ProcessSetup(0, 0, BLOCK, float(SAMPLE_RATE))
    check(method(proc, 7, POINTER(ProcessSetup))(byref(setup)), "setupProcessing")
    check(method(comp, 11, c_uint8)(1), "setActive(1)")
    check(method(proc, 8, c_uint8)(1), "setProcessing(1)")

    # Le son doit ressortir intact
    signal = np.sin(np.arange(BLOCK * 20) * 2 * np.pi * 440 / SAMPLE_RATE).astype(np.float32) * 0.5
    process = method(proc, 9, POINTER(ProcessData))
    for start in range(0, len(signal), BLOCK):
        inputs = [np.ascontiguousarray(signal[start:start + BLOCK]) for _ in range(2)]
        outs = [np.zeros(BLOCK, dtype=np.float32) for _ in range(2)]
        in_bus = AudioBusBuffers(2, 0, channel_pointers(inputs))
        out_bus = AudioBusBuffers(2, 0, channel_pointers(outs))
        data = ProcessData(0, 0, BLOCK, 1, 1, ctypes.pointer(in_bus), ctypes.pointer(out_bus),
                           None, None, None, None, None)
        assert process(byref(data)) == K_RESULT_OK, "échec de process"
        assert np.allclose(outs[0], inputs[0], atol=1e-6), "le plugin modifie le son"
    print("  le son ressort intact du plugin : ok")

    deadline = time.monotonic() + 10
    while not server.displays and time.monotonic() < deadline:
        time.sleep(0.1)
    assert server.displays, "le plugin ne s'est pas connecté au pont"
    print("  le plugin est connecté au pont comme écran live : ok")

    check(method(proc, 8, c_uint8)(0), "setProcessing(0)")
    check(method(comp, 11, c_uint8)(0), "setActive(0)")
    check(method(comp, 4)(), "terminate")
    print("OK : ScribUp Live se charge dans un hôte VST3 et suit le pont")


if __name__ == "__main__":
    main(sys.argv[1])
