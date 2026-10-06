"""Language definitions that are variants of one compiler.

EWM systems often keep one language definition per runtime combination of the
same compiler: COBOL batch, COBOL CICS, COBOL DB2 and COBOL CICS+DB2. zBuilder
expresses that with one language task whose steps and options depend on
boolean variables (`IS_CICS`, `IS_SQL`) that the application configuration
sets per file. This module finds such families; `emit` merges each into one
task and `language-map.yaml` tells `ewm2zbuilder app` which task and variables
belong to each language definition.

A family is a set of language definitions that
  * have the same language code,
  * have the same name once CICS/DB2/SQL words are removed
    ("Cobol DB2 CICS Compile and Link" -> "cobol compile and link"),
  * run the same compiler, and
  * differ in their CICS/SQL features, one definition per combination.
Anything else stays a task of its own (and gets per-file overrides in the
application configuration). The conversion report lists every family and
every near miss, so the grouping can be reviewed.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from itertools import combinations

from .model import CALL_COMMAND, LangDef, SystemDefinition

FEATURES = ("IS_CICS", "IS_SQL")

# Translator programs that imply a feature.
_FEATURE_PROGRAMS = {
    "IS_CICS": re.compile(r"^DFH\w*P1\$$"),  # CICS command translators: DFHECP1$, DFHEAP1$, DFHEPP1$
    "IS_SQL": re.compile(r"^DSNH"),  # Db2 precompilers: DSNHPC, DSNHPSM
}
# Compiler options that imply a feature (integrated CICS translator / Db2 coprocessor).
_FEATURE_OPTIONS = {"IS_CICS": re.compile(r"\bCICS\b"), "IS_SQL": re.compile(r"\bSQL\b")}
# Words in language definition names.
_FEATURE_WORDS = {"IS_CICS": r"cics", "IS_SQL": r"db2|sql"}
_NEGATION = r"(?:no|non|without)[\s_-]*"

# Compiler programs by EWM language code; a family must share one of them.
COMPILERS = {
    "COB": {"IGYCRCTL"},
    "PLI": {"IBMZPLI", "IEL0AA", "IEL1AA"},
    "ASM": {"ASMA90"},
    "C": {"CCNDRVR"},
}


@dataclass
class Family:
    task: str  # zBuilder task name: the member with the fewest features
    language_code: str
    features: list[str]  # the features that vary within the family
    members: dict[str, dict[str, bool]] = field(default_factory=dict)  # langdef -> feature values

    def values(self, langdef: str) -> tuple[bool, ...]:
        return tuple(self.members[langdef][f] for f in self.features)


def program_key(sd: SystemDefinition, translator_name: str) -> str:
    """What a translator runs: the program member, or the REXX exec / TSO command."""
    t = sd.translators.get(translator_name)
    if t is None:
        return f"?{translator_name}"
    if t.call_method == CALL_COMMAND:
        command = t.command_member or ""
        m = re.search(r"\(([^)]+)\)", command)  # EXEC 'DSN(MEMBER)' ...
        return "TSO:" + (m.group(1) if m else command.split(" ", 1)[0]).upper()
    d = sd.dsdefs.get(t.ds_def) if t.ds_def else None
    return (d.member or d.ds_name or "?").upper() if d else f"?{translator_name}"


def _name_feature(name: str, feature: str) -> bool | None:
    """True/False if the name says so ("CICS", "no CICS"), None if it does not mention the feature."""
    lower = name.lower()
    words = _FEATURE_WORDS[feature]
    if re.search(rf"(?<![a-z]){_NEGATION}(?:{words})(?![a-z])", lower):
        return False
    if re.search(rf"(?<![a-z])(?:{words})(?![a-z])", lower):
        return True
    return None


def features(sd: SystemDefinition, ld: LangDef) -> dict[str, bool]:
    programs = [program_key(sd, n) for n in ld.translators]
    options = " ".join((sd.translators[n].default_options or "") for n in ld.translators if n in sd.translators)
    result = {}
    for f in FEATURES:
        by_name = _name_feature(ld.name, f)
        if by_name is False:
            result[f] = False
            continue
        result[f] = bool(
            by_name
            or any(_FEATURE_PROGRAMS[f].match(p) for p in programs)
            or _FEATURE_OPTIONS[f].search(options)
        )
    return result


def family_key(name: str) -> str:
    lower = name.lower()
    words = "|".join(_FEATURE_WORDS.values())
    lower = re.sub(rf"(?<![a-z]){_NEGATION}(?:{words})(?![a-z])", " ", lower)
    lower = re.sub(rf"(?<![a-z])(?:{words})(?![a-z])", " ", lower)
    lower = re.sub(r"\band\b(?=\s*[)&,/]|\s*$)", " ", lower)  # "(CICS and DB2)" leftovers
    return " ".join(re.sub(r"[^a-z0-9]+", " ", lower).split())


def find_families(sd: SystemDefinition) -> tuple[list[Family], list[str]]:
    """Families of variant language definitions, plus notes on groups that were not merged."""
    groups: dict[tuple[str, str], list[LangDef]] = {}
    for ld in sd.langdefs.values():
        if ld.translators:  # e.g. copybooks: nothing to build, nothing to merge
            groups.setdefault((ld.language_code, family_key(ld.name)), []).append(ld)

    families: list[Family] = []
    notes: list[str] = []
    for (code, key), lds in sorted(groups.items()):
        if len(lds) < 2:
            continue
        feats = {ld.name: features(sd, ld) for ld in lds}
        by_combo: dict[tuple[bool, ...], list[str]] = {}
        for name, f in feats.items():
            by_combo.setdefault(tuple(f[x] for x in FEATURES), []).append(name)
        members = []
        for combo, names in by_combo.items():
            if len(names) > 1:
                notes.append(f"'{key}' ({code}): {sorted(names)} have the same CICS/SQL features "
                             f"{dict(zip(FEATURES, combo))}; kept as separate tasks")
            else:
                members.append(names[0])
        if len(members) < 2:
            continue
        compilers = COMPILERS.get(code)
        programs = {name: {program_key(sd, n) for n in sd.langdefs[name].translators} for name in members}
        shared = set.intersection(*programs.values())
        if (compilers and any(not (p & compilers) for p in programs.values())) or not shared:
            notes.append(f"'{key}' ({code}): {sorted(members)} do not run the same compiler; not merged")
            continue
        varying = [f for f in FEATURES if len({feats[m][f] for m in members}) > 1]
        task = min(members, key=lambda m: (sum(feats[m].values()), len(m), m))
        families.append(Family(task, code, varying, {m: {f: feats[m][f] for f in varying} for m in members}))
    return families, notes


def _literal(feature: str, value: bool) -> str:
    return f"${{{feature}}}" if value else f"${{{feature}}} != true"


def condition_for(present: set[tuple[bool, ...]], family: Family) -> str | None:
    """The shortest JEXL condition that is true exactly for the variants in `present`."""
    all_combos = {family.values(m) for m in family.members}
    if present == all_combos:
        return None
    n = len(family.features)
    literals = [(i, v) for i in range(n) for v in (True, False)]
    for i, v in literals:
        if {c for c in all_combos if c[i] == v} == present:
            return _literal(family.features[i], v)
    for (i, vi), (j, vj) in combinations(literals, 2):
        if i == j:
            continue
        if {c for c in all_combos if c[i] == vi and c[j] == vj} == present:
            return f"{_literal(family.features[i], vi)} && {_literal(family.features[j], vj)}"
        if {c for c in all_combos if c[i] == vi or c[j] == vj} == present:
            return f"{_literal(family.features[i], vi)} || {_literal(family.features[j], vj)}"
    terms = [" && ".join(_literal(family.features[i], c[i]) for i in range(n)) for c in sorted(present)]
    return " || ".join(f"( {t} )" for t in terms)


def merge_sequences(sequences: dict[str, list[tuple]]) -> list[dict[str, int]]:
    """Merge per-member step sequences into slots, keeping each member's order.

    `sequences` maps member -> list of keys. Returns slots in merged order; a slot
    maps member -> index into its sequence. Equal keys are aligned (longest common
    subsequence, member by member).
    """
    slots: list[tuple[tuple, dict[str, int]]] = []
    for member, seq in sequences.items():
        if not slots:
            slots = [(k, {member: i}) for i, k in enumerate(seq)]
            continue
        a = [k for k, _ in slots]
        # LCS table
        lcs = [[0] * (len(seq) + 1) for _ in range(len(a) + 1)]
        for x in range(len(a) - 1, -1, -1):
            for y in range(len(seq) - 1, -1, -1):
                lcs[x][y] = lcs[x + 1][y + 1] + 1 if a[x] == seq[y] else max(lcs[x + 1][y], lcs[x][y + 1])
        merged: list[tuple[tuple, dict[str, int]]] = []
        x = y = 0
        while x < len(a) or y < len(seq):
            if x < len(a) and y < len(seq) and a[x] == seq[y]:
                merged.append((a[x], {**slots[x][1], member: y}))
                x, y = x + 1, y + 1
            elif y < len(seq) and (x == len(a) or lcs[x][y + 1] >= lcs[x + 1][y]):
                merged.append((seq[y], {member: y}))
                y += 1
            else:
                merged.append(slots[x])
                x += 1
        slots = merged
    return [members for _, members in slots]
