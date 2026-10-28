# Permet `python -m pytest pc-bridge/tests` depuis la racine du dépôt.
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
