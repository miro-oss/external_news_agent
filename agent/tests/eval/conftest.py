"""Reuse the existing offline provider fixtures in nested evaluation tests."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
