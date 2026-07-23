"""Compatibility shim — the threads store moved to web_companion.

walkthrough (and originally only this skill) shares the same per-anchor
thread files; the implementation now lives with the rest of the shared
session machinery. Import sites can migrate at leisure; this alias keeps
both old module paths working.
"""
from skills._shared.web_companion.threads import *          # noqa: F401,F403
from skills._shared.web_companion.threads import (          # noqa: F401
    append_message, delete, list_versions, load,
    set_anchor_text_if_absent, valid_anchor,
)
