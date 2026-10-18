# -*- coding: utf-8 -*-
"""Everything Diorama for the China Edition ModSDK.

Layout (see ``china-modsdk/README.md``)::

    dioramaScripts/
        modMain.py        ModSDK entry (@Mod.Binding / InitServer / InitClient)
        modCommon/        config, event names, logging
        modCore/          pure map maths - no ModSDK import, runs on a PC
        modServer/        server system, components, engine adapters
        modClient/        client system, components, UI screens, engine adapters

Only ``modMain`` is loaded by the engine; everything else is imported from
there.
"""

from __future__ import absolute_import, division, print_function, unicode_literals
