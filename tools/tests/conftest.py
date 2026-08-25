import sys
from pathlib import Path

# Make tools/ importable so tests can `import simlib`, and tests/ itself so they
# can share helpers like scram_client — no packaging ceremony.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))
