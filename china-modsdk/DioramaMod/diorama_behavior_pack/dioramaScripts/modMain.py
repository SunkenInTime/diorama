# -*- coding: utf-8 -*-
"""Diorama - NetEase (China Edition) Minecraft ModSDK entry point.

The engine loads this file and nothing else: ``@Mod.Binding`` names the mod and
the four ``@Mod.*`` decorators say what to run when each side starts and stops.
Systems are registered here by *string class path*, which is how the ModSDK
resolves them (``RegisterSystem(namespace, name, "pkg.module.Class")``).

Everything the systems do lives in :mod:`modServer` / :mod:`modClient`, so this
file stays readable and the module layout matches the documented pattern::

    <behaviour pack>/dioramaScripts/modMain.py

Note for the PC workbench: importing this module outside the game fails, because
``mod.common.mod`` only exists in the engine. ``tools/validate.py`` therefore
compiles it without importing it.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

from mod.common.mod import Mod
import mod.client.extraClientApi as clientApi
import mod.server.extraServerApi as serverApi

from dioramaScripts.modCommon import modConfig
from dioramaScripts.modCommon.log import error, info


@Mod.Binding(name=modConfig.MOD_NAMESPACE, version=modConfig.MOD_VERSION)
class DioramaMod(object):
    """Binds Diorama to the engine and registers the two systems."""

    def __init__(self):
        info("Diorama %s loading (namespace=%s)", modConfig.MOD_VERSION, modConfig.MOD_NAMESPACE)

    # -- server --------------------------------------------------------

    @Mod.InitServer()
    def DioramaServerInit(self):
        info("registering server system %s", modConfig.SERVER_SYSTEM_NAME)
        try:
            serverApi.RegisterSystem(
                modConfig.MOD_NAMESPACE,
                modConfig.SERVER_SYSTEM_NAME,
                modConfig.SERVER_SYSTEM_CLS_PATH,
            )
        except Exception as exc:  # noqa: BLE001 - a failed system must be visible
            error("RegisterSystem(server) failed: %r", exc)

    @Mod.DestroyServer()
    def DioramaServerDestroy(self):
        info("server system shutting down")

    # -- client --------------------------------------------------------

    @Mod.InitClient()
    def DioramaClientInit(self):
        info("registering client system %s", modConfig.CLIENT_SYSTEM_NAME)
        try:
            clientApi.RegisterSystem(
                modConfig.MOD_NAMESPACE,
                modConfig.CLIENT_SYSTEM_NAME,
                modConfig.CLIENT_SYSTEM_CLS_PATH,
            )
        except Exception as exc:  # noqa: BLE001
            error("RegisterSystem(client) failed: %r", exc)

    @Mod.DestroyClient()
    def DioramaClientDestroy(self):
        info("client system shutting down")
