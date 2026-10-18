"""Tests for modCore.waypoint_store: persistence, damage recovery, death caps."""

import json
import os
import shutil
import tempfile
import unittest

from _core import load

waypoint = load("waypoint")
waypoint_store = load("waypoint_store")


class MemoryBackendTest(unittest.TestCase):
    def test_read_write(self):
        backend = waypoint_store.MemoryBackend()
        self.assertEqual(backend.read(), "")
        self.assertTrue(backend.write("hello"))
        self.assertEqual(backend.read(), "hello")


class StoreLoadTest(unittest.TestCase):
    def _store(self, text=""):
        return waypoint_store.WaypointStore(waypoint_store.MemoryBackend(text), "world")

    def test_empty_backend_loads_empty(self):
        store = self._store()
        self.assertEqual(store.count(), 0)
        self.assertFalse(store.damaged)

    def test_round_trip_through_json(self):
        store = self._store()
        store.add(waypoint.Waypoint("Home", 10, 64, -20, dimension=1, color=0xFF12A594))
        restored = self._store(store.to_json())
        self.assertEqual(restored.count(), 1)
        mark = restored.all()[0]
        self.assertEqual((mark.name, mark.x, mark.y, mark.z), ("Home", 10, 64, -20))
        self.assertEqual(mark.dimension, 1)
        self.assertEqual(mark.color, 0xFF12A594)

    def test_dict_wrapped_root_is_accepted(self):
        text = json.dumps({"waypoints": [{"x": 1, "y": 2, "z": 3, "name": "W"}]})
        store = self._store(text)
        self.assertEqual(store.count(), 1)
        self.assertEqual(store.all()[0].name, "W")

    def test_single_bad_entry_is_skipped(self):
        text = json.dumps(
            [
                {"x": 1, "y": 2, "z": 3, "name": "kept"},
                {"name": "no position"},
                "nonsense",
            ]
        )
        store = self._store(text)
        self.assertEqual(store.count(), 1)
        self.assertTrue(store.damaged)
        self.assertEqual(store.all()[0].name, "kept")

    def test_bad_json_reports_damage_and_backs_up(self):
        backend = waypoint_store.MemoryBackend("{not json")
        store = waypoint_store.WaypointStore(backend, "world")
        self.assertEqual(store.count(), 0)
        self.assertTrue(store.damaged)
        self.assertTrue(hasattr(backend, "last_backup"))

    def test_non_list_root_reports_damage(self):
        store = self._store(json.dumps(42))
        self.assertTrue(store.damaged)
        self.assertEqual(store.count(), 0)

    def test_broken_backend_does_not_raise(self):
        class Broken(object):
            def read(self):
                raise IOError("boom")

            def write(self, text):
                raise IOError("boom")

            def backup(self, suffix):
                return None

        store = waypoint_store.WaypointStore(Broken(), "world")
        self.assertTrue(store.damaged)
        self.assertFalse(store.save())


class StoreQueryTest(unittest.TestCase):
    def setUp(self):
        self.store = waypoint_store.WaypointStore(waypoint_store.MemoryBackend(), "world")
        self.store.add(waypoint.Waypoint("near", 0, 0, 0, dimension=0))
        self.store.add(waypoint.Waypoint("far", 100, 0, 0, dimension=0))
        self.store.add(waypoint.Waypoint("nether", 5, 5, 5, dimension=1))
        self.store.all()[2].visible = False

    def test_in_dimension(self):
        self.assertEqual([w.name for w in self.store.in_dimension(0)], ["near", "far"])
        self.assertEqual([w.name for w in self.store.in_dimension(1)], ["nether"])

    def test_visible_in_dimension_hides_invisible(self):
        self.assertEqual([w.name for w in self.store.visible_in_dimension(1)], [])

    def test_by_id(self):
        mark = self.store.all()[0]
        self.assertIs(self.store.by_id(mark.id), mark)
        self.assertIsNone(self.store.by_id("missing"))

    def test_nearest(self):
        self.assertEqual(self.store.nearest(0, 0, 0).name, "near")
        self.assertEqual(self.store.nearest(99, 0, 0).name, "far")

    def test_nearest_with_dimension_and_maximum(self):
        self.assertIsNone(self.store.nearest(0, 0, 0, dimension=1, maximum=1.0))
        self.assertEqual(self.store.nearest(0, 0, 0, maximum=10.0).name, "near")

    def test_all_returns_a_copy(self):
        snapshot = self.store.all()
        snapshot.append("junk")
        self.assertEqual(self.store.count(), 3)


class StoreMutationTest(unittest.TestCase):
    def setUp(self):
        self.store = waypoint_store.WaypointStore(waypoint_store.MemoryBackend(), "world")

    def test_add_returns_the_waypoint_and_saves(self):
        mark = waypoint.Waypoint("Home")
        self.assertIs(self.store.add(mark), mark)
        self.assertEqual(self.store.count(), 1)
        self.assertIn("Home", self.store.backend.read())

    def test_remove_and_remove_by_id(self):
        mark = waypoint.Waypoint("Home")
        self.store.add(mark)
        self.assertTrue(self.store.remove(mark))
        self.assertFalse(self.store.remove(mark))
        self.store.add(mark)
        self.assertTrue(self.store.remove_by_id(mark.id))
        self.assertFalse(self.store.remove_by_id("missing"))

    def test_death_markers_keep_only_the_newest(self):
        for index in range(5):
            death = waypoint.Waypoint("Death %d" % index, kind=waypoint.KIND_DEATH)
            death.created = index
            self.store.add(death)
        kept = sorted(w.created for w in self.store.all() if w.kind == waypoint.KIND_DEATH)
        self.assertEqual(kept, [2, 3, 4])

    def test_death_trim_leaves_manual_waypoints_alone(self):
        self.store.add(waypoint.Waypoint("home"))
        for index in range(5):
            death = waypoint.Waypoint("d%d" % index, kind=waypoint.KIND_DEATH)
            death.created = index
            self.store.add(death)
        self.assertEqual(len([w for w in self.store.all() if w.kind == waypoint.KIND_MANUAL]), 1)

    def test_replace_all_trims_deaths(self):
        deaths = []
        for index in range(6):
            death = waypoint.Waypoint("d%d" % index, kind=waypoint.KIND_DEATH)
            death.created = index
            deaths.append(death)
        self.store.replace_all(deaths)
        self.assertEqual(self.store.count(), waypoint.DEATHS_KEPT)

    def test_changed_saves_in_place_edits(self):
        mark = waypoint.Waypoint("Home")
        self.store.add(mark)
        mark.name = "Base"
        self.store.changed()
        self.assertIn("Base", self.store.backend.read())


class ShareTextTest(unittest.TestCase):
    def setUp(self):
        self.store = waypoint_store.WaypointStore(waypoint_store.MemoryBackend(), "world")

    def test_export_and_parse_round_trip(self):
        mark = waypoint.Waypoint("Iron Farm", 10, 64, -20, dimension=1, color=0xFF12A594)
        text = self.store.export_share_text(mark)
        parsed = waypoint_store.WaypointStore.parse_share_text(text)
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed.name, "Iron Farm")
        self.assertEqual((parsed.x, parsed.y, parsed.z), (10, 64, -20))
        self.assertEqual(parsed.dimension, 1)
        self.assertEqual(parsed.color, 0xFF12A594)

    def test_parse_without_position_is_none(self):
        self.assertIsNone(waypoint_store.WaypointStore.parse_share_text("no at sign"))
        self.assertIsNone(waypoint_store.WaypointStore.parse_share_text("[Diorama] Name @ 1 2"))

    def test_parse_empty_is_none(self):
        self.assertIsNone(waypoint_store.WaypointStore.parse_share_text(""))
        self.assertIsNone(waypoint_store.WaypointStore.parse_share_text(None))

    def test_parse_uses_the_last_at_sign(self):
        parsed = waypoint_store.WaypointStore.parse_share_text("[Diorama] A @ B @ 1 2 3")
        self.assertEqual(parsed.name, "A @ B")
        self.assertEqual((parsed.x, parsed.y, parsed.z), (1, 2, 3))


class FileBackendTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.mkdtemp(prefix="diorama-store-")
        self.path = os.path.join(self.directory, "waypoints.json")

    def tearDown(self):
        shutil.rmtree(self.directory, ignore_errors=True)

    def test_save_and_reload(self):
        backend = waypoint_store.FileBackend(self.path)
        store = waypoint_store.WaypointStore(backend, "world")
        self.assertFalse(os.path.isfile(self.path))
        store.add(waypoint.Waypoint("Home", 1, 2, 3))
        self.assertTrue(os.path.isfile(self.path))
        self.assertFalse(os.path.exists(self.path + ".tmp"))

        restored = waypoint_store.WaypointStore(waypoint_store.FileBackend(self.path), "world")
        self.assertEqual(restored.count(), 1)
        self.assertEqual(restored.all()[0].name, "Home")

    def test_unreadable_file_is_copied_aside(self):
        with open(self.path, "w") as handle:
            handle.write("this is not json")
        backend = waypoint_store.FileBackend(self.path)
        store = waypoint_store.WaypointStore(backend, "world")
        self.assertTrue(store.damaged)
        self.assertTrue(backend.last_backup)
        self.assertTrue(os.path.isfile(backend.last_backup))

    def test_missing_file_reads_empty(self):
        backend = waypoint_store.FileBackend(self.path)
        self.assertEqual(backend.read(), "")


if __name__ == "__main__":
    unittest.main()
