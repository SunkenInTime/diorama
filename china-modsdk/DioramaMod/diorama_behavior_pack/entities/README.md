# Diorama behavior pack

The ModSDK requires a `entities/` directory to exist in every behavior pack,
even when the mod defines no entities. `dioramaScripts/` next to it holds the
Python: `modMain.py` is the entry point the engine loads, and the packages
around it are imported from there.

Nothing in this pack defines a custom entity, item or block: Diorama is a
client-side map, so it lives entirely in scripts and UI.
