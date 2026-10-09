"""Language definitions that are variants of one compiler.

EWM systems often keep one language definition per runtime combination of the
same compiler: COBOL batch, COBOL CICS, COBOL DB2 and COBOL CICS+DB2, each with
and without link-edit. zBuilder expresses that with one language task whose
steps and options depend on boolean variables (`IS_CICS`, `IS_SQL`,
`doLinkEdit`, as in IBM's zBuilder samples) that the application configuration
sets per file. This module finds such families; `emit` folds each into one
task (`consolidate.py` merges and verifies the steps) and `language-map.yaml`
tells `ewm2zbuilder app` which task and variables belong to each language
definition. A `--fold-map` can add or override families.

A family is a set of language definitions that
  * have the same language code,
  * have the same name once CICS/DB2/SQL, link-edit and "batch" words are
    removed ("Cobol DB2 CICS Compile and Link" -> "cobol compile"),
  * run the same compiler, and
  * differ in their CICS/SQL/link-edit features, one definition per combination.
Definitions that share a combination but run exactly the same translators are
aliases: one joins the family, the others use its task. Anything else stays a
task of its own (and gets per-file overrides in the application
configuration). The conversion report lists every family and every near miss,
so the grouping can be reviewed.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from .model import CALL_COMMAND, LangDef, SystemDefinition

FEATURES = ("IS_CICS", "IS_SQL", "doLinkEdit")
# Value of each variable in the merged task; dbb-app.yaml sets the other value per file.
FEATURE_DEFAULTS = {"IS_CICS": False, "IS_SQL": False, "doLinkEdit": True}

# Translator programs that imply a feature.
_FEATURE_PROGRAMS = {
    "IS_CICS": re.compile(r"^DFH\w*P1\$$"),  # CICS command translators: DFHECP1$, DFHEAP1$, DFHEPP1$
    "IS_SQL": re.compile(r"^DSNH"),  # Db2 precompilers: DSNHPC, DSNHPSM
    "doLinkEdit": re.compile(r"^(IEWL|IEWBLINK|IEWBLNK|HEWL|HEWLH096|HEWLKED)$"),  # the binder
}
# Compiler options that imply a feature (integrated CICS translator / Db2 coprocessor).
_FEATURE_OPTIONS = {"IS_CICS": re.compile(r"\bCICS\b"), "IS_SQL": re.compile(r"\bSQL\b"),
                    "doLinkEdit": re.compile(r"(?!)")}  # no compiler option links
# Words in language definition names.
_FEATURE_WORDS = {"IS_CICS": r"cics", "IS_SQL": r"db2|sql",
                  "doLinkEdit": r"(?:and[\s_-]*)?(?:link[\s_-]*edit|link|lked)"}
_NEGATION = r"(?:no|non|without)[\s_-]*"
# Words that only say "no CICS/DB2": ignored when grouping, dropped from the task name.
_NEUTRAL = r"batch"

# Compiler programs by EWM language code; a family must share one of them.
COMPILERS = {
    "COB": {"IGYCRCTL"},
    "PLI": {"IBMZPLI", "IEL0AA", "IEL1AA"},
    "ASM": {"ASMA90"},
    "C": {"CCNDRVR"},
}


@dataclass
class Family:
    task: str  # zBuilder task name: the member with the fewest features (without "batch")
    language_code: str
    features: list[str]  # the features that vary within the family
    members: dict[str, dict[str, bool]] = field(default_factory=dict)  # langdef -> feature values
    aliases: dict[str, str] = field(default_factory=dict)  # langdef -> member with the same translators
    source: str = "detected"  # or "fold map"

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
    lower = re.sub(rf"(?<![a-z])(?:{words}|{_NEUTRAL})(?![a-z])", " ", lower)
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
        if len(lds) < 2 or not key:  # a name of nothing but feature words ("Batch Link") says nothing
            continue
        feats = {ld.name: features(sd, ld) for ld in lds}
        by_combo: dict[tuple[bool, ...], list[str]] = {}
        for name, f in feats.items():
            by_combo.setdefault(tuple(f[x] for x in FEATURES), []).append(name)
        members = []
        aliases: dict[str, str] = {}
        for combo, names in by_combo.items():
            names = sorted(names)
            if len(names) > 1 and len({_translators(sd, n) for n in names}) == 1:
                # the same build: one of them joins the family, the others use its task
                members.append(names[0])
                aliases.update({n: names[0] for n in names[1:]})
            elif len(names) > 1:
                notes.append(f"'{key}' ({code}): {names} have the same CICS/SQL/link-edit features "
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
        # the task is named after the plainest variant: batch, linked
        plainest = min(members, key=lambda m: (sum(feats[m][f] != FEATURE_DEFAULTS[f] for f in FEATURES), len(m), m))
        task = _without_neutral_words(plainest)
        if task != plainest and task in sd.langdefs:
            task = plainest  # the shorter name belongs to another definition
        families.append(Family(task, code, varying, {m: {f: feats[m][f] for f in varying} for m in members},
                               {a: c for a, c in aliases.items() if c in members}))
    return families, notes


def _translators(sd: SystemDefinition, name: str) -> tuple:
    """What a language definition runs: its translators and their conditions."""
    ld = sd.langdefs[name]
    return tuple(ld.translators), tuple(" ".join(c.split()) for c in ld.conditions)


def _without_neutral_words(name: str) -> str:
    """ "C Batch Compile and Link" -> "C Compile and Link" """
    stripped = re.sub(rf"(?i)(?<![a-z])(?:{_NEUTRAL})(?![a-z])", " ", name)
    return " ".join(stripped.split()) or name
