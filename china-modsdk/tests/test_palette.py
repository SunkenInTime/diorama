"""Tests for modCore.palette: block looks, aliases, aux variants and misses."""

import unittest

from _core import load

palette = load("palette")


class TableTest(unittest.TestCase):
    def test_verify_names_all_resolve(self):
        for name in palette.VERIFY_NAMES:
            self.assertTrue(palette.is_known(name), name)
            self.assertNotEqual(palette.lookup(name).kind, palette.KIND_AIR, name)

    def test_representative_kinds(self):
        self.assertEqual(palette.lookup("minecraft:air").kind, palette.KIND_AIR)
        self.assertEqual(palette.lookup("minecraft:stone").kind, palette.KIND_SOLID)
        self.assertEqual(palette.lookup("minecraft:water").kind, palette.KIND_WATER)
        self.assertEqual(palette.lookup("minecraft:lava").kind, palette.KIND_LAVA)
        self.assertEqual(palette.lookup("minecraft:leaves").kind, palette.KIND_PLANT)
        self.assertEqual(palette.lookup("minecraft:glass").kind, palette.KIND_GLASS)
        self.assertEqual(palette.lookup("minecraft:torch").kind, palette.KIND_PLANT)

    def test_block_look_side_defaults_to_top(self):
        look = palette.BlockLook(palette.KIND_SOLID, (1, 2, 3))
        self.assertEqual(look.side, look.top)

    def test_biome_tints_present_for_all_kinds(self):
        for kind in (palette.TINT_GRASS, palette.TINT_FOLIAGE, palette.TINT_DRY_FOLIAGE, palette.TINT_WATER):
            tint = palette.DEFAULT_TINTS[kind]
            self.assertEqual(len(tint), 3)
            for channel in tint:
                self.assertTrue(0 <= channel <= 255)


class AliasTest(unittest.TestCase):
    def test_java_names_resolve_to_the_bedrock_row(self):
        self.assertEqual(
            palette.lookup("minecraft:grass_block").top,
            palette.lookup("minecraft:grass").top,
        )
        self.assertEqual(
            palette.lookup("minecraft:oak_log").top,
            palette.lookup("minecraft:log").top,
        )
        self.assertEqual(
            palette.lookup("minecraft:terracotta").top,
            palette.lookup("minecraft:hardened_clay").top,
        )

    def test_aliases_are_known(self):
        for name in palette.ALIASES:
            self.assertTrue(palette.is_known(name), name)


class AuxTest(unittest.TestCase):
    def test_leaves_aux_selects_the_species(self):
        oak = palette.lookup("minecraft:leaves", 0)
        spruce = palette.lookup("minecraft:leaves", 1)
        birch = palette.lookup("minecraft:leaves", 2)
        self.assertNotEqual(oak.top, spruce.top)
        self.assertNotEqual(spruce.top, birch.top)

    def test_log_aux_selects_the_species(self):
        self.assertNotEqual(
            palette.lookup("minecraft:log", 0).top,
            palette.lookup("minecraft:log", 2).top,
        )

    def test_stained_clay_aux_colours(self):
        self.assertNotEqual(
            palette.lookup("minecraft:stained_hardened_clay", 0).top,
            palette.lookup("minecraft:stained_hardened_clay", 3).top,
        )

    def test_aux_falls_back_to_the_base_row(self):
        base = palette.lookup("minecraft:stone", 0)
        self.assertEqual(palette.lookup("minecraft:stone", 9).top, base.top)

    def test_alias_without_an_explicit_row_resolves(self):
        # white_terracotta has no TABLE row, so it resolves through ALIASES.
        self.assertTrue(palette.is_known("minecraft:white_terracotta"))
        self.assertEqual(
            palette.lookup("minecraft:white_terracotta").top,
            palette.lookup("minecraft:stained_hardened_clay").top,
        )

    def test_java_names_ignore_aux(self):
        # Java names are already species-specific rows, so aux does not reroute them.
        self.assertEqual(
            palette.lookup("minecraft:oak_log", 2).top,
            palette.lookup("minecraft:oak_log").top,
        )


class MissTest(unittest.TestCase):
    def test_unknown_block_is_the_grey_default(self):
        look = palette.lookup("minecraft:not_a_block")
        self.assertIs(look, palette.UNKNOWN_SOLID)
        self.assertEqual(look.kind, palette.KIND_SOLID)

    def test_miss_records_the_name_once(self):
        seen = set()
        palette.lookup("minecraft:mystery", 0, seen)
        palette.lookup("minecraft:mystery", 0, seen)
        palette.lookup("minecraft:another", 0, seen)
        self.assertEqual(seen, {"minecraft:mystery", "minecraft:another"})

    def test_known_blocks_are_not_recorded(self):
        seen = set()
        palette.lookup("minecraft:stone", 0, seen)
        self.assertEqual(seen, set())

    def test_none_is_air_without_recording(self):
        seen = set()
        look = palette.lookup(None, 0, seen)
        self.assertEqual(look.kind, palette.KIND_AIR)
        self.assertEqual(seen, set())

    def test_empty_name_is_air(self):
        self.assertEqual(palette.lookup("").kind, palette.KIND_AIR)
        self.assertFalse(palette.is_known(""))


if __name__ == "__main__":
    unittest.main()
