# -*- coding: utf-8 -*-
"""One component per server-side feature.

The ModSDK does not have the Spring-style component container a Chinese mod
framework might add (MODSDKSpring and friends), so a component here is a plain
object that receives the ``DioramaServerSystem`` in ``__init__`` and is called
from it. Keeping features apart means the radar scan, the waypoint relay and
the map service can each be tested and disabled on their own.
"""

from __future__ import absolute_import, division, print_function, unicode_literals
