# -*- coding: utf-8 -*-
"""Server-side engine adapters.

Everything that touches ``mod.server.extraServerApi`` (or its client twin) lives
in this package, so :mod:`modCore` stays importable on a plain PC. Each adapter
is written defensively - a missing component, a deleted engine method or an
unloaded chunk must degrade to "no data", never to an exception inside a tick.
"""

from __future__ import absolute_import, division, print_function, unicode_literals
