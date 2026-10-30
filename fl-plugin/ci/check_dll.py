"""Diagnostic : charge la DLL du VST3 et appelle son point d'entrée (GetPluginFactory)."""

import ctypes
import sys
from pathlib import Path

bundle = Path(sys.argv[1]).resolve()
dll_path = bundle / "Contents" / "x86_64-win" / bundle.name
print("DLL :", dll_path, dll_path.exists(), dll_path.stat().st_size if dll_path.exists() else "")
dll = ctypes.WinDLL(str(dll_path))
for name in ("InitDll", "ExitDll", "GetPluginFactory"):
    print(name, "exporté :", hasattr(dll, name))
if hasattr(dll, "InitDll"):
    print("InitDll ->", dll.InitDll())
dll.GetPluginFactory.restype = ctypes.c_void_p
factory = dll.GetPluginFactory()
print("GetPluginFactory ->", hex(factory or 0))
if not factory:
    sys.exit("la fabrique VST3 est nulle")

# IPluginFactory : vtable [queryInterface, addRef, release, getFactoryInfo, countClasses, getClassInfo, createInstance]
vtable = ctypes.cast(ctypes.c_void_p.from_address(factory).value, ctypes.POINTER(ctypes.c_void_p))
count_classes = ctypes.WINFUNCTYPE(ctypes.c_int32, ctypes.c_void_p)(vtable[4])
print("countClasses ->", count_classes(factory))


class PClassInfo(ctypes.Structure):
    _fields_ = [("cid", ctypes.c_char * 16), ("cardinality", ctypes.c_int32),
                ("category", ctypes.c_char * 32), ("name", ctypes.c_char * 64)]


get_class_info = ctypes.WINFUNCTYPE(ctypes.c_int32, ctypes.c_void_p, ctypes.c_int32,
                                    ctypes.POINTER(PClassInfo))(vtable[5])
for i in range(count_classes(factory)):
    info = PClassInfo()
    result = get_class_info(factory, i, ctypes.byref(info))
    print(f"classe {i} : résultat {result}, catégorie {info.category!r}, nom {info.name!r}, cid {info.cid.hex()}")
