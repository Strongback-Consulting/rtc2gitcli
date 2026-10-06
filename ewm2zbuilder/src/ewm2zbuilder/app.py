"""Application configuration (dbb-app.yaml) from the z/OS metadata of a migrated repository.

Input:
  * `.ewm/zos-metadata.json`, written by `scm migrate-to-git` into the
    application repository (language definition, per-file build variables and
    flags of every member; data set definition of every zFolder), and
  * `language-map.yaml` from the system definition conversion (language
    definition -> zBuilder task + the variables that select its variant).

Output: one language task entry per zBuilder task the application uses, with
  * `sources`: a zFolder glob when every member of the folder belongs to the
    task, otherwise the member paths (a member whose language definition maps to
    another task than its neighbours is thereby overridden per file),
  * variant variables (`IS_CICS`, `IS_SQL`) for the members that need them, and
  * the members' file-level build variable overrides,
both as `forFiles` variables.
"""

from __future__ import annotations

import posixpath
from collections import defaultdict
from dataclasses import dataclass, field

DEFAULT_RENAMES = (("zOSsrc", "src"),)


@dataclass
class AppConfig:
    document: dict
    notes: list[str] = field(default_factory=list)


def rename(path: str, renames) -> str:
    """Apply the layout's folder renames to every path segment (zOSsrc -> src)."""
    table = dict(renames)
    return "/".join(table.get(segment, segment) for segment in path.split("/"))


def build(metadata: dict, language_map: dict, version: str, renames=DEFAULT_RENAMES,
          prefix: str = "**/") -> AppConfig:
    notes: list[str] = []
    members = {rename(path, renames): info for path, info in metadata.get("members", {}).items()}
    by_folder: dict[str, list[str]] = defaultdict(list)
    for path in members:
        by_folder[posixpath.dirname(path)].append(path)

    task_of: dict[str, str] = {}
    variant_of: dict[str, dict] = {}
    for path, info in sorted(members.items()):
        langdef = (info.get("languageDefinition") or {}).get("name")
        if not langdef:
            reference = info.get("languageDefinition")
            if reference and reference.get("uuid"):
                notes.append(f"{path}: language definition {reference['uuid']} could not be resolved; not built")
            elif not info.get("alwaysLoad"):
                notes.append(f"{path}: no language definition; not built")
            continue
        entry = language_map.get(langdef)
        if entry is None:
            notes.append(f"{path}: language definition '{langdef}' is not in the language map; "
                         "used as task name")
            entry = {"task": langdef, "variables": {}}
        task_of[path] = entry["task"]
        variant_of[path] = entry.get("variables") or {}

    def patterns(paths: list[str], task: str) -> list[str]:
        """Folder globs where the whole folder belongs to `task`, member paths elsewhere."""
        result = []
        chosen = set(paths)
        for folder in sorted({posixpath.dirname(p) for p in paths}):
            in_folder = by_folder[folder]
            if folder and all(p in chosen and task_of.get(p) == task for p in in_folder):
                result.append(f"{prefix}{folder}/*")
            else:
                result += [f"{prefix}{p}" for p in sorted(p for p in in_folder if p in chosen)]
        return result

    tasks = []
    for task in sorted(set(task_of.values())):
        files = sorted(p for p, t in task_of.items() if t == task)
        entry: dict = {"language": task, "sources": patterns(files, task)}
        variables = []
        # variant variables: the task's default is false, so only the members that need true
        names = sorted({n for p in files for n in variant_of[p]})
        for name in names:
            on = [p for p in files if variant_of[p].get(name)]
            if on:
                variables.append({"name": name, "value": True, "forFiles": patterns(on, task)})
        # file-level overrides of translator variables, one entry per variable and value
        overrides: dict[tuple[str, str], list[str]] = defaultdict(list)
        for p in files:
            for name, value in (members[p].get("buildVariables") or {}).items():
                overrides[(name, value)].append(p)
        for (name, value), paths in sorted(overrides.items()):
            known = (language_map_task_variables(language_map, task))
            if known is not None and name not in known:
                notes.append(f"{task}: file-level variable {name} is not a variable of the task "
                             f"(overridden in {len(paths)} file(s))")
            variables.append({"name": name, "value": value, "forFiles": [f"{prefix}{p}" for p in paths]})
        if variables:
            entry["variables"] = variables
        tasks.append(entry)

    for path, info in sorted(members.items()):
        if info.get("mvsCodePage"):
            notes.append(f"{path}: code page {info['mvsCodePage']} (already in .gitattributes)")
    return AppConfig({"version": version, "tasks": tasks}, notes)


def language_map_task_variables(language_map: dict, task: str) -> set[str] | None:
    """The variables of a task, if the language map records them."""
    for entry in language_map.values():
        if entry.get("task") == task and "taskVariables" in entry:
            return set(entry["taskVariables"])
    return None
