#!/usr/bin/env python3
"""Run the China ModSDK Diorama core test-suite.

Standard library only; no pytest, no third-party imports, no game, no ModSDK.
The suite exercises ``modCore`` (the dependency-free Diorama maths package under
``DioramaMod/diorama_behavior_pack/dioramaScripts``) on plain CPython.

Usage::

    python3 run_tests.py                     # run everything
    python3 run_tests.py -v                  # verbose
    python3 run_tests.py test_baker          # one module
    python3 run_tests.py --core my_core      # another implementation
    python3 run_tests.py --list              # list without running
    python3 run_tests.py -f                  # stop at the first failure
"""

from __future__ import absolute_import, division, print_function

import argparse
import importlib
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TESTS_DIR = os.path.join(HERE, "tests")
DEFAULT_SCRIPTS = os.path.normpath(
    os.path.join(HERE, "DioramaMod", "diorama_behavior_pack", "dioramaScripts")
)

DEFAULT_CORE = os.environ.get("DIORAMA_CORE_MODULE", "modCore")


def build_parser():
    parser = argparse.ArgumentParser(
        prog="run_tests.py",
        description="Run the pure-Python Diorama core tests.",
    )
    parser.add_argument(
        "--core",
        default=DEFAULT_CORE,
        metavar="MODULE",
        help="importable core package to test (default: %(default)s)",
    )
    parser.add_argument(
        "--core-path",
        default=os.environ.get("DIORAMA_CORE_PATH", ""),
        metavar="DIR",
        help="directory to add to sys.path before importing the core",
    )
    parser.add_argument(
        "--pattern",
        default="test_*.py",
        metavar="GLOB",
        help="test file pattern for discovery (default: %(default)s)",
    )
    parser.add_argument("-v", "--verbose", action="store_true", help="show each test")
    parser.add_argument("-q", "--quiet", action="store_true", help="summary only")
    parser.add_argument("-f", "--failfast", action="store_true", help="stop at the first failure")
    parser.add_argument("--list", action="store_true", help="list the tests without running them")
    parser.add_argument(
        "tests",
        nargs="*",
        metavar="TEST",
        help="optional test names or dotted paths; defaults to discovery",
    )
    return parser


def _put_on_path(path):
    if path and os.path.isdir(path) and path not in sys.path:
        sys.path.insert(0, path)


def _iter_tests(suite):
    for item in suite:
        if isinstance(item, unittest.TestSuite):
            for test in _iter_tests(item):
                yield test
        else:
            yield item


def _check_core(name):
    try:
        importlib.import_module(name)
    except ImportError as error:
        sys.stderr.write("error: cannot import core %r: %s\n" % (name, error))
        sys.stderr.write(
            "hint: pass --core MODULE, or set PYTHONPATH so the package is importable\n"
        )
        return False
    return True


def main(argv=None):
    args = build_parser().parse_args(argv)

    # The tests resolve the core through this variable at import time.
    os.environ["DIORAMA_CORE_MODULE"] = args.core
    os.environ["DIORAMA_CORE_PATH"] = args.core_path

    _put_on_path(DEFAULT_SCRIPTS)  # modCore
    _put_on_path(args.core_path)  # an alternative core package wins
    _put_on_path(HERE)  # this script's directory
    _put_on_path(TESTS_DIR)  # the _core helper and the test modules

    if not _check_core(args.core):
        return 2

    loader = unittest.TestLoader()
    if args.tests:
        suite = loader.loadTestsFromNames(args.tests)
    else:
        suite = loader.discover(TESTS_DIR, pattern=args.pattern, top_level_dir=TESTS_DIR)

    if args.list:
        count = 0
        for test in _iter_tests(suite):
            print(test.id())
            count += 1
        print("%d test%s" % (count, "" if count == 1 else "s"))
        return 0

    verbosity = 2 if args.verbose else 0 if args.quiet else 1
    runner = unittest.TextTestRunner(verbosity=verbosity, failfast=args.failfast)
    result = runner.run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
