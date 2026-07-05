import os
import sys
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parents[1]
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

# These files are interactive Playwright diagnostics. Importing them launches a
# browser and requires a separately running frontend, so they are not unit tests.
collect_ignore = [
    "test_login_debug.py",
    "test_login_debug2.py",
    "test_usage_status_filter.py",
]

if os.getenv("RUN_LIVE_AGENT_TESTS") != "1":
    collect_ignore.append("test_agent.py")
