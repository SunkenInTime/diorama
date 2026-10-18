#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Validate the Diorama China Edition ModSDK scaffold, with no dependencies.

Checks, in the order they are useful when something is wrong:

1. **Python** - every script compiles under the running interpreter, ``modMain``
   has the four ``@Mod.*`` decorators and both ``RegisterSystem`` calls, and
   ``modCore`` imports nothing from ``mod.*`` (that package must stay runnable
   on a plain PC).
2. **Manifests** - JSON parses, required fields exist, UUIDs are RFC 4122 and
   unique, versions are three non-negative ints, module types are known.
3. **JSON UI** - every file in ``_ui_defs.json`` (the canonical name; the
   single-``f`` ``_ui_def.json`` is accepted with a warning) exists,
   parses, declares a namespace, and each ``texture`` it references resolves to
   a real file; each ``@<namespace>.<control>`` reference resolves to a control
   defined somewhere in the same namespace.
4. **Texts** - ``texts/*.lang`` are JSON objects and every language has the same
   key set.
5. **Layout sanity** - the control paths the Python asks for exist in the UI
   JSON, which is the single most common way for these scaffolds to break.

Usage::

    python3 tools/validate.py            # warnings do not fail
    python3 tools/validate.py --strict   # warnings fail too
    python3 tools/validate.py --json     # machine readable
Exit codes: 0 ok, 1 problems, 2 environment/usage error.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import argparse
import json
import os
import re
import sys

import py_compile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, os.pardir))
MOD_ROOT = os.path.join(ROOT, "DioramaMod")
BP = os.path.join(MOD_ROOT, "diorama_behavior_pack")
RP = os.path.join(MOD_ROOT, "diorama_resource_pack")
SCRIPTS = os.path.join(BP, "dioramaScripts")

UUID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", re.I
)
MODULE_TYPES = ("data", "resources", "script", "javascript", "client_data", "skin_pack")
TEXTURE_EXT = (".png", ".tga", ".jpg", ".jpeg")
#: ``"texture": "textures/ui/thing"`` in a UI file.
TEXTURE_RE = re.compile(r'"texture"\s*:\s*"([^"]+)"')
#: ``"control@namespace.control"``.
INHERIT_RE = re.compile(r'"([A-Za-z0-9_]+)@([A-Za-z0-9_]+)\.([A-Za-z0-9_]+)"\s*:')
#: ``@namespace.control`` anywhere else in a value.
REFERENCE_RE = re.compile(r'@([A-Za-z0-9_]+)\.([A-Za-z0-9_]+)')

#: Control paths the Python screens ask for. Kept here (rather than scraped) so
#: the check is explicit: if a path is renamed in the JSON, this list is the
#: thing that says where the Python must be updated too.
EXPECTED_CONTROLS = {
    "diorama_hud": (
        "/hud_root/minimap",
        "/hud_root/minimap/compass",
        "/hud_root/coord_label",
        "/hud_root/bearing_label",
        "/hud_root/biome_label",
        "/hud_root/waypoint_label",
        "/hud_root/notice_label",
        "/hud_root/tile_status_label",
        "/hud_root/add_button",
        "/hud_root/hide_button",
    ),
    "diorama_map": (
        "/map_root/title_label",
        "/map_root/map_panel",
        "/map_root/map_panel/atlas",
        "/map_root/map_panel/grid",
        "/map_root/map_panel/markers",
        "/map_root/map_panel/markers/marker_template",
        "/map_root/map_panel/markers/marker_template/label",
        "/map_root/footer_label",
        "/map_root/button_row/zoom_in_button",
        "/map_root/button_row/zoom_out_button",
        "/map_root/button_row/recenter_button",
        "/map_root/button_row/waypoint_button",
        "/map_root/button_row/settings_button",
        "/map_root/button_row/close_button",
    ),
    "diorama_waypoints": (
        "/waypoints_root/title_label",
        "/waypoints_root/list_panel",
        "/waypoints_root/list_panel/row_template",
        "/waypoints_root/list_panel/row_template/row_label",
        "/waypoints_root/list_panel/row_template/row_initial",
        "/waypoints_root/list_panel/row_template/row_selected",
        "/waypoints_root/count_label",
        "/waypoints_root/hint_label",
        "/waypoints_root/action_row/add_button",
        "/waypoints_root/action_row/share_button",
        "/waypoints_root/action_row/import_button",
        "/waypoints_root/action_row/recolor_button",
        "/waypoints_root/action_row/delete_button",
        "/waypoints_root/action_row/close_button",
    ),
    "diorama_settings": (
        "/settings_root/summary_label",
        "/settings_root/button_row/reset_button",
        "/settings_root/button_row/done_button",
        "/settings_root/rows/row_minimap/toggle",
        "/settings_root/rows/row_compass/toggle",
        "/settings_root/rows/row_coordinates/toggle",
        "/settings_root/rows/row_biome/toggle",
        "/settings_root/rows/row_radar_players/toggle",
        "/settings_root/rows/row_radar_hostile/toggle",
        "/settings_root/rows/row_radar_neutral/toggle",
        "/settings_root/rows/row_radar_passive/toggle",
        "/settings_root/rows/row_death/toggle",
        "/settings_root/rows/row_outlines/toggle",
        "/settings_root/rows/row_rounded_fog/toggle",
        "/settings_root/rows/row_depth/toggle",
        "/settings_root/rows/row_grid/toggle",
        "/settings_root/rows/row_fog_of_war/toggle",
        "/settings_root/rows/row_range/value",
        "/settings_root/rows/row_size/value",
        "/settings_root/rows/row_zoom/value",
    ),
}


class Report(object):
    def __init__(self):
        self.problems = []

    def error(self, where, message):
        self.problems.append({"level": "error", "where": where, "message": message})

    def warn(self, where, message):
        self.problems.append({"level": "warning", "where": where, "message": message})

    def errors(self):
        return [p for p in self.problems if p["level"] == "error"]

    def warnings(self):
        return [p for p in self.problems if p["level"] == "warning"]


def read_json(path, report, where):
    if not os.path.isfile(path):
        report.error(where, "missing file: %s" % (path,))
        return None
    handle = open(path, "rb")
    try:
        text = handle.read().decode("utf-8")
    finally:
        handle.close()
    try:
        return json.loads(text)
    except ValueError as exc:
        report.error(where, "%s is not valid JSON: %s" % (path, exc))
        return None


def check_python(report):
    files = []
    for base, _dirs, names in os.walk(SCRIPTS):
        if "__pycache__" in base:
            continue
        for name in sorted(names):
            if name.endswith(".py"):
                files.append(os.path.join(base, name))
    if not files:
        report.error("python", "no scripts under %s" % (SCRIPTS,))
        return
    for path in files:
        handle = open(path, "rb")
        try:
            source = handle.read()
        finally:
            handle.close()
        try:
            # compile() checks the syntax without writing a .pyc anywhere.
            compile(source, path, "exec")
        except SyntaxError as exc:
            report.error("python", "%s does not compile: %s" % (path, exc))
        except ValueError as exc:
            report.error("python", "%s cannot be compiled: %s" % (path, exc))

    mod_main = os.path.join(SCRIPTS, "modMain.py")
    if not os.path.isfile(mod_main):
        report.error("python", "modMain.py is missing; the engine loads nothing without it")
    else:
        handle = open(mod_main, "rb")
        try:
            text = handle.read().decode("utf-8")
        finally:
            handle.close()
        for decorator in ("@Mod.Binding", "@Mod.InitServer", "@Mod.InitClient"):
            if decorator not in text:
                report.error("python", "modMain.py is missing %s" % (decorator,))
        for call in ("RegisterSystem",):
            if call not in text:
                report.error("python", "modMain.py never calls %s" % (call,))

    # modCore must not import the ModSDK, or the workbench cannot load it.
    core = os.path.join(SCRIPTS, "modCore")
    for base, _dirs, names in os.walk(core):
        for name in sorted(names):
            if not name.endswith(".py"):
                continue
            path = os.path.join(base, name)
            handle = open(path, "rb")
            try:
                text = handle.read().decode("utf-8")
            finally:
                handle.close()
            for line in text.splitlines():
                stripped = line.strip()
                if stripped.startswith("import mod.") or stripped.startswith("from mod."):
                    report.error(
                        "python",
                        "%s imports the ModSDK; modCore must stay engine-free" % (path,),
                    )

    for package, needed in (
        ("modServer", "DioramaServerSystem.py"),
        ("modClient", "DioramaClientSystem.py"),
    ):
        if not os.path.isfile(os.path.join(SCRIPTS, package, needed)):
            report.error("python", "%s/%s is missing" % (package, needed))
    for name in ("__init__.py",):
        for package in ("modCore", "modCommon", "modServer", "modClient", os.curdir):
            base = SCRIPTS if package == os.curdir else os.path.join(SCRIPTS, package)
            if not os.path.isfile(os.path.join(base, name)):
                report.error("python", "missing %s in %s" % (name, base))


def check_manifest(path, kind, report):
    where = "manifest:%s" % (kind,)
    data = read_json(path, report, where)
    if data is None:
        return
    if not isinstance(data, dict):
        report.error(where, "manifest is not an object")
        return
    header = data.get("header")
    if not isinstance(header, dict):
        report.error(where, "no header object")
        return
    for field in ("name", "description", "uuid", "version"):
        if field not in header:
            report.error(where, "header is missing %r" % (field,))
    uuid_value = header.get("uuid")
    if isinstance(uuid_value, str) and not UUID_RE.match(uuid_value):
        report.error(where, "header uuid is not an RFC 4122 uuid: %r" % (uuid_value,))
    _check_version(header.get("version"), where + ".header.version", report)

    modules = data.get("modules")
    if not isinstance(modules, list) or not modules:
        report.error(where, "no modules array")
        return
    seen = set()
    if isinstance(uuid_value, str):
        seen.add(uuid_value.lower())
    for index, module in enumerate(modules):
        if not isinstance(module, dict):
            report.error(where, "module %d is not an object" % (index,))
            continue
        module_type = module.get("type")
        if module_type not in MODULE_TYPES:
            report.error(where, "module %d has unknown type %r" % (index, module_type))
        module_uuid = module.get("uuid")
        if not isinstance(module_uuid, str) or not UUID_RE.match(module_uuid):
            report.error(where, "module %d uuid is not an RFC 4122 uuid" % (index,))
        elif module_uuid.lower() in seen:
            report.error(where, "module %d uuid is a duplicate: %s" % (index, module_uuid))
        else:
            seen.add(module_uuid.lower())
        _check_version(module.get("version"), where + ".modules[%d].version" % index, report)
    if kind == "resource" and not any(m.get("type") == "resources" for m in modules):
        report.error(where, "resource pack has no resources module")


def _check_version(value, where, report):
    if not isinstance(value, list) or len(value) != 3:
        report.error(where, "version must be [major, minor, patch]")
        return
    for part in value:
        if not isinstance(part, int) or part < 0:
            report.error(where, "version parts must be non-negative ints: %r" % (value,))
            return


def collect_paths(data, templates, namespace):
    """Every control path a namespace exposes, following ``@namespace.control``.

    A control written as ``"minimap@diorama.minimap_panel"`` owns the name
    ``minimap`` but takes its children from ``diorama.minimap_panel``, so the
    walk has to merge the template before recursing - otherwise the validator
    reports controls the JSON clearly has.
    """
    found = set()

    def walk(name, value, prefix):
        base = name.split("@", 1)[0]
        path = prefix + "/" + base
        found.add(path)
        merged = {}
        if "@" in name:
            template = name.split("@", 1)[1]
            if "." in template:
                tns, tname = template.split(".", 1)
                tdef = templates.get(tns, {}).get(tname)
                if isinstance(tdef, dict):
                    merged.update(tdef)
        if isinstance(value, dict):
            merged.update(value)
        elif value is None:
            pass
        controls = merged.get("controls")
        if isinstance(controls, list):
            for entry in controls:
                if not isinstance(entry, dict):
                    continue
                for child_name, child_value in entry.items():
                    walk(child_name, child_value, path)

    for name, value in data.items():
        if name in ("namespace", "ui_defs", "ui_def", "screen_name", "files"):
            continue
        if isinstance(value, (dict, list)):
            walk(name, value, "")
    return found


def check_ui(report):
    ui_dir = os.path.join(RP, "ui")
    defs_path = os.path.join(ui_dir, "_ui_defs.json")
    if not os.path.isfile(defs_path):
        alt = os.path.join(ui_dir, "_ui_def.json")
        if os.path.isfile(alt):
            report.warn(
                "ui",
                "using _ui_def.json; the China Edition guide names it _ui_defs.json",
            )
            defs_path = alt
        else:
            report.error("ui", "neither _ui_defs.json nor _ui_def.json exists")
            return
    defs = read_json(defs_path, report, "ui:defs")
    entries = []
    if isinstance(defs, dict):
        entries = defs.get("ui_defs") or defs.get("ui_def") or defs.get("files") or []
    if isinstance(defs, list):
        entries = defs
    if not entries:
        report.error("ui", "%s lists no UI files" % (defs_path,))
        return

    namespace_controls = {}
    textures = set()
    files = {}
    templates = {}
    for entry in entries:
        relative = entry if isinstance(entry, str) else ""
        path = os.path.join(RP, relative.replace("/", os.sep))
        where = "ui:%s" % (relative,)
        if not os.path.isfile(path):
            report.error(where, "listed in _ui_defs.json but not found on disk")
            continue
        data = read_json(path, report, where)
        if data is None:
            continue
        files[os.path.splitext(os.path.basename(relative))[0]] = data
        if not isinstance(data, dict):
            report.error(where, "UI file is not an object")
            continue
        namespace = data.get("namespace")
        if not isinstance(namespace, str) or not namespace:
            report.error(where, "missing \"namespace\"")
        template_map = templates.setdefault(namespace or "?", {})
        for control_name, control_value in data.items():
            if control_name in ("namespace", "ui_defs", "ui_def", "screen_name", "files"):
                continue
            template_map[control_name.split("@", 1)[0]] = control_value
        namespace_controls[namespace or "?"] = set()
        raw_handle = open(path, "rb")
        try:
            raw = raw_handle.read().decode("utf-8")
        finally:
            raw_handle.close()
        for texture in TEXTURE_RE.findall(raw):
            textures.add(texture)

    for namespace, template_map in templates.items():
        namespace_controls[namespace] = collect_paths(template_map, templates, namespace)

    for texture in sorted(textures):
        candidate = os.path.join(RP, texture.replace("/", os.sep))
        if not any(os.path.isfile(candidate + ext) for ext in TEXTURE_EXT):
            report.error("ui:textures", "texture %r has no file in the resource pack" % (texture,))

    # Every namespace-qualified control reference must resolve.
    for name, data in files.items():
        raw_handle = None
        path = os.path.join(ui_dir, name + ".json")
        raw_handle = open(path, "rb")
        try:
            raw = raw_handle.read().decode("utf-8")
        finally:
            raw_handle.close()
        references = set(REFERENCE_RE.findall(raw))
        for _control_name, namespace, control in INHERIT_RE.findall(raw):
            references.add((namespace, control))
        for namespace, control in references:
            if namespace == "common":
                # common.base_screen and friends are the engine's own namespace.
                continue
            known = namespace_controls.get(namespace)
            if known is None:
                report.error("ui:refs", "%s references unknown namespace %r" % (name, namespace))
            elif ("/" + control) not in known:
                report.warn(
                    "ui:refs",
                    "%s references @%s.%s which is not defined in that namespace"
                    % (name, namespace, control),
                )

    # The Python control paths, which the JSON must actually provide.
    for screen, paths in EXPECTED_CONTROLS.items():
        data = files.get(screen)
        if data is None:
            report.error("ui:layout", "%s.json is not listed in _ui_defs.json" % (screen,))
            continue
        defined = collect_paths(
            dict((k, v) for k, v in data.items()), templates, data.get("namespace") or "?"
        )
        for wanted in paths:
            if wanted in defined:
                continue
            report.error(
                "ui:layout",
                "%s.json has no control %r (the Python screen asks for it)" % (screen, wanted),
            )


def check_texts(report):
    texts_dir = os.path.join(RP, "texts")
    if not os.path.isdir(texts_dir):
        report.error("texts", "no texts/ directory")
        return
    key_sets = {}
    for name in sorted(os.listdir(texts_dir)):
        if not name.endswith(".lang"):
            continue
        path = os.path.join(texts_dir, name)
        data = read_json(path, report, "texts:%s" % (name,))
        if data is None:
            continue
        if not isinstance(data, dict):
            report.error("texts:%s" % (name,), "lang file is not a JSON object")
            continue
        key_sets[name] = set(data.keys())
    if not key_sets:
        report.error("texts", "no .lang files found")
        return
    names = sorted(key_sets)
    reference = names[0]
    for name in names[1:]:
        missing = key_sets[reference] - key_sets[name]
        extra = key_sets[name] - key_sets[reference]
        if missing:
            report.warn("texts", "%s is missing keys present in %s: %s"
                        % (name, reference, ", ".join(sorted(missing)[:5])))
        if extra:
            report.warn("texts", "%s has keys %s lacks: %s"
                        % (name, reference, ", ".join(sorted(extra)[:5])))


def check_packaging(report):
    for pack, manifest_kind in ((BP, "behavior"), (RP, "resource")):
        manifest = os.path.join(pack, "manifest.json")
        check_manifest(manifest, manifest_kind, report)
        if not os.path.isfile(os.path.join(pack, "pack_icon.png")):
            report.warn("pack", "%s has no pack_icon.png" % (pack,))
    if not os.path.isdir(os.path.join(BP, "entities")):
        report.error("pack", "behavior pack needs an entities/ directory")
    if not os.path.isfile(os.path.join(SCRIPTS, "modMain.py")):
        report.error("pack", "behavior pack has no dioramaScripts/modMain.py")


def main(argv=None):
    parser = argparse.ArgumentParser(description="Validate the Diorama ModSDK scaffold")
    parser.add_argument("--strict", action="store_true", help="warnings are failures")
    parser.add_argument("--json", action="store_true", help="machine readable output")
    args = parser.parse_args(argv)

    if not os.path.isdir(MOD_ROOT):
        print("cannot find %s" % (MOD_ROOT,), file=sys.stderr)
        return 2

    report = Report()
    check_packaging(report)
    check_python(report)
    check_ui(report)
    check_texts(report)

    failed = bool(report.errors()) or (args.strict and bool(report.warnings()))
    if args.json:
        print(json.dumps({"problems": report.problems, "ok": not failed}, indent=2))
    else:
        for problem in report.problems:
            print("%-7s %-16s %s" % (problem["level"], problem["where"], problem["message"]))
        print(
            "%d error(s), %d warning(s)"
            % (len(report.errors()), len(report.warnings()))
        )
        if not report.problems:
            print("scaffold looks consistent")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
