# -*- coding: utf-8 -*-
"""Logging with a fixed prefix, so the game's own log can be filtered.

The China Edition ModSDK has no debugger; everything is ``print`` or the
``logging`` module, and the output lands in the Mod PC development package's
console. Keeping one prefix and one place to silence it is the difference
between a readable console and a wall of text.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

#: Set to False to silence Diorama's own output in a release build.
ENABLED = True

PREFIX = "[diorama]"

try:  # the engine ships mod_log; use it when it is there
    from mod_log import logger as _logger  # type: ignore
except Exception:  # noqa: BLE001 - not present on the PC workbench
    _logger = None


def info(message, *args):
    """Log an informational line; ``%``-style args are supported."""
    if not ENABLED:
        return
    text = _format(message, args)
    if _logger is not None:
        try:
            _logger.info(text)
            return
        except Exception:  # noqa: BLE001
            pass
    print(PREFIX, text)


def warn(message, *args):
    if not ENABLED:
        return
    text = _format(message, args)
    if _logger is not None:
        try:
            _logger.warning(text)
            return
        except Exception:  # noqa: BLE001
            pass
    print(PREFIX, "WARN", text)


def error(message, *args):
    if not ENABLED:
        return
    text = _format(message, args)
    if _logger is not None:
        try:
            _logger.error(text)
            return
        except Exception:  # noqa: BLE001
            pass
    print(PREFIX, "ERROR", text)


def _format(message, args):
    text = "%s" % (message,)
    if not args:
        return text
    try:
        return text % args
    except (TypeError, ValueError):
        return "%s %s" % (text, " ".join(["%s" % (a,) for a in args]))


def safe(label, function, *args, **kwargs):
    """Call ``function`` and log instead of raising.

    Every engine callback Diorama registers is wrapped in this: one bad frame
    must not take the whole mod - or the player's session - down with it.
    """
    try:
        return function(*args, **kwargs)
    except Exception as exc:  # noqa: BLE001 - deliberate catch-all at the edge
        error("%s failed: %r", label, exc)
        return None
