"""Emit zBuilder YAML (validated against buildConfigurationSchema*.json) from a SystemDefinition.

Output mirrors the sample layout: `Languages.yaml` (includes, a `Languages`
stage and global variables for system libraries) plus one language-task file
per EWM language definition, except that variants of one compiler are folded
into one task: the families `variants.py` detects, plus or overridden by the
folds of a fold map. `consolidate.py` merges the folded steps and verifies the
result; `Output.language_map` records which task and variant values every
language definition ended up with (the contract with `app.py`).

Mapping decisions (EWM -> zBuilder):
  * usage 0/1 datasets (non-VIO)  -> `${HLQ}.<dsName>` plus a `datasets` entry
  * usage 3 datasets              -> global variable in Languages.yaml, DD uses `${VAR}`
  * usage 2 / VIO datasets        -> temp DD (no `dsn`, BPXWDYN `new` options)
  * program library of a translator -> TASKLIB DD (as in the IBM samples), or the first entry
    of the translator's own TASKLIB/STEPLIB concatenation
  * allocation `propertyName`     -> `${PROP}` guarded by `condition: {exists: PROP}`
  * `input` DD with no dataset    -> the source member; a `copySrc` step is added
  * `keep` (temp DD) -> named temp + `pass`; a later translator using the same temp
    dsdef reads it back (`dsn: &&NAME`, `shr`);  `publish` -> `log`/`logEncoding` (one file per DD)
  * `isset`/`not` conditions      -> `exists` / `notExists`
  * `commandMember` translators   -> `tso` step (flagged for review)
Not generated (flagged in the report): `sources` unless a sources map is given,
`dependencyCopy`, scanners/dependency types, `deployType`, `usedAsInput`,
`outputName`.
"""

from __future__ import annotations

import itertools
import json
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

import yaml

from .consolidate import (
    ConsolidationError,
    Item,
    Space,
    consolidate as _consolidate,
    merge_orders as _merge_orders,
    unify_temp_space as _unify_temp_space,
    verify as _verify,
    with_condition as _with_condition,
)
from .variants import FEATURE_DEFAULTS, Family, find_families
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
LOG_PATH_NAMED = "${{LOGS}}/${{STEP}}-{dd}-${{FILE_NAME}}.log"  # second and later published DDs of a step

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
    # langdef name -> {"task", "variables", "taskVariables"}; the contract with `ewm2zbuilder app`
    language_map: dict[str, dict] = field(default_factory=dict)
    families: list[Family] = field(default_factory=list)  # folded tasks, detected or from the fold map


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


def _add_program_library(dds: list[dict], library: dict, same_dataset=lambda a, b: a == b) -> None:
    """The translator's program library goes first in the task library: as its own TASKLIB DD, or, when the
    translator allocates TASKLIB/STEPLIB itself and that concatenation does not already hold the library (under
    any data set definition: `same_dataset` compares DSNs), as its first entry (one DD per name)."""
    index = next((i for i, dd in enumerate(dds) if dd.get("name") in ("TASKLIB", "STEPLIB")), None)
    if index is None:
        dds.insert(0, library)
        return
    end = index + 1
    while end < len(dds) and "name" not in dds[end]:
        end += 1
    if any(same_dataset(dd.get("dsn"), library["dsn"]) for dd in dds[index:end]):
        return
    head = dds[index]
    dds[index] = {k: v for k, v in head.items() if k != "name"}
    dds.insert(index, {**library, "name": head["name"]})


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


@dataclass
class _Flow:
    """Temp datasets handed between the translators of one langdef.

    `kept` = temp dsdefs a previous translator marked `keep`; `produced` = the ones this
    translator keeps (added to `kept` once it is finished); `consumed` = the kept ones
    this translator reads.
    """

    kept: set[str] = field(default_factory=set)
    produced: set[str] = field(default_factory=set)
    consumed: set[str] = field(default_factory=set)
    published: int = 0  # DDs of this translator already given a log file


class _Converter:
    def __init__(self, sd: SystemDefinition, sources_map: dict[str, list[str]] | None,
                 consolidate: bool = True):
        self.sd = sd
        self.consolidate = consolidate
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

    def same_dataset(self, a: str | None, b: str | None) -> bool:
        """Whether two DD `dsn`s name the same data set (system library variables resolved)."""
        def resolve(dsn):
            m = re.fullmatch(r"\$\{(\w+)\}", dsn or "")
            return self.sysvar_values.get(m.group(1), dsn) if m else dsn
        return a is not None and resolve(a) == resolve(b)

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
    def dd(self, a: Allocation, ld: LangDef, where: str, datasets: dict, flow: _Flow) -> dict:
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
                tmp = d.ds_name.lstrip("&")  # EWM temp names look like "&&CICPUNCH"
                new_opts = " ".join([*_space_options(d), *(["unit(vio)"] if d.generic_unit else []), "new"])
                if a.keep:  # producer: a later translator reads this dataset (as in the IBM samples)
                    if tmp:
                        e["dsn"] = f"&&{tmp}"
                    e["options"] = new_opts
                    e["pass"] = True
                    flow.produced.add(d.name)
                elif d.name in flow.kept:  # consumer: read what an earlier translator kept
                    if tmp:
                        e["dsn"] = f"&&{tmp}"
                    else:
                        self.note(ld.name, where, f"temp data set {d.name!r} is kept but unnamed; cannot be re-read")
                    e["options"] = "shr"
                    flow.consumed.add(d.name)
                else:
                    e["options"] = new_opts
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
            # Every published DD needs its own file, or the later ones overwrite the earlier. As in the
            # IBM samples the first keeps the plain step log and the others carry their DD name.
            flow.published += 1
            if flow.published == 1:
                e["log"] = LOG_PATH
            else:
                e["log"] = LOG_PATH_NAMED.format(dd=a.dd_name or f"DD{flow.published}")
            e["logEncoding"] = "${LOG_ENCODING}"
        if a.keep and "pass" not in e:
            self.note(ld.name, where, "`keep` on a non-temporary DD is not mapped")
        if a.used_as_input:
            self.note(ld.name, where, "`usedAsInput` not mapped")
        if a.output_name or a.output_name_kind:
            self.note(ld.name, where, "`outputName`/`outputNameKind` not mapped")
        return e

    def step(self, t: Translator, cond, ld: LangDef, datasets: dict, kept: set[str]) -> tuple[dict, frozenset]:
        """Build one step; returns (step, temp dsdefs it reads from earlier steps)."""
        flow = _Flow(kept=kept)
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
                    e = self.dd(a, ld, f"{where} DD {item.dd_name}", datasets, flow)
                    # Only the first entry carries the name; the rest concatenate to it.
                    e.pop("name", None)
                    dds.append({"name": item.dd_name, **e} if i == 0 else e)
            else:
                dds.append(self.dd(item, ld, f"{where} DD {item.dd_name}", datasets, flow))
        if program_library:
            _add_program_library(dds, program_library, self.same_dataset)
        if dds:
            step["dds"] = dds
        kept |= flow.produced
        return step, frozenset(flow.consumed)

    # -- language task --------------------------------------------------
    def _collect(self, ld: LangDef):
        """Per-translator steps (condition not yet applied) for one langdef.

        Returns (items, datasets, variables); each item is (key, step, ewm_cond).
        """
        datasets: dict[str, tuple[DataSetDef | None, bool]] = {}
        items: list[tuple[tuple, dict, object]] = []
        variables: dict[str, str] = {}
        kept: set[str] = set()
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
            raw = ld.conditions[i] if i < len(ld.conditions) else ""
            step, consumed = self.step(t, None, ld, datasets, kept)
            # The same translator reading different kept datasets in two variants is two steps.
            items.append(((name, raw, tuple(sorted(consumed))), step, cond))
            for k, v in t.variables.items():
                if k in variables and variables[k] != v:
                    self.note(ld.name, f"variable {k}", f"conflicting values; keeping {variables[k]!r}")
                variables.setdefault(k, v)
        return items, datasets, variables

    def _task(self, name: str, code: str, steps: list[dict], datasets: dict, variables: dict,
              extra_variables: list[dict] | None = None) -> dict:
        task: dict = {"language": name}
        sources = self.sources_map.get(name) or self.sources_map.get(code)
        if sources:
            task["sources"] = sources
        else:
            self.note(name, "sources", "no file patterns known; add via --sources-map")
        if variables or extra_variables:
            task["variables"] = [{"name": k, "value": v} for k, v in variables.items()] + (extra_variables or [])
        if datasets:
            task["datasets"] = [
                {"name": n, "options": self.create_options(d, member)} for n, (d, member) in datasets.items()
            ]
        src_ds = f"${{HLQ}}.{SOURCE_DATASET.get(code, DEFAULT_SOURCE_DATASET)}"
        if src_ds in datasets and datasets[src_ds][0] is None:
            steps.insert(0, {
                "step": "copySrc", "type": "copy", "source": "${FILE_PATH}",
                "target": f"//'{src_ds}(${{MEMBER}})'",
            })
            self.note(name, "copySrc", "dependencyCopy (copybook/include search) not generated")
        for st in steps:
            if "dds" in st:
                st["dds"] = [FlowDict(d) for d in st["dds"]]
        task["steps"] = steps
        return task

    def language(self, ld: LangDef) -> dict:
        items, datasets, variables = self._collect(ld)
        steps = [_with_condition(step, cond) for _, step, cond in items]
        return self._task(ld.name, ld.language_code, steps, datasets, variables)

    def fold(self, target: str, variants: dict[str, dict[str, bool]], unify: list[str] | None = None) -> dict:
        """Merge several langdefs into one task, guarding each step by variant flags.

        `variants` maps langdef name -> {flag variable: bool}. A step present in
        several variants is emitted once with the simplified union of their flags.
        `unify` lists DD-name globs whose temp work files get one (the largest) space
        across the variants (see consolidate.unify_temp_space).
        """
        flags = list(next(iter(variants.values())))
        lds = [self.sd.langdefs[n] for n in variants]
        collected = [self._collect(ld) for ld in lds]
        info: dict[tuple, tuple[dict, object]] = {}
        used_by: dict[tuple, list[tuple[bool, ...]]] = {}
        seqs: list[list[tuple]] = []
        datasets: dict = {}
        variables: dict[str, str] = {}
        for (name, assign), (items, ds, vs) in zip(variants.items(), collected):
            seq = []
            for key, step, cond in items:
                info.setdefault(key, (step, cond))
                used_by.setdefault(key, []).append(tuple(assign[f] for f in flags))
                seq.append(key)
            seqs.append(seq)
            for k, v in ds.items():
                datasets.setdefault(k, v)
            for k, v in vs.items():
                if k in variables and variables[k] != v:
                    self.note(target, f"variable {k}", f"conflicting values across variants; keeping {variables[k]!r}")
                variables.setdefault(k, v)
        order = _merge_orders(seqs)
        if order is None:
            self.note(target, "fold", "variant step orders conflict; falling back to variant-by-variant order")
            order = list(dict.fromkeys(k for seq in seqs for k in seq))
        props = sorted({v for _, c in info.values() if isinstance(c, dict) for v in c.values()})
        space = Space(tuple(flags), tuple(props))
        atoms = {k: self._atoms(info[k][1], used_by[k], props) for k in order}
        if unify:
            for msg in _unify_temp_space([info[k][0] for k in order], unify, [atoms[k] for k in order]):
                self.note(target, "unify_temp_space", msg)
        conds = {k: _combine(info[k][1], _predicate(used_by[k], flags)) for k in order}
        steps = [_with_condition(info[k][0], conds[k]) for k in order]
        # the variant flags, with the value most files have; dbb-app.yaml sets the other value per file
        extra_vars: list[dict] = [{"name": f, "value": FEATURE_DEFAULTS.get(f, False)} for f in flags]
        if self.consolidate:
            try:
                steps, selects = self._consolidate(target, space, order, info, atoms, seqs, conds, steps, variables)
                extra_vars += selects
            except ConsolidationError as e:
                self.note(target, "consolidate", f"steps left unconsolidated: {e}")
        return self._task(target, lds[0].language_code, steps, datasets, variables, extra_vars)

    @staticmethod
    def _atoms(cond, assignments, props) -> frozenset:
        """Flag/property combinations in which a step runs (a property condition fixes that property)."""
        out = set()
        for assign in assignments:
            for pv in itertools.product((False, True), repeat=len(props)):
                if cond:
                    (kind, prop), = cond.items()
                    if pv[props.index(prop)] != (kind == "exists"):
                        continue
                out.add(tuple(assign) + pv)
        return frozenset(out)

    def _consolidate(self, target, space, order, info, atoms, seqs, conds, steps, variables):
        """Merge same-role steps; returns (steps, new variables), verified equivalent to `steps`."""
        index = {k: i for i, k in enumerate(order)}
        items = [Item(info[k][0], atoms[k], conds[k]) for k in order]
        # names a new variable must not shadow: existing variables, properties, any ${...} in use
        reserved = set(variables) | set(space.props)
        reserved |= set(re.findall(r"\$\{(\w+)\}", json.dumps([i.step for i in items])))
        why: list[str] = []
        result = _consolidate(items, [[index[k] for k in seq] for seq in seqs], space, reserved, why)
        for msg in why:
            self.note(target, "consolidate", msg)
        problems = _verify(steps, result.steps, result.variables, space)
        if problems:
            raise ConsolidationError(f"consolidated task is not equivalent ({'; '.join(problems[:3])})")
        return result.steps, result.variables

    @staticmethod
    def create_options(d: DataSetDef | None, member: bool) -> str:
        if d is None:  # source dataset: sensible default
            return "cyl space(10,10) lrecl(80) dsorg(PO) recfm(F,B) dsntype(library)"
        opts = _space_options(d) or ["cyl space(5,5)"]
        pds = member or d.directory_blocks is not None
        opts.append("dsorg(PO) dsntype(library)" if pds else "dsorg(PS)")
        return " ".join(opts)


def _predicate(assignments: list[tuple[bool, ...]], flags: list[str]) -> str:
    """Smallest AND/OR expression over `flags` true for exactly `assignments`.

    Merges pairs of terms that differ in one flag (Quine-McCluskey, one pass to
    fixpoint). Returns "" when the step applies to every combination given.
    """
    terms = {tuple(a) for a in assignments}
    changed = True
    while changed:
        changed = False
        for x in sorted(terms, key=str):
            for y in sorted(terms, key=str):
                diff = [i for i, (p, q) in enumerate(zip(x, y)) if p != q]
                if len(diff) == 1 and x[diff[0]] is not None and y[diff[0]] is not None:
                    merged = tuple(None if i == diff[0] else v for i, v in enumerate(x))
                    terms = (terms - {x, y}) | {merged}
                    changed = True
                    break
            if changed:
                break
    rendered = []
    for term in sorted(terms, key=lambda t: [(-1 if v is None else int(v)) for v in t], reverse=True):
        parts = [(f"${{{f}}}" if v else f"${{{f}}} != true") for f, v in zip(flags, term) if v is not None]
        if not parts:
            return ""
        rendered.append(" && ".join(parts))
    if len(rendered) == 1:
        return rendered[0]
    return " || ".join(f"({r})" if " && " in r else r for r in rendered)


def _combine(cond, predicate: str):
    if not predicate:
        return cond
    if not cond:
        return predicate
    return {**cond, "eval": predicate}


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


FOLD_KEYS = {"variants", "unify_temp_space"}


def _fold_spec(target: str, spec: dict) -> tuple[dict, list[str]]:
    """(variants, unify_temp_space patterns) from either fold-map shape.

    Shape 1 (older): {langdef name: {flag: bool}}.  Shape 2: {"variants": {...}, "unify_temp_space": [...]}.
    """
    if isinstance(spec.get("variants"), dict):
        unknown = set(spec) - FOLD_KEYS
        if unknown:
            raise ValueError(f"fold {target!r}: unknown key(s) {sorted(unknown)}; expected {sorted(FOLD_KEYS)}")
        patterns = spec.get("unify_temp_space") or []
        if isinstance(patterns, str):
            patterns = [patterns]
        return spec["variants"], list(patterns)
    return spec, []


def emit_all(
    sd: SystemDefinition,
    sources_map: dict[str, list[str]] | None = None,
    folds: dict[str, dict] | None = None,
    version: str = SCHEMA_VERSION,
    consolidate: bool = True,
    variants: bool = True,
) -> Output:
    """Convert every language definition.

    Folded tasks come from two places: the variant families `variants.py` detects (unless `variants`
    is false) and the explicit `folds` of a fold map, which win for every language definition they
    name. `folds` maps a target task name to {langdef name: {flag: bool}} (see _Converter.fold), or
    to {"variants": {...}, "unify_temp_space": [DD name globs]}.
    """
    conv = _Converter(sd, sources_map, consolidate)
    out = Output()
    specs = {target: _fold_spec(target, spec) for target, spec in (folds or {}).items()}
    for target, (members, _) in specs.items():
        missing = [n for n in members if n not in sd.langdefs]
        if missing:
            raise ValueError(f"fold {target!r}: unknown language definition(s) {missing}")
        clash = target in sd.langdefs and target not in members
        if clash:
            raise ValueError(f"fold {target!r}: the task name belongs to a language definition outside the fold")
        flags = [sorted(flags) for flags in members.values()]
        if any(f != flags[0] for f in flags):
            raise ValueError(f"fold {target!r}: every variant must set the same flags")
        out.families.append(Family(target, sd.langdefs[next(iter(members))].language_code, flags[0],
                                   {m: dict(v) for m, v in members.items()}, source="fold map"))
    explicit = {n for members, _ in specs.values() for n in members}
    aliases: dict[str, str] = {}  # langdef -> family member with the same translators
    if variants:
        families, family_notes = find_families(sd)
        for message in family_notes:
            conv.note("variants", "grouping", message)
        for fam in families:
            members = {m: v for m, v in fam.members.items() if m not in explicit}
            task, features = fam.task, fam.features
            if len(members) < len(fam.members):
                conv.note(fam.task, "variants", f"{sorted(set(fam.members) - set(members))} are folded by the "
                                                f"fold map; the detected family keeps {sorted(members)}")
                if len(members) < 2:
                    continue
                # what is left: named after its plainest member, folded on the flags that still vary
                features = [f for f in fam.features if len({v[f] for v in members.values()}) > 1]
                members = {m: {f: v[f] for f in features} for m, v in members.items()}
                task = min(members, key=lambda m: (sum(v != FEATURE_DEFAULTS.get(f, False)
                                                       for f, v in members[m].items()), len(m), m))
            if len(members) < 2 or not features:
                continue
            if task in specs or (task in sd.langdefs and task not in members):
                conv.note(task, "variants", "detected family not folded: its task name is taken")
                continue
            specs[task] = (members, [])
            kept = {a: c for a, c in fam.aliases.items() if c in members and a not in explicit}
            aliases.update(kept)
            out.families.append(Family(task, fam.language_code, features, members, kept))
    member_of = {m: target for target, (members, _) in specs.items() for m in members}
    by_task = {fam.task: fam for fam in out.families}

    tasks: list[str] = []
    task_variables: dict[str, list[str]] = {}
    for ld in sd.langdefs.values():
        name = aliases.get(ld.name, ld.name)
        target = member_of.get(name)
        task_name = target or ld.name
        out.language_map[ld.name] = {
            "task": task_name,
            "variables": dict(specs[target][0][name]) if target else {},
        }
        if task_name in tasks or ld.name in aliases:
            continue
        doc_task = conv.fold(target, *specs[target]) if target else conv.language(ld)
        task_variables[task_name] = sorted(v["name"] for v in doc_task.get("variables", []))
        fname = f"{slug(task_name)}.yaml"
        if fname in out.files:
            fname = f"{slug(task_name)}_{len(out.files)}.yaml"
        header = ""
        if target:
            fam = by_task[target]
            header = "# One task for these EWM language definitions (variants of one compiler):\n" + "".join(
                f"#   {m}: {', '.join(f'{k}={str(v).lower()}' for k, v in vals.items())}\n"
                for m, vals in fam.members.items()) + "".join(
                f"#   {a}: same translators as {c}\n" for a, c in fam.aliases.items())
        out.files[fname] = header + _dump({"version": version, "tasks": [doc_task]})
        tasks.append(task_name)
    out.files["Languages.yaml"] = _dump({
        "version": version,
        "include": [{"file": f} for f in out.files],
        "tasks": [{"stage": "Languages", "tasks": tasks}],
        "variables": [{"name": k, "value": v} for k, v in conv.sysvar_values.items()],
    })
    out.files = {"Languages.yaml": out.files.pop("Languages.yaml"), **out.files}
    for entry in out.language_map.values():
        entry["taskVariables"] = task_variables[entry["task"]]
    out.notes = conv.notes
    out.properties = list(conv.properties)
    return out
