# -*- coding: utf-8 -*-
"""End-to-end tests for the ModSDK components, with fake engine objects.

``modServer`` and ``modClient`` import ``dioramaScripts.*`` (that is what the
engine resolves), so this module puts the behaviour-pack root on ``sys.path``
itself instead of using the ``modCore``-only ``_core`` helper.

Nothing here needs the game: the engine is replaced by the smallest objects the
components actually call. That is exactly the seam the adapters exist for -
if a component starts calling a new engine API, one of these tests fails and
points at it.
"""

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
PACK_ROOT = os.path.normpath(
    os.path.join(
        HERE, os.pardir, "DioramaMod", "diorama_behavior_pack"
    )
)
if PACK_ROOT not in sys.path:
    sys.path.insert(0, PACK_ROOT)

try:
    from dioramaScripts.modCommon import log as mod_log

    # The components log through modCommon.log, which prints when the engine's
    # mod_log is absent. That is correct in game and noise in a test run.
    mod_log.ENABLED = False

    from dioramaScripts.modCore.block_source import NO_ROOF
    from dioramaScripts.modCore.projection import Projection
    from dioramaScripts.modCore.tile_key import TileKey
    from dioramaScripts.modCore.waypoint_store import MemoryBackend
    from dioramaScripts.modServer.components.MapServerComponent import MapServerComponent
    from dioramaScripts.modServer.components.RadarServerComponent import RadarServerComponent

    AVAILABLE = True
except ImportError as exc:  # pragma: no cover - only when the tree is incomplete
    IMPORT_ERROR = exc
    AVAILABLE = False

STONE = "minecraft:stone"
AIR = "minecraft:air"
SURFACE = TileKey.SURFACE


# -- fake engine -----------------------------------------------------------


class FakeBlockInfo(object):
    def __init__(self, cells, top=10):
        self.cells = cells
        self.top = top

    def GetBlockNew(self, pos, dimension_id):
        return {"name": self.cells.get(pos, AIR), "aux": 0}

    def GetTopBlockHeight(self, column, dimension_id):
        return self.top

    def GetBlockLight(self, pos, dimension_id):
        return 0

    def GetBlockSkyLight(self, pos, dimension_id):
        return 15


class FakeFactory(object):
    def __init__(self, block_info):
        self.block_info = block_info

    def CreateBlockInfo(self, level_id):
        return self.block_info


class FakeApi(object):
    def __init__(self, block_info):
        self.factory = FakeFactory(block_info)

    def GetEngineCompFactory(self):
        return self.factory


class FakeServer(object):
    """The slice of ``DioramaServerSystem`` the components use."""

    def __init__(self, cells, top=10):
        self.api = FakeApi(FakeBlockInfo(cells, top))
        self.level_id = "0"
        self.dimension_id = 0
        self.min_y, self.max_y, self.top_y = 0, 64, top + 30
        self.sent = []
        self.players = ["p1"]

    def roof_for(self, dimension_id):
        return NO_ROOF

    def pick_start_y(self, layer):
        if layer == SURFACE:
            return self.top_y
        return min(int(layer), self.max_y)

    def player_ids(self):
        return list(self.players)

    def notify(self, player_id, event_name, payload):
        self.sent.append((player_id, event_name, payload))
        return True

    def events(self, name):
        return [payload for (_p, event, payload) in self.sent if event == name]


def flat_world(top=10, extent=32):
    cells = {}
    for x in range(-extent, extent):
        for z in range(-extent, extent):
            for y in range(0, top + 1):
                cells[(x, y, z)] = STONE
    return cells


# -- server components -----------------------------------------------------


@unittest.skipUnless(AVAILABLE, "packages not importable")
class MapServerComponentTest(unittest.TestCase):
    def setUp(self):
        self.server = FakeServer(flat_world())
        self.map = MapServerComponent(self.server)
        self.projection = Projection(50)

    def test_pick_answers_with_the_standing_spot(self):
        v = self.projection.v(11, 5.5)
        result = self.map.on_pick_request(
            {"playerId": "p1", "u": 5.5, "v": v, "layer": SURFACE}
        )
        self.assertTrue(result["ok"])
        self.assertEqual((result["x"], result["y"], result["z"]), (5, 11, 5))

    def test_pick_is_sent_to_the_asking_client(self):
        self.map.on_pick_request(
            {"playerId": "p1", "u": 5.5, "v": self.projection.v(11, 5.5), "layer": SURFACE}
        )
        payloads = self.server.events("diorama:pick_result")
        self.assertEqual(len(payloads), 1)
        self.assertTrue(payloads[0]["ok"])

    def test_pick_outside_the_world_is_a_refusal_not_an_error(self):
        result = self.map.on_pick_request(
            {"playerId": "p1", "u": 500.0, "v": 500.0, "layer": SURFACE}
        )
        self.assertFalse(result["ok"])

    def test_runtime_baking_is_refused_by_default(self):
        """A tile bake is seconds of Python on the game thread; refuse it."""
        self.map.on_tile_request({"playerId": "p1", "key": [SURFACE, 0, 0, 0]})
        payloads = self.server.events("diorama:tile")
        self.assertEqual(len(payloads), 1)
        self.assertFalse(payloads[0]["ok"])
        self.assertEqual(payloads[0]["reason"], "runtime_bake_disabled")
        self.assertEqual(self.map.stats()["queued"], 0)

    def test_malformed_tile_request_is_ignored(self):
        self.map.on_tile_request({"playerId": "p1", "key": [1, 2]})
        self.map.on_tile_request({"playerId": "p1"})
        self.assertEqual(self.server.events("diorama:tile"), [])

    def test_the_engine_source_is_built_once_per_dimension(self):
        self.map.on_pick_request(
            {"playerId": "p1", "u": 1.5, "v": self.projection.v(11, 1.5), "layer": SURFACE}
        )
        self.map.on_pick_request(
            {"playerId": "p1", "u": 2.5, "v": self.projection.v(11, 2.5), "layer": SURFACE}
        )
        self.assertEqual(self.map.stats()["sources"], 1)
        self.assertIsNotNone(self.map._sources[0])


class FakeQuery(object):
    """Stands in for ``EntityQuery``, which would call the engine."""

    def __init__(self, viewer, entities):
        self.viewer = viewer
        self.entities = entities

    def describe(self, entity_id):
        if entity_id == self.viewer["id"]:
            return dict(self.viewer)
        for entity in self.entities:
            if entity["id"] == entity_id:
                return dict(entity)
        return None

    def collect(self, centre, radius, entity_id=None):
        records = []
        if entity_id:
            records.append(self.describe(entity_id))
        records.extend(dict(entity) for entity in self.entities)
        return [record for record in records if record is not None]


@unittest.skipUnless(AVAILABLE, "packages not importable")
class RadarServerComponentTest(unittest.TestCase):
    def setUp(self):
        self.server = FakeServer(flat_world())
        self.radar = RadarServerComponent(self.server, interval=0.0)
        self.viewer = {
            "id": "p1",
            "type": "minecraft:player",
            "x": 0.0,
            "y": 64.0,
            "z": 0.0,
            "name": "Steve",
            "player": True,
            "yaw": 0.0,
            "dim": 0,
        }
        self.entities = [
            {"id": "z", "type": "minecraft:zombie", "x": 4.0, "y": 64.0, "z": 4.0, "name": "Zombie"},
            {"id": "c", "type": "minecraft:cow", "x": -6.0, "y": 70.0, "z": 2.0, "name": "Cow"},
            {"id": "i", "type": "minecraft:item", "x": 1.0, "y": 64.0, "z": 1.0, "name": "Item"},
        ]
        self.radar._query = FakeQuery(self.viewer, self.entities)

    def test_scan_pushes_classified_blips(self):
        self.radar.scan_once()
        payloads = self.server.events("diorama:radar")
        self.assertEqual(len(payloads), 1)
        blips = payloads[0]["blips"]
        # items are dropped, the viewer is not a blip, passive sorts first
        self.assertEqual([blip["id"] for blip in blips], ["c", "z"])
        self.assertEqual([blip["kind"] for blip in blips], ["passive", "hostile"])

    def test_height_difference_travels_with_the_blip(self):
        self.radar.scan_once()
        blips = self.server.events("diorama:radar")[0]["blips"]
        by_id = dict((blip["id"], blip) for blip in blips)
        self.assertEqual(by_id["c"]["dy"], 6.0)
        self.assertEqual(by_id["z"]["dy"], 0.0)

    def test_settings_hide_a_kind(self):
        self.radar.on_settings({"playerId": "p1", "settings": {"radar_hostile": False}})
        self.radar.scan_once()
        blips = self.server.events("diorama:radar")[0]["blips"]
        self.assertEqual([blip["id"] for blip in blips], ["c"])

    def test_tick_is_throttled(self):
        self.radar.interval = 60.0
        self.radar.tick()
        self.radar.tick()
        self.assertEqual(len(self.server.events("diorama:radar")), 1)


# -- client components -----------------------------------------------------


class FakeClient(object):
    """The slice of ``DioramaClientSystem`` the client components use."""

    def __init__(self):
        from dioramaScripts.modClient.components.SettingsClientComponent import (
            SettingsClientComponent,
        )
        from dioramaScripts.modCommon import modConfig

        self.player_id = "p1"
        self.pos = (120.5, 70.0, -40.5)
        self.dim = 0
        self.world_key = "seed-42"
        self.notices = []
        self.sent = []
        self.settings = SettingsClientComponent(self, MemoryBackend())
        del modConfig

    # the interface the components expect
    def player_position(self):
        return self.pos

    def dimension_id(self):
        return self.dim

    def notice(self, text):
        self.notices.append(text)
        return text

    def notify_server(self, event_name, payload):
        self.sent.append((event_name, payload))
        return True

    def notify(self, player_id, event_name, payload):
        return player_id == self.player_id

    def projection(self):
        return Projection(50)

    def geometry(self):
        from dioramaScripts.modCore.geometry import GEOMETRY

        return GEOMETRY

    def block_source(self):
        return None


@unittest.skipUnless(AVAILABLE, "packages not importable")
class WaypointClientComponentTest(unittest.TestCase):
    def setUp(self):
        from dioramaScripts.modClient.components.WaypointClientComponent import (
            WaypointClientComponent,
        )

        self.client = FakeClient()
        self.waypoints = WaypointClientComponent(self.client, MemoryBackend())

    def test_add_at_player_uses_the_player_position(self):
        waypoint = self.waypoints.add_at_player("Base")
        self.assertEqual((waypoint.x, waypoint.y, waypoint.z), (120, 70, -40))
        self.assertEqual(waypoint.dimension, 0)
        self.assertEqual(self.waypoints.all(), [waypoint])

    def test_waypoints_survive_a_reload_through_the_backend(self):
        from dioramaScripts.modClient.components.WaypointClientComponent import (
            WaypointClientComponent,
        )

        backend = MemoryBackend()
        first = WaypointClientComponent(self.client, backend)
        first.add_at_player("Base")
        second = WaypointClientComponent(self.client, backend)
        self.assertEqual([w.name for w in second.all()], ["Base"])

    def test_death_markers_are_capped(self):
        for _ in range(5):
            self.waypoints.on_player_died()
        deaths = [w for w in self.waypoints.all() if w.kind == "death"]
        self.assertEqual(len(deaths), 3)

    def test_death_markers_respect_the_setting(self):
        self.client.settings.settings.death_markers = False
        self.assertIsNone(self.waypoints.on_player_died())
        self.assertEqual(self.waypoints.all(), [])

    def test_share_sends_the_waypoint_and_announces_it(self):
        waypoint = self.waypoints.add_at_player("Base")
        self.waypoints.share(waypoint.id)
        events = [name for (name, _payload) in self.client.sent]
        self.assertIn("diorama:share_waypoint", events)
        self.assertTrue(self.client.notices)

    def test_nearest_picks_the_closest_waypoint(self):
        near = self.waypoints.add_at(121, 70, -40, "Near")
        self.waypoints.add_at(400, 70, 400, "Far")
        self.assertEqual(self.waypoints.nearest_to_player().id, near.id)

    def test_describe_includes_bearing_and_distance(self):
        # The player stands at z = -40.5; smaller z is north.
        waypoint = self.waypoints.add_at(120, 70, -50, "North")
        text = self.waypoints.describe(waypoint)
        self.assertIn("North", text)
        self.assertIn("m", text)
        from dioramaScripts.modCore import bearing

        self.assertIn(bearing.bearing_arrow(0.0), text)

    def test_shared_waypoints_arrive_and_can_be_imported(self):
        self.waypoints.on_shared(
            {
                "shared": [
                    {
                        "id": "abc",
                        "name": "Shared",
                        "x": 1,
                        "y": 2,
                        "z": 3,
                        "dimension": 0,
                        "kind": "manual",
                    }
                ]
            }
        )
        self.assertEqual(len(self.waypoints.shared), 1)
        imported = self.waypoints.import_shared("abc")
        self.assertEqual(imported.x, 1)
        self.assertEqual(self.waypoints.all()[0].name, "Shared (copy)")

    def test_chat_share_line_is_parsed(self):
        parsed = self.waypoints.on_chat_share("[Diorama] Home @ 10 64 -3 dim=0 color=255")
        self.assertIsNotNone(parsed)
        self.assertEqual((parsed.x, parsed.y, parsed.z), (10, 64, -3))

    def test_recolour_cycles_through_the_palette(self):
        waypoint = self.waypoints.add_at_player("Base")
        before = waypoint.color
        self.assertTrue(self.waypoints.recolor(waypoint.id, 0x123456))
        self.assertEqual(self.waypoints.by_id(waypoint.id).color, 0x123456)
        self.assertNotEqual(before, 0x123456)


@unittest.skipUnless(AVAILABLE, "packages not importable")
class SettingsClientComponentTest(unittest.TestCase):
    def setUp(self):
        self.client = FakeClient()
        self.settings = self.client.settings

    def test_cycle_walks_the_offered_values(self):
        from dioramaScripts.modCore.settings import RANGES

        seen = []
        for _ in range(len(RANGES)):
            seen.append(self.settings.cycle_radar_range())
        self.assertEqual(sorted(seen), sorted(RANGES))

    def test_toggle_flips_and_persists(self):
        backend = MemoryBackend()
        from dioramaScripts.modClient.components.SettingsClientComponent import (
            SettingsClientComponent,
        )

        first = SettingsClientComponent(self.client, backend)
        value = first.toggle("minimap")
        self.assertFalse(value)
        second = SettingsClientComponent(self.client, backend)
        self.assertFalse(second.settings.minimap)

    def test_server_sync_carries_only_the_radar_subset(self):
        self.settings.toggle("minimap")
        names = [name for (name, _payload) in self.client.sent]
        self.assertIn("diorama:sync_settings", names)
        payload = [p for (n, p) in self.client.sent if n == "diorama:sync_settings"][-1]
        self.assertIn("radar_range", payload["settings"])
        self.assertNotIn("minimap", payload["settings"])

    def test_reset_returns_defaults(self):
        self.settings.toggle("radar_hostile")
        self.settings.reset()
        self.assertTrue(self.settings.settings.radar_hostile)


if __name__ == "__main__":
    unittest.main()
