"""Emit zBuilder YAML (schema v1.0.3) from a SystemDefinition.

Output mirrors the sample layout: `Languages.yaml` (includes, a `Languages`
stage and global variables for system libraries) plus one language-task file
per EWM language definition.

Mapping decisions (EWM -> zBuilder):
  * usage 0/1 datasets (non-VIO)  -> `${HLQ}.<dsName>` plus a `datasets` entry
  * usage 3 datasets              -> global variable in Languages.yaml, DD uses `${VAR}`
  * usage 2 / VIO datasets        -> temp DD (no `dsn`, BPXWDYN `new` options)
  * program library of a translator -> TASKLIB DD (as in the IBM samples)
  * allocation `propertyName`     -> `${PROP}` guarded by `condition: {exists: PROP}`
  * `input` DD with no dataset    -> the source member; a `copySrc` step is added
  * `keep` (temp DD) -> `pass`;  `publish` -> `log`/`logEncoding`
  * `isset`/`not` conditions      -> `exists` / `notExists`
  * `commandMember` translators   -> `tso` step (flagged for review)
Not generated (flagged in the report): `sources` unless a sources map is given,
`dependencyCopy`, scanners/dependency types, `deployType`, `usedAsInput`,
`outputName`.
"""

from __future__ import annotations

import json
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

import yaml

from .variants import FEATURE_DEFAULTS, Family, condition_for, find_families, merge_sequences, program_key
from .model import (
    CALL_COMMAND,
    USAGE_TEMP,
    USAGE_EXISTING,
    Allocation,
    Concatenation,
    DataSetDef,
    LangDef,
    SystemDefinition,
    Translator,
)

SCHEMA_VERSION = "1.0.3"
LOG_PATH = "${LOGS}/${STEP}-${FILE_NAME}.log"

# Dataset that holds the source member being built, by EWM language code.
SOURCE_DATASET = {
    "COB": "COBOL", "ASM": "ASM", "PLI": "PLI", "C": "C", "JCL": "JCL", "LNK": "LINKCNTL",
}
DEFAULT_SOURCE_DATASET = "SOURCE"

TEXT_SUBSTITUTIONS = {
    "${team.enterprise.scm.resourcePrefix}": "${HLQ}",
    "@{source.member.name}": "${MEMBER}",
    "@{source.member}": "${MEMBER}",  # (assumed) member name of the file being built
}


@dataclass
class Note:
    """Something that could not be mapped faithfully and needs a human."""

    langdef: str
    where: str
    message: str

    def __str__(self) -> str:
        return f"[{self.langdef}] {self.where}: {self.message}"


@dataclass
class Output:
    files: dict[str, str] = field(default_factory=dict)  # filename -> YAML text
    notes: list[Note] = field(default_factory=list)
    properties: list[str] = field(default_factory=list)  # build properties to define
    # langdef name -> {"task": zBuilder task, "variables": {name: value}}; the contract with `ewm2zbuilder app`
    language_map: dict[str, dict] = field(default_factory=dict)
    families: list[Family] = field(default_factory=list)


def slug(name: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "_", name).strip("_") or "unnamed"


def condition_obj(fragment: str):
    """EWM <isset>/<not> fragment -> zBuilder condition; None if unsupported, "" if none."""
    if not fragment.strip():
        return ""
    try:
        root = ET.fromstring(f"<r>{fragment}</r>")
    except ET.ParseError:
        return None
    kids = list(root)
    if len(kids) != 1:
        return None
    el = kids[0]
    if el.tag == "isset" and el.get("property"):
        return {"exists": el.get("property")}
    if el.tag == "not" and len(el) == 1 and el[0].tag == "isset" and el[0].get("property"):
        return {"notExists": el[0].get("property")}
    return None


def _add_program_library(dds: list[dict], library: dict) -> None:
    """The translator's program library goes first in the task library: as its own TASKLIB DD, or, when the
    translator allocates TASKLIB/STEPLIB itself, as the first entry of that concatenation (one DD per name)."""
    index = next((i for i, dd in enumerate(dds) if dd.get("name") in ("TASKLIB", "STEPLIB")), None)
    if index is None:
        dds.insert(0, library)
        return
    end = index + 1
    while end < len(dds) and "name" not in dds[end]:
        end += 1
    if any(dd.get("dsn") == library["dsn"] for dd in dds[index:end]):
        return
    head = dds[index]
    dds[index] = {k: v for k, v in head.items() if k != "name"}
    dds.insert(index, {**library, "name": head["name"]})


def _same_step(a, b) -> bool:
    """Same program, settings and EWM condition; options and DDs may differ."""
    def bare(step: dict) -> dict:
        return {k: v for k, v in step.items() if k not in ("step", "parm", "dds")}
    return bare(a[1]) == bare(b[1]) and a[2] == b[2]


def combine_conditions(feature: str | None, ewm):
    """A CICS/SQL condition (JEXL) and an EWM condition (exists/notExists) on the same step."""
    if not feature:
        return ewm or None
    if not ewm:
        return feature
    return {**ewm, "eval": feature}


def _space_options(d: DataSetDef) -> list[str]:
    opts = []
    if d.space_units and d.primary is not None:
        unit = {"trks": "tracks", "cyls": "cyl"}.get(d.space_units, d.space_units)
        opts.append(f"{unit} space({d.primary},{d.secondary or 0})")
    if d.record_format:
        opts.append(f"recfm({','.join(d.record_format.lower())})")
    if d.record_length:
        opts.append(f"lrecl({d.record_length})")
    if d.block_size:
        opts.append(f"blksize({d.block_size})")
    return opts


def _is_temp(d: DataSetDef) -> bool:
    return d.usage_type == USAGE_TEMP or (d.generic_unit or "").upper() == "VIO"


class _Converter:
    def __init__(self, sd: SystemDefinition, sources_map: dict[str, list[str]] | None):
        self.sd = sd
        self.sources_map = sources_map or {}
        self.notes: list[Note] = []
        self.sysvars: dict[str, str] = {}  # usage-3 dsdef name -> variable name
        self.sysvar_values: dict[str, str] = {}  # variable name -> dsn
        self.properties: dict[str, None] = {}

    # -- helpers --------------------------------------------------------
    def note(self, ld: str, where: str, msg: str) -> None:
        self.notes.append(Note(ld, where, msg))

    def sysvar(self, d: DataSetDef) -> str:
        if d.name not in self.sysvars:
            base = slug(d.name).upper()
            name, n = base, 2
            while name in self.sysvar_values:
                name, n = f"{base}_{n}", n + 1
            self.sysvars[d.name] = name
            self.sysvar_values[name] = d.ds_name
        return self.sysvars[d.name]

    def text(self, s: str, ld: str, where: str, variables: dict[str, str] | None = None) -> str:
        for old, new in TEXT_SUBSTITUTIONS.items():
            s = s.replace(old, new)
        # EWM option strings reference translator variables as &NAME.
        for var in variables or ():
            s = re.sub(rf"&{re.escape(var)}(?![A-Za-z0-9_])", f"${{{var}}}", s)
        left = sorted(set(re.findall(r"@\{[^}]*\}|#\*|\$\{team\.[^}]*\}", s)))
        if left:
            self.note(ld, where, f"untranslated EWM token(s) {left}")
        return s

    # -- DDs ------------------------------------------------------------
    def dd(self, a: Allocation, ld: LangDef, where: str, datasets: dict) -> dict:
        e: dict = {}
        if a.dd_name:
            e["name"] = a.dd_name
        if a.property_name:
            self.properties.setdefault(a.property_name)
            e["dsn"] = f"${{{a.property_name}}}"
            e["condition"] = {"exists": a.property_name}
            e["options"] = "shr"
        elif a.ds_def:
            d = self.sd.dsdefs.get(a.ds_def)
            if d is None:
                self.note(ld.name, where, f"unresolved data set definition {a.ds_def!r}")
                e["dsn"] = f"${{TODO_{slug(a.ds_def)}}}"
                e["options"] = "shr"
            elif _is_temp(d):
                e["options"] = " ".join([*_space_options(d), *(["unit(vio)"] if d.generic_unit else []), "new"])
                if a.keep:
                    e["pass"] = True
            elif d.usage_type == USAGE_EXISTING:
                if d.ds_name:
                    dsn = f"${{{self.sysvar(d)}}}"
                    if a.member:
                        dsn += "(${MEMBER})"
                    elif d.member:
                        dsn += f"({d.member})"
                    e["dsn"] = dsn
                else:
                    self.note(ld.name, where, f"data set definition {d.name!r} has no dataset name")
                e["options"] = "shr"
            else:
                base = f"${{HLQ}}.{d.ds_name}" if d.prefix_dsn else d.ds_name
                datasets.setdefault(base, (d, a.member))
                e["dsn"] = f"{base}(${{MEMBER}})" if a.member else base
                e["options"] = "shr"
        elif a.input:
            src = SOURCE_DATASET.get(ld.language_code, DEFAULT_SOURCE_DATASET)
            base = f"${{HLQ}}.{src}"
            datasets.setdefault(base, (None, True))
            e["dsn"] = f"{base}(${{MEMBER}})"
            e["options"] = "shr"
        else:
            self.note(ld.name, where, "allocation has no dataset or property reference")
        if a.input:
            e["input"] = True
        if a.output:
            e["output"] = True
        if a.publish:
            e["log"] = LOG_PATH
            e["logEncoding"] = "${LOG_ENCODING}"
        if a.keep and "pass" not in e:
            self.note(ld.name, where, "`keep` on a non-temporary DD is not mapped")
        if a.used_as_input:
            self.note(ld.name, where, "`usedAsInput` not mapped")
        if a.output_name or a.output_name_kind:
            self.note(ld.name, where, "`outputName`/`outputNameKind` not mapped")
        return e

    def step(self, t: Translator, cond, ld: LangDef, datasets: dict) -> dict:
        where = f"translator {t.name!r}"
        step: dict = {"step": t.name}
        d = self.sd.dsdefs.get(t.ds_def) if t.ds_def else None
        dds: list[dict] = []
        program_library = None
        if t.call_method == CALL_COMMAND:
            step["type"] = "tso"
            step["command"] = self.text(t.command_member or "", ld.name, where)
            self.note(ld.name, where, "REXX/TSO command step needs manual review")
        else:
            step["type"] = "mvs"
            if d is None:
                self.note(ld.name, where, "program data set definition unresolved")
                step["pgm"] = "TODO"
            else:
                step["pgm"] = d.member or d.ds_name
                if d.ds_name and d.member:  # program lives in a specific library
                    program_library = {"name": "TASKLIB", "dsn": f"${{{self.sysvar(d)}}}", "options": "shr"}
            if t.default_options:
                step["parm"] = self.text(t.default_options, ld.name, where, t.variables)
        if cond:
            step["condition"] = cond
        step["maxRC"] = t.max_rc
        if t.ddname_list:
            step["ddnameSubstitution"] = ",".join(t.ddname_list)
        for item in t.dds:
            if isinstance(item, Concatenation):
                for i, a in enumerate(item.allocations):
                    e = self.dd(a, ld, f"{where} DD {item.dd_name}", datasets)
                    # Only the first entry carries the name; the rest concatenate to it.
                    e.pop("name", None)
                    dds.append({"name": item.dd_name, **e} if i == 0 else e)
            else:
                dds.append(self.dd(item, ld, f"{where} DD {item.dd_name}", datasets))
        if program_library:
            _add_program_library(dds, program_library)
        if dds:
            step["dds"] = [FlowDict(d) for d in dds]
        return step

    # -- language task --------------------------------------------------
    def language(self, ld: LangDef) -> dict:
        datasets: dict[str, tuple[DataSetDef | None, bool]] = {}
        steps: list[dict] = []
        variables: dict[str, str] = {}
        for i, name in enumerate(ld.translators):
            t = self.sd.translators.get(name)
            if t is None:
                self.note(ld.name, f"translator {name!r}", "not defined in export; skipped")
                continue
            cond = None
            if i < len(ld.conditions):
                cond = condition_obj(ld.conditions[i])
                if cond is None:
                    self.note(ld.name, f"translator {name!r}", f"unsupported condition {ld.conditions[i]!r}")
            steps.append(self.step(t, cond, ld, datasets))
            self.merge_variables(variables, t, ld.name)
        return self.task(ld.name, ld, [ld.name, ld.language_code], steps, variables, datasets)

    def merge_variables(self, variables: dict, t: Translator, ld_name: str) -> None:
        for k, v in t.variables.items():
            if k in variables and variables[k] != v:
                self.note(ld_name, f"variable {k}", f"conflicting values; keeping {variables[k]!r}")
            variables.setdefault(k, v)

    def task(self, name: str, ld: LangDef, source_keys: list[str], steps: list[dict], variables: dict,
             datasets: dict, extra_variables: list[dict] | None = None) -> dict:
        task: dict = {"language": name}
        sources = next((self.sources_map[k] for k in source_keys if self.sources_map.get(k)), None)
        if sources:
            task["sources"] = sources
        else:
            self.note(name, "sources", "no file patterns known; add via --sources-map")
        all_variables = [*(extra_variables or []), *({"name": k, "value": v} for k, v in variables.items())]
        if all_variables:
            task["variables"] = all_variables
        if datasets:
            task["datasets"] = [
                {"name": n, "options": self.create_options(d, member)} for n, (d, member) in datasets.items()
            ]
        src_ds = f"${{HLQ}}.{SOURCE_DATASET.get(ld.language_code, DEFAULT_SOURCE_DATASET)}"
        if src_ds in datasets and datasets[src_ds][0] is None:
            steps.insert(0, {
                "step": "copySrc", "type": "copy", "source": "${FILE_PATH}",
                "target": f"//'{src_ds}(${{MEMBER}})'",
            })
            self.note(name, "copySrc", "dependencyCopy (copybook/include search) not generated")
        task["steps"] = steps
        return task

    # -- variant families ----------------------------------------------
    def family(self, fam: Family) -> dict:
        """One task for all variants: steps not shared by every variant get a CICS/SQL condition."""
        sd = self.sd
        datasets: dict[str, tuple[DataSetDef | None, bool]] = {}
        variables: dict[str, str] = {}
        selects: list[dict] = []
        steps: list[dict] = []
        used: set[str] = set()
        sequences = {}
        for m in fam.members:
            ld = sd.langdefs[m]
            sequences[m] = [
                (program_key(sd, n), " ".join((ld.conditions[i] if i < len(ld.conditions) else "").split()))
                for i, n in enumerate(ld.translators)
            ]
        for slot in merge_sequences(sequences):
            groups: dict[str, list[str]] = {}
            for m, index in slot.items():
                groups.setdefault(sd.langdefs[m].translators[index], []).append(m)
            built = []
            for tname, members in groups.items():
                ld = sd.langdefs[members[0]]
                index = slot[members[0]]
                t = sd.translators.get(tname)
                if t is None:
                    self.note(fam.task, f"translator {tname!r}", "not defined in export; skipped")
                    continue
                ewm = condition_obj(ld.conditions[index]) if index < len(ld.conditions) else ""
                if ewm is None:
                    self.note(fam.task, f"translator {tname!r}", f"unsupported condition {ld.conditions[index]!r}")
                built.append((members, self.step(t, None, ld, datasets), ewm))
                self.merge_variables(variables, t, fam.task)
            if not built:
                continue
            present = {fam.values(m) for m in slot}

            if len(built) > 1 and all(_same_step(b, built[0]) for b in built):
                built = [(list(slot), self.merge_steps(built, fam, selects), built[0][2])]
            elif len(built) > 1:
                self.note(fam.task, f"steps {[b[1]['step'] for b in built]}",
                          "variants run different programs or settings here; emitted one step per variant")
            for members, step, ewm in built:
                feature = condition_for({fam.values(m) for m in members}, fam) if len(built) > 1 \
                    else condition_for(present, fam)
                steps.append(self.place(step, combine_conditions(feature, ewm), used))

        base = sd.langdefs[fam.task]
        flags = [{"name": f, "value": FEATURE_DEFAULTS[f]} for f in fam.features]
        return self.task(fam.task, base, [fam.task, *fam.members, base.language_code], steps, variables,
                         datasets, flags + selects)

    @staticmethod
    def merge_steps(built: list, fam: Family, selects: list[dict]) -> dict:
        """Variants run the same program here: one step, their options chosen per variant (`select`) and
        their DDs aligned, each DD that not every variant has getting the condition of those that do."""
        primary = next((b for b in built if fam.task in b[0]), built[0])
        step = dict(primary[1])
        # one select entry per distinct value, for all the variants that use it
        by_value: dict[str, set] = {}
        for members, s, _ in built:
            by_value.setdefault(s.get("parm") or "", set()).update(fam.values(m) for m in members)
        parms = [(condition_for(combos, fam), value) for value, combos in by_value.items()]
        if len(parms) > 1:
            var = re.sub(r"[^A-Za-z0-9]+", "_", step["step"]).strip("_").upper() + "_PARMS"
            step["parm"] = f"${{{var}}}"
            selects.append({"name": var, "select": [{"condition": c, "value": p or ""} for c, p in parms]})
        if any(b[1].get("dds") for b in built):
            sequences = {i: [json.dumps(dd, sort_keys=True) for dd in b[1].get("dds", [])]
                         for i, b in enumerate(built)}
            dds = []
            for slot in merge_sequences(sequences):
                i, j = next(iter(slot.items()))
                dd = FlowDict(built[i][1]["dds"][j])
                if len(slot) < len(built):
                    members = [m for k in slot for m in built[k][0]]
                    cond = combine_conditions(condition_for({fam.values(m) for m in members}, fam),
                                              dd.pop("condition", None))
                    dd["condition"] = cond
                dds.append(dd)
            step["dds"] = dds
        return step

    @staticmethod
    def place(step: dict, cond, used: set[str]) -> dict:
        """Unique step name; the condition goes before maxRC, where single-variant tasks have it."""
        name, n = step["step"], 2
        while name in used:
            name, n = f"{step['step']}_{n}", n + 1
        used.add(name)
        placed: dict = {}
        for k, v in step.items():
            if k == "condition":
                continue
            if k == "maxRC" and cond:
                placed["condition"] = cond
            placed[k] = name if k == "step" else v
        if cond and "condition" not in placed:
            placed["condition"] = cond
        return placed

    @staticmethod
    def create_options(d: DataSetDef | None, member: bool) -> str:
        if d is None:  # source dataset: sensible default
            return "cyl space(10,10) lrecl(80) dsorg(PO) recfm(F,B) dsntype(library)"
        opts = _space_options(d) or ["cyl space(5,5)"]
        pds = member or d.directory_blocks is not None
        opts.append("dsorg(PO) dsntype(library)" if pds else "dsorg(PS)")
        return " ".join(opts)


class FlowDict(dict):
    """Dumped as a one-line flow mapping: `{ name: SYSIN, dsn: ..., options: shr }`."""


yaml.SafeDumper.add_representer(
    FlowDict, lambda dumper, d: dumper.represent_mapping("tag:yaml.org,2002:map", d, flow_style=True)
)


DD_PREFIX = "    - {"


def _align_dds(text: str) -> str:
    """Pad unnamed (concatenated) DD lines so `dsn:` lines up with the `dsn:` above."""
    out, col, inside = [], None, False
    for line in text.split("\n"):
        if line == "    dds:":
            inside, col = True, None
        elif inside and not line.startswith("    - "):
            inside = False
        elif inside and line.startswith(DD_PREFIX + "dsn: ") and col:
            line = DD_PREFIX + " " * (col - len(DD_PREFIX)) + line[len(DD_PREFIX):]
        if inside and line.startswith(DD_PREFIX):
            i = line.find("dsn: ")
            if i >= 0 and (line[i - 1] in " {"):
                col = i
        out.append(line)
    return "\n".join(out)


def _dump(doc: dict) -> str:
    # Huge width so long DD lines are never wrapped.
    text = yaml.safe_dump(doc, sort_keys=False, width=100_000, default_flow_style=False)
    return _align_dds(text)


def emit_all(sd: SystemDefinition, sources_map: dict[str, list[str]] | None = None,
             variants: bool = True) -> Output:
    conv = _Converter(sd, sources_map)
    out = Output()
    families, family_notes = find_families(sd) if variants else ([], [])
    for message in family_notes:
        conv.note("variants", "grouping", message)
    member_of = {m: fam for fam in families for m in fam.members}
    tasks: list[str] = []
    task_variables: dict[str, list[str]] = {}
    for ld in sd.langdefs.values():
        fam = member_of.get(ld.name)
        task_name = fam.task if fam else ld.name
        out.language_map[ld.name] = {
            "task": task_name,
            "variables": dict(fam.members[ld.name]) if fam else {},
        }
        if task_name in tasks:
            continue
        task = conv.family(fam) if fam else conv.language(ld)
        task_variables[task_name] = sorted(v["name"] for v in task.get("variables", []))
        fname = f"{slug(task_name)}.yaml"
        if fname in out.files:
            fname = f"{slug(task_name)}_{len(out.files)}.yaml"
        header = ""
        if fam:
            header = "# One task for these EWM language definitions (variants of one compiler):\n" + "".join(
                f"#   {m}: {', '.join(f'{k}={str(v).lower()}' for k, v in vals.items())}\n"
                for m, vals in fam.members.items())
        out.files[fname] = header + _dump({"version": SCHEMA_VERSION, "tasks": [task]})
        tasks.append(task_name)
    out.files["Languages.yaml"] = _dump({
        "version": SCHEMA_VERSION,
        "include": [{"file": f} for f in out.files],
        "tasks": [{"stage": "Languages", "tasks": tasks}],
        "variables": [{"name": k, "value": v} for k, v in conv.sysvar_values.items()],
    })
    out.files = {"Languages.yaml": out.files.pop("Languages.yaml"), **out.files}
    for entry in out.language_map.values():
        entry["taskVariables"] = task_variables[entry["task"]]
    out.notes = conv.notes
    out.properties = list(conv.properties)
    out.families = families
    return out
