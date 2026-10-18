"""Resolve the Diorama core package under test.

The suite runs against the China ModSDK core at
``DioramaMod/diorama_behavior_pack/dioramaScripts/modCore``. When that directory
is not already importable, this module puts it on ``sys.path`` (so
``python3 -m unittest discover -s tests`` works without any setup).

Set ``DIORAMA_CORE_MODULE`` to the name of another importable package that
provides the same submodules to test that implementation instead; set
``DIORAMA_CORE_PATH`` to add its directory to ``sys.path`` as well.
"""

from __future__ import absolute_import, division, print_function

import importlib
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def _default_scripts_dir():
    return os.path.normpath(
        os.path.join(
            HERE,
            os.pardir,
            "DioramaMod",
            "diorama_behavior_pack",
            "dioramaScripts",
        )
    )


DEFAULT_SCRIPTS = _default_scripts_dir()

#: The import name of the core under test.
CORE_MODULE = os.environ.get("DIORAMA_CORE_MODULE", "modCore")


def _add_to_path(path):
    if path and os.path.isdir(path) and path not in sys.path:
        sys.path.insert(0, path)


# Insert the default first, then any override, so an override wins.
_add_to_path(DEFAULT_SCRIPTS)
_add_to_path(os.environ.get("DIORAMA_CORE_PATH"))


def load(submodule=None):
    """Imports the core package, or one of its submodules."""
    name = CORE_MODULE if submodule is None else CORE_MODULE + "." + submodule
    return importlib.import_module(name)
