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

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

import yaml

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
                    dds.append({"name": "TASKLIB", "dsn": f"${{{self.sysvar(d)}}}", "options": "shr"})
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
            for k, v in t.variables.items():
                if k in variables and variables[k] != v:
                    self.note(ld.name, f"variable {k}", f"conflicting values; keeping {variables[k]!r}")
                variables.setdefault(k, v)

        task: dict = {"language": ld.name}
        sources = self.sources_map.get(ld.name) or self.sources_map.get(ld.language_code)
        if sources:
            task["sources"] = sources
        else:
            self.note(ld.name, "sources", "no file patterns known; add via --sources-map")
        if variables:
            task["variables"] = [{"name": k, "value": v} for k, v in variables.items()]
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
            self.note(ld.name, "copySrc", "dependencyCopy (copybook/include search) not generated")
        task["steps"] = steps
        return task

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


def emit_all(sd: SystemDefinition, sources_map: dict[str, list[str]] | None = None) -> Output:
    conv = _Converter(sd, sources_map)
    out = Output()
    tasks: list[str] = []
    for ld in sd.langdefs.values():
        fname = f"{slug(ld.name)}.yaml"
        if fname in out.files:
            fname = f"{slug(ld.name)}_{len(out.files)}.yaml"
        out.files[fname] = _dump({"version": SCHEMA_VERSION, "tasks": [conv.language(ld)]})
        tasks.append(ld.name)
    out.files["Languages.yaml"] = _dump({
        "version": SCHEMA_VERSION,
        "include": [{"file": f} for f in out.files],
        "tasks": [{"stage": "Languages", "tasks": tasks}],
        "variables": [{"name": k, "value": v} for k, v in conv.sysvar_values.items()],
    })
    out.files = {"Languages.yaml": out.files.pop("Languages.yaml"), **out.files}
    out.notes = conv.notes
    out.properties = list(conv.properties)
    return out
