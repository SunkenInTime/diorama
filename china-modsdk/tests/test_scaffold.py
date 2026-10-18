# -*- coding: utf-8 -*-
"""Scaffold tests: the ModSDK manifests, UI wiring, texts and tools.

These do not need Minecraft, the ModSDK or a network: they read the repository
tree, parse the same JSON the engine will, and run the validator the release
checklist tells you to run. They are deliberately separate from the ``modCore``
algorithm tests - if the UI JSON stops matching the Python screens, this file is
what fails first.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import json
import os
import re
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, os.pardir))
MOD_ROOT = os.path.join(ROOT, "DioramaMod")
BP = os.path.join(MOD_ROOT, "diorama_behavior_pack")
RP = os.path.join(MOD_ROOT, "diorama_resource_pack")
SCRIPTS = os.path.join(BP, "dioramaScripts")
UI_DIR = os.path.join(RP, "ui")
VALIDATOR = os.path.join(ROOT, "tools", "validate.py")

UUID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", re.I
)
TEXTURE_RE = re.compile(r'"texture"\s*:\s*"([^"]+)"')
MODULE_TYPES = ("data", "resources", "script", "javascript", "client_data", "skin_pack")


def _read_json(path):
    handle = open(path, "rb")
    try:
        return json.loads(handle.read().decode("utf-8"))
    finally:
        handle.close()


class ManifestTests(unittest.TestCase):
    def manifests(self):
        return {
            "behavior": _read_json(os.path.join(BP, "manifest.json")),
            "resource": _read_json(os.path.join(RP, "manifest.json")),
        }

    def test_required_directories(self):
        self.assertTrue(os.path.isdir(os.path.join(BP, "entities")))
        self.assertTrue(os.path.isfile(os.path.join(SCRIPTS, "modMain.py")))
        self.assertTrue(os.path.isdir(UI_DIR))
        self.assertTrue(os.path.isdir(os.path.join(RP, "texts")))

    def test_header_shape_and_versions(self):
        for kind, manifest in self.manifests().items():
            header = manifest["header"]
            for field in ("name", "description", "uuid", "version"):
                self.assertIn(field, header, "%s header lacks %s" % (kind, field))
            self.assertTrue(UUID_RE.match(header["uuid"]), "%s uuid" % kind)
            self.assertEqual(len(header["version"]), 3)
            for part in header["version"]:
                self.assertIsInstance(part, int)
                self.assertGreaterEqual(part, 0)
            self.assertIn("modules", manifest)
            self.assertTrue(manifest["modules"])

    def test_module_types_and_unique_uuids(self):
        seen = set()
        for kind, manifest in self.manifests().items():
            uuids = [manifest["header"]["uuid"]]
            for module in manifest["modules"]:
                self.assertIn(module["type"], MODULE_TYPES)
                uuids.append(module["uuid"])
            for value in uuids:
                lowered = value.lower()
                self.assertNotIn(lowered, seen, "duplicate uuid %s" % value)
                seen.add(lowered)

    def test_resource_pack_depends_on_behavior_pack(self):
        behavior = _read_json(os.path.join(BP, "manifest.json"))
        resource = _read_json(os.path.join(RP, "manifest.json"))
        dependencies = resource.get("dependencies") or []
        uuids = [d.get("uuid", "").lower() for d in dependencies]
        self.assertIn(behavior["header"]["uuid"].lower(), uuids)

    def test_behavior_pack_has_data_or_script_module(self):
        behavior = _read_json(os.path.join(BP, "manifest.json"))
        kinds = [m.get("type") for m in behavior["modules"]]
        self.assertTrue("data" in kinds or "script" in kinds)


class UiTests(unittest.TestCase):
    def ui_files(self):
        defs = _read_json(os.path.join(UI_DIR, "_ui_defs.json"))
        entries = defs.get("ui_defs") or []
        return entries

    def test_ui_defs_lists_existing_files(self):
        entries = self.ui_files()
        self.assertTrue(entries)
        for entry in entries:
            path = os.path.join(RP, entry.replace("/", os.sep))
            self.assertTrue(os.path.isfile(path), "missing UI file %s" % entry)

    def test_each_ui_file_has_namespace(self):
        for entry in self.ui_files():
            path = os.path.join(RP, entry.replace("/", os.sep))
            data = _read_json(path)
            self.assertIsInstance(data.get("namespace"), str, entry)

    def test_screen_files_declare_a_main_canvas(self):
        screens = ("diorama_hud", "diorama_map", "diorama_waypoints", "diorama_settings")
        for name in screens:
            path = os.path.join(UI_DIR, name + ".json")
            self.assertTrue(os.path.isfile(path), "missing %s.json" % name)
            data = _read_json(path)
            canvases = [
                key for key in data
                if key == "main" or str(key).startswith("main@")
            ]
            self.assertTrue(canvases, "%s.json has no main canvas" % name)

    def test_ui_textures_exist(self):
        for entry in self.ui_files():
            path = os.path.join(RP, entry.replace("/", os.sep))
            handle = open(path, "rb")
            try:
                raw = handle.read().decode("utf-8")
            finally:
                handle.close()
            for texture in TEXTURE_RE.findall(raw):
                candidate = os.path.join(RP, texture.replace("/", os.sep))
                self.assertTrue(
                    os.path.isfile(candidate + ".png")
                    or os.path.isfile(candidate + ".tga"),
                    "%s references missing texture %s" % (entry, texture),
                )

    def test_ui_def_keys_map_to_files(self):
        sys.path.insert(0, BP)
        try:
            from dioramaScripts.modCommon import modConfig
        finally:
            if BP in sys.path:
                sys.path.remove(BP)
        pairs = (
            (modConfig.UI_HUD, modConfig.UI_DEF_HUD),
            (modConfig.UI_MAP, modConfig.UI_DEF_MAP),
            (modConfig.UI_WAYPOINTS, modConfig.UI_DEF_WAYPOINTS),
            (modConfig.UI_SETTINGS, modConfig.UI_DEF_SETTINGS),
        )
        for key, definition in pairs:
            self.assertTrue(definition.endswith("." + key) or definition.endswith(key))
            filename = definition.rsplit(".", 1)[-1] + ".json"
            self.assertTrue(
                os.path.isfile(os.path.join(UI_DIR, filename)),
                "UI_DEF %r has no %s" % (definition, filename),
            )
            self.assertTrue(os.path.isfile(os.path.join(UI_DIR, key + ".json")))


class TextTests(unittest.TestCase):
    def test_languages_json_lists_the_lang_files(self):
        languages = _read_json(os.path.join(RP, "texts", "languages.json"))
        self.assertIn("en_US", languages)
        self.assertIn("zh_CN", languages)

    def test_lang_files_are_json_objects_with_the_same_keys(self):
        texts = os.path.join(RP, "texts")
        sets = {}
        for name in os.listdir(texts):
            if not name.endswith(".lang"):
                continue
            data = _read_json(os.path.join(texts, name))
            self.assertIsInstance(data, dict, name)
            sets[name] = set(data.keys())
        self.assertIn("en_US.lang", sets)
        self.assertIn("zh_CN.lang", sets)
        self.assertEqual(sets["en_US.lang"], sets["zh_CN.lang"])


class ToolTests(unittest.TestCase):
    def test_validator_strict_passes(self):
        if not os.path.isfile(VALIDATOR):
            self.skipTest("tools/validate.py not present")
        process = subprocess.Popen(
            [sys.executable, VALIDATOR, "--strict"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        output = process.communicate()[0].decode("utf-8", "replace")
        self.assertEqual(process.returncode, 0, output)

    def test_preview_bake_smoke(self):
        """A tiny bake exercises baker -> png without the seconds a full tile takes."""
        sys.path.insert(0, SCRIPTS)
        try:
            from modCore import png
            from modCore.baker import bake_tile
            from modCore.block_source import SyntheticWorld
            from modCore.geometry import Geometry
            from modCore.tile_key import TileKey
        except ImportError as error:  # pragma: no cover - only without modCore
            self.skipTest("modCore not importable: %s" % (error,))
            return
        finally:
            if SCRIPTS in sys.path:
                sys.path.remove(SCRIPTS)

        geometry = Geometry(16, 2, 4, "test")
        image = bake_tile(
            TileKey(TileKey.SURFACE, 0, 0, 0),
            SyntheticWorld().source(),
            geometry=geometry,
            fast=True,
        )
        self.assertEqual(image.size, geometry.tile_pixels)
        self.assertGreater(image.known_fraction(), 0.0)
        rows = png.abgr_rows(image.pixels, image.size)
        self.assertEqual(len(rows), image.size)
        self.assertEqual(len(rows[0]), image.size)


if __name__ == "__main__":
    unittest.main()
