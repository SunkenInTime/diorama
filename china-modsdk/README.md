# China ModSDK Diorama core — tests

A pure-Python, standard-library-only test-suite and runner for `modCore`, the
dependency-free Diorama maths package at
`DioramaMod/diorama_behavior_pack/dioramaScripts/modCore`. It runs on a plain PC
CPython with no game, no NetEase ModSDK and no third-party packages.

The suite is written against an interface, not against the files: point
`--core` at another importable package to test a different implementation of the
same modules.

## Running

CPython 3.8+ (developed on 3.12):

```sh
cd china-modsdk
python3 run_tests.py              # run every test
python3 run_tests.py -v           # show each test
python3 run_tests.py -q           # summary only
python3 run_tests.py test_baker   # one module
python3 run_tests.py -f           # stop at the first failure
python3 run_tests.py --list       # list without running
```

Plain `unittest` works too, because `tests/_core.py` puts the core on the path:

```sh
cd china-modsdk
python3 -m unittest discover -s tests
```

## Testing another implementation

```sh
python3 run_tests.py --core my_core
DIORAMA_CORE_MODULE=my_core python3 run_tests.py
DIORAMA_CORE_PATH=/path/to/pkg python3 run_tests.py --core my_core
```

`tests/_core.py` reads `DIORAMA_CORE_MODULE` (default `modCore`), inserts
`DIORAMA_CORE_PATH` and the default `dioramaScripts` directory on `sys.path`,
and imports the package. Every test addresses the core only through
`_core.load("<submodule>")`, so a replacement core does not have to live in this
repo or be called `modCore`.

## What is covered

| Test file | Module under test | Focus |
| --- | --- | --- |
| `test_projection.py` | `projection` | pitch clamp/fallback, `v`/`z_at` round trip, depth, refresh/copy |
| `test_geometry.py` | `geometry` | tile units/pixels, validation, runtime vs preview |
| `test_tile_key.py` | `tile_key` | LOD sizing, floor-division parents, `tile_for`, stem round trip |
| `test_noise.py` | `noise` | 32-bit block hash vs an independent oracle, smoothstep, sRGB tables |
| `test_mipmaps.py` | `mipmaps` | alpha-weighted half, chain sizes, level clamping |
| `test_bearing.py` | `bearing` | 8-point bearings, zh/en labels and arrows, distances, formatting |
| `test_waypoint.py` | `waypoint` | ids/names/colours, dimension mapping, distance, JSON round trip |
| `test_waypoint_store.py` | `waypoint_store` | load/save, damage recovery, death cap, queries, share text |
| `test_palette.py` | `palette` | kinds, Java↔Bedrock aliases, aux variants, unknown misses |
| `test_block_source.py` | `block_source` | `BlockSource` contract, in-memory/function sources, `SyntheticWorld` |
| `test_pick.py` | `pick` | map-space point, surface/water picks, cave skip, fog of war, column height |
| `test_baker.py` | `baker` | DDA trace, cave slices, small-tile bakes, saved-pixel merge, `TileImage` |
| `test_radar.py` | `radar` | entity classification, filtering, ordering, hit testing, tooltips |
| `test_map_style.py` | `map_style` | clamps, sun/shadow vectors, fluent builders, serialisation |
| `test_settings.py` | `settings` | clamped player settings, derived zoom/style, server view |
| `test_png.py` | `png` | PNG bytes re-parsed and decoded with an independent chunk walker |

The projection, DDA/heightmap sampling, LOD/mipmap and waypoint maths the port
needs are all exercised. Lighting, shading and rendering are only touched
through the router-level `baker`/`radar` tests; they need game data to check
further.

All generated files (PNG output, waypoint stores) go to `tempfile` directories
and are removed by the tests.
