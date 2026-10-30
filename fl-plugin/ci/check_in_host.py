"""Mini-hôte VST3 (Windows) : fait avec le plugin ce que fait FL Studio, puis vérifie
- que le plugin laisse passer le son sans le modifier ;
- que le pont ScribUp reçoit ce son (et y reconnaît l'accord joué).

Séquence VST3 : GetPluginFactory -> createInstance(IComponent) -> initialize -> IAudioProcessor
-> setupProcessing -> activateBus -> setActive -> setProcessing -> process (blocs de 512).

Usage : python ci/check_in_host.py "target/bundled/ScribUp Send.vst3"
"""

import ctypes
import sys
import time
from ctypes import POINTER, byref, c_char, c_char_p, c_double, c_float, c_int32, c_uint8, c_uint64, c_void_p
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "pc-bridge"))
from bridge.analysis import HarmonyAnalyzer  # noqa: E402
from bridge.sources import AudioFanout, PluginReceiver  # noqa: E402
from bridge.synth import render_chord  # noqa: E402

SAMPLE_RATE = 44100
BLOCK = 512
K_RESULT_OK = 0


def iid(l1: int, l2: int, l3: int, l4: int) -> bytes:
    """INLINE_UID du SDK VST3 en mode COM_COMPATIBLE (Windows)."""
    return bytes([
        l1 & 0xFF, (l1 >> 8) & 0xFF, (l1 >> 16) & 0xFF, (l1 >> 24) & 0xFF,
        (l2 >> 16) & 0xFF, (l2 >> 24) & 0xFF, l2 & 0xFF, (l2 >> 8) & 0xFF,
        *l3.to_bytes(4, "big"), *l4.to_bytes(4, "big"),
    ])


IID_ICOMPONENT = iid(0xE831FF31, 0xF2D54301, 0x928EBBEE, 0x25697802)
IID_IAUDIOPROCESSOR = iid(0x42043F99, 0xB7DA453C, 0xA569E79D, 0x9AAEC33D)


class PClassInfo(ctypes.Structure):
    _fields_ = [("cid", c_char * 16), ("cardinality", c_int32), ("category", c_char * 32), ("name", c_char * 64)]


class ProcessSetup(ctypes.Structure):
    _fields_ = [("processMode", c_int32), ("symbolicSampleSize", c_int32),
                ("maxSamplesPerBlock", c_int32), ("sampleRate", c_double)]


class AudioBusBuffers(ctypes.Structure):
    _fields_ = [("numChannels", c_int32), ("silenceFlags", c_uint64), ("channelBuffers32", POINTER(POINTER(c_float)))]


class ProcessData(ctypes.Structure):
    _fields_ = [("processMode", c_int32), ("symbolicSampleSize", c_int32), ("numSamples", c_int32),
                ("numInputs", c_int32), ("numOutputs", c_int32),
                ("inputs", POINTER(AudioBusBuffers)), ("outputs", POINTER(AudioBusBuffers)),
                ("inputParameterChanges", c_void_p), ("outputParameterChanges", c_void_p),
                ("inputEvents", c_void_p), ("outputEvents", c_void_p), ("processContext", c_void_p)]


def method(obj: int, index: int, *argtypes, restype=c_int32):
    """Méthode n° index de la vtable d'un objet COM/VST3, liée à cet objet."""
    vtable = ctypes.cast(c_void_p.from_address(obj).value, POINTER(c_void_p))
    func = ctypes.WINFUNCTYPE(restype, c_void_p, *argtypes)(vtable[index])
    return lambda *args: func(obj, *args)


def check(result: int, what: str) -> None:
    if result != K_RESULT_OK:
        sys.exit(f"échec de {what} (code {result})")
    print(f"  {what} : ok")


def channel_pointers(channels):
    return (POINTER(c_float) * len(channels))(*[ch.ctypes.data_as(POINTER(c_float)) for ch in channels])


def main(bundle: str) -> None:
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

    stereo_arrangement = (c_uint64 * 1)(3)  # kStereo
    check(method(proc, 3, POINTER(c_uint64), c_int32, POINTER(c_uint64), c_int32)(
        stereo_arrangement, 1, stereo_arrangement, 1), "setBusArrangements(stéréo)")
    check(method(comp, 10, c_int32, c_int32, c_int32, c_uint8)(0, 0, 0, 1), "activateBus(entrée)")
    check(method(comp, 10, c_int32, c_int32, c_int32, c_uint8)(0, 1, 0, 1), "activateBus(sortie)")
    setup = ProcessSetup(0, 0, BLOCK, float(SAMPLE_RATE))  # temps réel, float32
    check(method(proc, 7, POINTER(ProcessSetup))(byref(setup)), "setupProcessing(44,1 kHz)")

    fanout = AudioFanout(HarmonyAnalyzer(samplerate=48000))
    received = []
    fanout.listeners.append(received.append)
    receiver = PluginReceiver(fanout)
    receiver.start()
    time.sleep(0.3)

    check(method(comp, 11, c_uint8)(1), "setActive(1)")
    check(method(proc, 8, c_uint8)(1), "setProcessing(1)")

    mono = render_chord("Am", 3.0, SAMPLE_RATE)
    process = method(proc, 9, POINTER(ProcessData))
    outputs = []
    for start in range(0, len(mono) - BLOCK + 1, BLOCK):
        inputs = [np.ascontiguousarray(mono[start:start + BLOCK]) for _ in range(2)]
        outs = [np.zeros(BLOCK, dtype=np.float32) for _ in range(2)]
        in_bus = AudioBusBuffers(2, 0, channel_pointers(inputs))
        out_bus = AudioBusBuffers(2, 0, channel_pointers(outs))
        data = ProcessData(0, 0, BLOCK, 1, 1, ctypes.pointer(in_bus), ctypes.pointer(out_bus), None, None, None, None, None)
        result = process(byref(data))
        if result != K_RESULT_OK:
            sys.exit(f"échec de process (code {result})")
        outputs.append(np.stack(outs))
        time.sleep(BLOCK / SAMPLE_RATE)  # rythme temps réel, comme dans FL
    time.sleep(0.5)

    check(method(proc, 8, c_uint8)(0), "setProcessing(0)")
    check(method(comp, 11, c_uint8)(0), "setActive(0)")
    receiver.stop()

    output = np.concatenate(outputs, axis=1)
    assert np.allclose(output[0], mono[:output.shape[1]], atol=1e-6), "le plugin modifie le son"
    print("  le son ressort intact du plugin : ok")

    frames = sum(len(block) for block in received)
    chord = fanout.analyzer.snapshot()["chord"]
    print(f"Pont : {frames} frames reçues ({frames / 48000:.2f} s), source {fanout.active_source()}, accord {chord}")
    assert frames > 2 * 48000, "le pont n'a pas reçu le son du plugin"
    assert fanout.active_source() == "plugin"
    assert chord == "Am", chord
    print("OK : le plugin transmet le son de FL au pont")


if __name__ == "__main__":
    main(sys.argv[1])
