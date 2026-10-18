# -*- coding: utf-8 -*-
"""Thin helpers over the ModSDK UI API.

Two things make raw ``ScreenNode`` code painful and are dealt with here:

1. **Base-class path shift.** A screen that inherits ``common.base_screen``
   (needed for the safe-area handling on notched phones) does not expose its
   controls at ``/panel/label`` but under a fixed wrapper path. :func:`resolve`
   prepends it, so the rest of the code can use short paths.
2. **Every control may be missing.** The JSON and the Python are edited
   separately; a renamed control must make one widget stop updating, not throw
   inside ``Update``. Every accessor here returns ``None`` instead of raising.

The exact accessor for the screen base class
(``GetScreenNodeCls`` vs ``GetUIScreenCls``) and for the experimental minimap
base class (``GetMiniMapBaseScreenCls``) is not verified - see
``docs/API_ASSUMPTIONS.md``; :func:`screen_node_cls` tries the documented names
in order and falls back to ``object``.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import mod.client.extraClientApi as clientApi

from dioramaScripts.modCommon import modConfig
from dioramaScripts.modCommon.log import warn


def _first_callable(module, names):
    for name in names:
        candidate = getattr(module, name, None)
        if candidate is None:
            continue
        try:
            return candidate()
        except Exception as exc:  # noqa: BLE001
            warn("%s() failed: %r", name, exc)
    return None


_SCREEN_NODE = []


def screen_node_cls():
    """``ScreenNode``, under whichever accessor this build exposes."""
    if not _SCREEN_NODE:
        cls = _first_callable(clientApi, ("GetScreenNodeCls", "GetUIScreenCls"))
        _SCREEN_NODE.append(cls if cls is not None else object)
    return _SCREEN_NODE[0]


def minimap_screen_cls():
    """``MiniMapBaseScreen`` when present, else ``ScreenNode``.

    ``MiniMapBaseScreen`` is documented as an experimental China Edition class
    that renders an actual minimap and accepts static/entity markers. When it is
    missing the HUD screen still works - it just has no engine-drawn map, which
    is what the "no mini map control" note in the README is about.
    """
    cls = _first_callable(
        clientApi,
        ("GetMiniMapBaseScreenCls", "GetMiniMapScreenCls", "GetMiniMapCls"),
    )
    return cls if cls is not None else screen_node_cls()


def resolve(screen, path, uses_base_screen=True):
    """Full control path for a screen that may inherit ``common.base_screen``."""
    if not path.startswith("/"):
        path = "/" + path
    if uses_base_screen and getattr(screen, "USES_BASE_SCREEN", True):
        return modConfig.BASE_SCREEN_PATH + path
    return path


def control(screen, path, uses_base_screen=True):
    """``BaseUIControl`` at ``path``, or ``None``."""
    if screen is None:
        return None
    try:
        return screen.GetBaseUIControl(resolve(screen, path, uses_base_screen))
    except Exception as exc:  # noqa: BLE001
        warn("GetBaseUIControl(%s) failed: %r", path, exc)
        return None


def as_label(screen, path, uses_base_screen=True):
    node = control(screen, path, uses_base_screen)
    return _as(node, "asLabel")


def as_image(screen, path, uses_base_screen=True):
    node = control(screen, path, uses_base_screen)
    return _as(node, "asImage")


def as_button(screen, path, uses_base_screen=True):
    node = control(screen, path, uses_base_screen)
    return _as(node, "asButton")


def _as(node, method):
    if node is None:
        return None
    fn = getattr(node, method, None)
    if fn is None:
        return None
    try:
        return fn()
    except Exception as exc:  # noqa: BLE001
        warn("%s failed: %r", method, exc)
        return None


def set_text(screen, path, text, uses_base_screen=True):
    label = as_label(screen, path, uses_base_screen)
    if label is None:
        return False
    return _invoke(label, ("SetText", "SetTextString"), "%s" % (text,))


def set_visible(screen, path, visible, uses_base_screen=True):
    node = control(screen, path, uses_base_screen)
    if node is None:
        return False
    return _invoke(node, ("SetVisible",), bool(visible))


def set_sprite(screen, path, texture, uses_base_screen=True):
    """Point an image control at a texture in the resource pack."""
    image = as_image(screen, path, uses_base_screen)
    if image is None:
        return False
    return _invoke(image, ("SetSprite", "SetTexture"), "%s" % (texture,))


def set_alpha(screen, path, alpha, uses_base_screen=True):
    node = control(screen, path, uses_base_screen)
    if node is None:
        return False
    return _invoke(node, ("SetAlpha",), float(alpha))


def set_position(screen, path, x, y, uses_base_screen=True):
    node = control(screen, path, uses_base_screen)
    if node is None:
        return False
    return _invoke(node, ("SetPosition",), (float(x), float(y)))


def on_touch_up(screen, path, callback, uses_base_screen=True):
    """Attach a touch-up handler to a button control."""
    button = as_button(screen, path, uses_base_screen)
    if button is None:
        return False
    ok = _invoke(button, ("AddTouchEventParams",), {"isSwallow": True})
    if ok is False:
        return False
    return _invoke(button, ("SetButtonTouchUpCallback",), callback)


def on_touch_down(screen, path, callback, uses_base_screen=True):
    button = as_button(screen, path, uses_base_screen)
    if button is None:
        return False
    _invoke(button, ("AddTouchEventParams",), {"isSwallow": True})
    return _invoke(button, ("SetButtonTouchDownCallback",), callback)


def clone(screen, template_path, parent_path, new_name, uses_base_screen=True):
    """``Clone`` a template control under a parent, returning its new path."""
    if screen is None:
        return None
    try:
        ok = screen.Clone(
            resolve(screen, template_path, uses_base_screen),
            resolve(screen, parent_path, uses_base_screen),
            new_name,
        )
    except Exception as exc:  # noqa: BLE001
        warn("Clone(%s) failed: %r", template_path, exc)
        return None
    if not ok:
        return None
    parent = parent_path.rstrip("/")
    return "%s/%s" % (parent, new_name)


def remove(screen, path, parent_path, uses_base_screen=True):
    if screen is None:
        return False
    try:
        screen.RemoveComponent(
            resolve(screen, path, uses_base_screen),
            resolve(screen, parent_path, uses_base_screen),
        )
        return True
    except Exception as exc:  # noqa: BLE001
        warn("RemoveComponent(%s) failed: %r", path, exc)
        return False


def _invoke(target, names, *args):
    """Call the first of ``names`` that exists on ``target``."""
    for name in names:
        fn = getattr(target, name, None)
        if fn is None:
            continue
        try:
            result = fn(*args)
            return True if result is None else result
        except Exception as exc:  # noqa: BLE001
            warn("%s failed: %r", name, exc)
            return False
    return False


def screen_size():
    """``(width, height)`` in UI pixels, or a sane default on failure."""
    try:
        size = clientApi.GetScreenSize()
        if size and len(size) >= 2:
            return (float(size[0]), float(size[1]))
    except Exception:  # noqa: BLE001
        pass
    return (1280.0, 720.0)


def hide_extra_hud(hide_map_only=True):
    """Hide the engine's own map-ish HUD bits Diorama replaces.

    Only the buttons that would overlap the minimap are touched; the rest of
    the HUD is left alone so the player keeps health, hunger and hotbar.
    """
    for name in ("HideMoveGui", "HideJumpGui", "HideSneakGui"):
        fn = getattr(clientApi, name, None)
        if fn is None:
            continue
        try:
            fn(bool(hide_map_only))
        except Exception:  # noqa: BLE001
            pass
