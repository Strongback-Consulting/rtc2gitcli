"""Consolidate the steps of a folded task into fewer steps, using conditions.

A folded task has one step per EWM translator, each guarded by the variant(s) it
belongs to. Many of those steps are the same *role* (same program) repeated per
variant, differing in a few DDs or in `parm`. This module merges them:

  * steps with the same role and disjoint applicability become one step;
  * a DD present in only some of the merged steps gets a `condition`;
  * a `parm`/`command` that differs becomes a task variable with `select`;
  * every condition is the smallest expression over the variant flags
    (`eval`) and properties (`exists` / `notExists`) that selects exactly the
    right cases (cases where the merged step cannot run act as "don't care").

Correctness is not assumed: `verify` expands the original and the consolidated
task for every combination of flags and properties and compares the resulting
step lists (program, parm, DDs, order). The caller falls back to the
unconsolidated steps if anything differs.
"""

from __future__ import annotations

import itertools
import json
import re
from dataclasses import dataclass
from fnmatch import fnmatchcase

Atom = tuple[bool, ...]  # one value per name in Space.names
Cube = tuple  # True / False / None (= either) per name


class ConsolidationError(Exception):
    pass


@dataclass(frozen=True)
class Space:
    flags: tuple[str, ...]  # variant flags, rendered as `eval` expressions
    props: tuple[str, ...]  # build properties, rendered as exists / notExists

    @property
    def names(self) -> tuple[str, ...]:
        return self.flags + self.props

    def universe(self) -> frozenset[Atom]:
        return frozenset(itertools.product((False, True), repeat=len(self.names)))


@dataclass
class Item:
    step: dict  # without `condition`
    atoms: frozenset[Atom]  # combinations in which the step runs
    condition: object  # the unconsolidated condition (used if the item cannot be merged)


@dataclass
class Result:
    steps: list[dict]
    variables: list[dict]  # new task variables (`select`)


# -- conditions -----------------------------------------------------------------


def _ck(c: Cube) -> tuple:
    return tuple(2 if v is None else int(v) for v in c)


def _cube_atoms(c: Cube):
    return itertools.product(*[(False, True) if v is None else (v,) for v in c])


def cover(on: set[Atom], dc: set[Atom], n: int) -> list[Cube]:
    """Few, simple cubes that include every `on` atom and no atom outside on|dc."""
    on, allowed = set(on), set(on) | set(dc)
    cands = []
    for c in itertools.product((False, True, None), repeat=n):
        atoms = set(_cube_atoms(c))
        if atoms <= allowed and atoms & on:
            cands.append((c, atoms))
    uncovered, chosen = set(on), []
    while uncovered:
        c, atoms = min(
            cands, key=lambda ca: (-len(ca[1] & uncovered), sum(v is not None for v in ca[0]), _ck(ca[0]))
        )
        chosen.append((c, atoms))
        uncovered -= atoms
    # drop cubes whose `on` atoms are all covered by the other chosen cubes
    for i in range(len(chosen) - 1, -1, -1):
        others = set().union(*(a for j, (_, a) in enumerate(chosen) if j != i)) if len(chosen) > 1 else set()
        if len(chosen) > 1 and (chosen[i][1] & on) <= others:
            chosen.pop(i)
    return [c for c, _ in chosen]


def _lit(name: str, value: bool) -> str:
    return f"${{{name}}}" if value else f"${{{name}}} != true"


def _or(terms: list[str]) -> str:
    terms = list(dict.fromkeys(terms))
    return terms[0] if len(terms) == 1 else " || ".join(f"({t})" if " && " in t else t for t in terms)


def _render(space: Space, cubes: list[Cube]):
    """One zBuilder condition (str, dict) for cubes that share their property literals."""
    nf = len(space.flags)
    prop_lits = {tuple(c[nf:]) for c in cubes}
    if len(prop_lits) != 1:
        raise ConsolidationError("cubes differ in property literals")
    pl = next(iter(prop_lits))
    terms: list[str] | None = []
    for c in cubes:
        parts = [_lit(f, v) for f, v in zip(space.flags, c[:nf]) if v is not None]
        if not parts:
            terms = None  # no restriction on the flags
            break
        terms.append(" && ".join(parts))
    expr = _or(terms) if terms else ""
    cond: dict = {}
    exists = [p for p, v in zip(space.props, pl) if v is True]
    not_exists = [p for p, v in zip(space.props, pl) if v is False]
    if exists:
        cond["exists"] = exists[0] if len(exists) == 1 else exists
    if not_exists:
        cond["notExists"] = not_exists[0] if len(not_exists) == 1 else not_exists
    if expr:
        cond["eval"] = expr
    if not cond:
        return None
    return expr if list(cond) == ["eval"] else cond


def conditions(space: Space, on: set[Atom], dc: set[Atom]) -> list:
    """Conditions whose union holds exactly on `on` (atoms in `dc` may go either way).

    [] = never, [None] = always, several = alternatives (disjoint when properties
    are involved, so an entry repeated once per alternative is never duplicated).
    """
    on = set(on)
    if not on:
        return []
    n, nf = len(space.names), len(space.flags)
    cubes = sorted(cover(on, set(dc), n), key=_ck)
    if any(all(v is None for v in c) for c in cubes):
        return [None]
    if len({tuple(c[nf:]) for c in cubes}) == 1:
        return [_render(space, cubes)]
    out = []
    for pp in sorted({a[nf:] for a in on}):
        sub_on = {a for a in on if a[nf:] == pp}
        sub_dc = {a for a in dc if a[nf:] == pp}
        out.append(_render(space, sorted(cover(sub_on, sub_dc, n), key=_ck)))
    return out


def _as_list(v) -> list[str]:
    return [] if v is None else ([v] if isinstance(v, str) else list(v))


def and_conditions(a, b):
    """AND two zBuilder conditions (None | eval string | dict of eval/exists/notExists)."""
    if not a:
        return b
    if not b:
        return a
    da = {"eval": a} if isinstance(a, str) else a
    db = {"eval": b} if isinstance(b, str) else b
    out: dict = {}
    for key in ("exists", "notExists"):
        names = _as_list(da.get(key))
        names += [n for n in _as_list(db.get(key)) if n not in names]
        if names:
            out[key] = names[0] if len(names) == 1 else names
    evals = [e for e in (da.get("eval"), db.get("eval")) if e]
    if evals:
        out["eval"] = evals[0] if len(evals) == 1 else " && ".join(f"({e})" for e in evals)
    return out["eval"] if list(out) == ["eval"] else out


def with_condition(step: dict, cond) -> dict:
    """Return `step` with `condition` inserted before maxRC (the key order of the samples)."""
    if not cond:
        return step
    out = {}
    for k, v in step.items():
        if k == "maxRC":
            out["condition"] = cond
        out[k] = v
    if "condition" not in out:
        out["condition"] = cond
    return out


def _dd_with_condition(dd: dict, cond) -> dict:
    """Put `condition` just before `options` (the key order the emitter uses for DDs)."""
    out, placed = {}, False
    for k, v in dd.items():
        if k == "condition":
            continue
        if k == "options" and cond:
            out["condition"], placed = cond, True
        out[k] = v
    if cond and not placed:
        out["condition"] = cond
    return out


# -- ordering ---------------------------------------------------------------------


def merge_orders(seqs: list[list]) -> list | None:
    """Topologically merge sequences, keeping each sequence's order (None on conflict)."""
    first: dict = {}
    after: dict = {}
    indeg: dict = {}
    for seq in seqs:
        for k in seq:
            first.setdefault(k, len(first))
            after.setdefault(k, set())
            indeg.setdefault(k, 0)
        for a, b in zip(seq, seq[1:]):
            if b not in after[a]:
                after[a].add(b)
                indeg[b] += 1
    ready = sorted((k for k, n in indeg.items() if n == 0), key=first.get)
    out = []
    while ready:
        k = ready.pop(0)
        out.append(k)
        for nxt in sorted(after[k], key=first.get):
            indeg[nxt] -= 1
            if indeg[nxt] == 0:
                ready.append(nxt)
        ready.sort(key=first.get)
    return out if len(out) == len(first) else None


def conflicting_keys(seqs: list[list]) -> set:
    """Keys that sit on an ordering cycle (two sequences order them against each other)."""
    after: dict = {}
    indeg: dict = {}
    for seq in seqs:
        for k in seq:
            after.setdefault(k, set())
            indeg.setdefault(k, 0)
        for a, b in zip(seq, seq[1:]):
            if b not in after[a]:
                after[a].add(b)
                indeg[b] += 1
    ready = [k for k, n in indeg.items() if n == 0]
    while ready:  # Kahn: whatever is left is on a cycle or downstream of one
        k = ready.pop()
        indeg.pop(k)
        for nxt in after[k]:
            indeg[nxt] -= 1
            if indeg[nxt] == 0:
                ready.append(nxt)
    left = set(indeg)
    bad = set()
    for v in left:
        seen, stack = set(), [n for n in after[v] if n in left]
        while stack:
            u = stack.pop()
            if u == v:
                bad.add(v)
                break
            if u not in seen:
                seen.add(u)
                stack.extend(n for n in after[u] if n in left)
    return bad


def split_conflicts(seqs: list[list]) -> list[list]:
    """Re-key entries whose order conflicts, so only sequences that agree keep sharing them.

    Sequences with the same relative order of the conflicting entries share one copy;
    if that is not enough every sequence gets its own copy. Raises if still cyclic.
    """
    for mode in ("cluster", "each"):
        bad = conflicting_keys(seqs)
        if not bad:
            return seqs
        tags: dict[tuple, int] = {}
        out = []
        for i, seq in enumerate(seqs):
            tag = tags.setdefault(tuple(k for k in seq if k in bad), len(tags)) if mode == "cluster" else i
            out.append([k + (tag,) if k in bad else k for k in seq])
        seqs = out
    if conflicting_keys(seqs):
        raise ConsolidationError("DD orders conflict and cannot be separated")
    return seqs


# -- naming and value factoring -----------------------------------------------------


def _same_word(a: str, b: str) -> bool:
    """Equal, or one is the other plus a suffix (compile / compiler, Precompile / Precompiler)."""
    if a == b:
        return True
    short, long_ = sorted((a.lower(), b.lower()), key=len)
    return len(short) >= 3 and long_.startswith(short)


def _lcs(a: list[str], b: list[str]) -> list[str]:
    """Longest common word subsequence; a fuzzy match keeps the shorter word."""
    dp = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(len(a) - 1, -1, -1):
        for j in range(len(b) - 1, -1, -1):
            dp[i][j] = dp[i + 1][j + 1] + 1 if _same_word(a[i], b[j]) else max(dp[i + 1][j], dp[i][j + 1])
    i = j = 0
    out = []
    while i < len(a) and j < len(b):
        if _same_word(a[i], b[j]):
            out.append(min(a[i], b[j], key=len))
            i, j = i + 1, j + 1
        elif dp[i + 1][j] >= dp[i][j + 1]:
            i += 1
        else:
            j += 1
    return out


def merged_name(names: list[str]) -> str:
    """Words shared by all step names, in order ("COBOL Compile current/alternate" -> "COBOL Compile")."""
    common = names[0].split()
    for n in names[1:]:
        common = _lcs(common, n.split())
    words = [w for w in common if w not in ("-", "--")]
    return " ".join(words) or names[0]


def camel(name: str) -> str:
    words = re.findall(r"[A-Za-z0-9]+", name)
    return (words[0].lower() + "".join(w.capitalize() for w in words[1:])) if words else "step"


_TOKEN = re.compile(r"[@$]\{[^}]*\}|\w+|\s+|.", re.S)


def factor(values: list[str]) -> tuple[str, str, list[str]] | None:
    """Split values into (shared prefix, shared suffix, differing middles), on token boundaries.

    None if what is shared is only punctuation (not worth a template).
    """
    toks = [_TOKEN.findall(v) for v in values]
    p = 0
    while all(len(t) > p for t in toks) and len({t[p] for t in toks}) == 1:
        p += 1
    s = 0
    while all(len(t) - p > s for t in toks) and len({t[-1 - s] for t in toks}) == 1:
        s += 1
    prefix = "".join(toks[0][:p])
    suffix = "".join(toks[0][len(toks[0]) - s:]) if s else ""
    mids = ["".join(t[p:len(t) - s]) for t in toks]
    if not re.search(r"\w|[@$]\{", prefix + suffix) or any(m == "" for m in mids):
        return None
    return prefix, suffix, mids


# -- expansion and verification -------------------------------------------------------


def _eval(expr: str, env: dict[str, bool]) -> bool:
    toks = list(re.finditer(r"\(|\)|&&|\|\||\$\{(\w+)\}(\s*!=\s*true)?|\S", expr))
    pos = 0

    def or_() -> bool:
        nonlocal pos
        v = and_()
        while pos < len(toks) and toks[pos].group(0) == "||":
            pos += 1
            v = and_() or v
        return v

    def and_() -> bool:
        nonlocal pos
        v = atom()
        while pos < len(toks) and toks[pos].group(0) == "&&":
            pos += 1
            v = atom() and v
        return v

    def atom() -> bool:
        nonlocal pos
        if pos >= len(toks):
            raise ConsolidationError(f"unexpected end of condition {expr!r}")
        m = toks[pos]
        pos += 1
        if m.group(0) == "(":
            v = or_()
            if pos >= len(toks) or toks[pos].group(0) != ")":
                raise ConsolidationError(f"unbalanced parentheses in {expr!r}")
            pos += 1
            return v
        if m.group(1):
            if m.group(1) not in env:
                raise ConsolidationError(f"unknown name {m.group(1)!r} in condition {expr!r}")
            v = env[m.group(1)]
            return (not v) if m.group(2) else v
        raise ConsolidationError(f"cannot parse condition {expr!r}")

    result = or_()
    if pos != len(toks):
        raise ConsolidationError(f"trailing tokens in condition {expr!r}")
    return result


def _holds(cond, env: dict[str, bool]) -> tuple[bool, dict | None]:
    """(is the condition true under env, residual exists/notExists on names outside env)."""
    if not cond:
        return True, None
    d = {"eval": cond} if isinstance(cond, str) else cond
    ok = True
    resid: dict[str, list[str]] = {}
    if d.get("eval"):
        ok = _eval(d["eval"], env)
    for key in ("exists", "notExists"):
        for n in _as_list(d.get(key)):
            if n in env:
                ok = ok and (env[n] if key == "exists" else not env[n])
            else:
                resid.setdefault(key, []).append(n)
    return ok, ({k: sorted(v) for k, v in resid.items()} or None)


def expand(steps: list[dict], variables: list[dict], space: Space, atom: Atom) -> list[tuple]:
    """What the task would run for one combination of flags/properties (names ignored)."""
    env = dict(zip(space.names, atom))
    chosen: dict[str, str] = {}
    for v in variables:
        for s in v.get("select", []):
            ok, resid = _holds(s["condition"], env)
            if ok and resid is None:
                chosen[v["name"]] = s["value"]
                break

    def sub(text):
        return None if text is None else re.sub(r"\$\{(\w+)\}", lambda m: chosen.get(m.group(1), m.group(0)), text)

    out = []
    for st in steps:
        if not _holds(st.get("condition"), env)[0]:
            continue
        # DDs as blocks (a named DD with the unnamed entries that follow it); the order of different
        # DDs relative to each other has no effect, the order inside each block does.
        blocks: dict[str, list[str]] = {}
        owner = ""
        for dd in st.get("dds", []):
            ok, resid = _holds(dd.get("condition"), env)
            if ok:
                owner = dd.get("name", owner)
                blocks.setdefault(owner, []).append(
                    json.dumps([{k: v for k, v in dd.items() if k != "condition"}, resid], sort_keys=True))
        dds = tuple(sorted((k, tuple(v)) for k, v in blocks.items()))
        rest = {k: v for k, v in st.items() if k not in ("step", "condition", "dds", "parm", "command")}
        out.append((json.dumps(rest, sort_keys=True), sub(st.get("parm")), sub(st.get("command")), dds))
    return out


def verify(original: list[dict], merged: list[dict], variables: list[dict], space: Space) -> list[str]:
    """Differences between the two tasks over every flag/property combination (empty = equivalent)."""
    problems = []
    for atom in sorted(space.universe()):
        a = expand(original, [], space, atom)
        b = expand(merged, variables, space, atom)
        if a != b:
            where = ", ".join(f"{n}={int(v)}" for n, v in zip(space.names, atom))
            detail = f"{len(a)} steps vs {len(b)}"
            for i, (x, y) in enumerate(zip(a, b)):
                if x != y:
                    detail = f"step {i + 1} differs"
                    break
            problems.append(f"[{where}] {detail}")
    return problems


# -- temp work-file space -------------------------------------------------------------------

_SPACE = re.compile(r"\b(cyl|tracks)\s+space\((\d+),\s*(\d+)\)")


def _anonymous_temp(dd: dict) -> bool:
    """A named DD that allocates a fresh, unnamed temporary dataset (`new`, no `dsn`)."""
    return bool(dd.get("name")) and "dsn" not in dd and re.search(r"\bnew\b", dd.get("options", "")) is not None


def _split_space(options: str) -> tuple[str, int, int, str] | None:
    """(unit, primary, secondary, options with the space replaced by a placeholder)."""
    m = _SPACE.search(options)
    if not m:
        return None
    return m.group(1), int(m.group(2)), int(m.group(3)), options[:m.start()] + "\0" + options[m.end():]


def unify_temp_space(steps: list[dict], patterns: list[str], atoms: list[frozenset] | None = None) -> list[str]:
    """Give same-named temp work files of same-role steps one allocation: the largest asked for.

    For each DD name matching one of the glob `patterns`, among steps with the same role
    (e.g. every IGYCRCTL compile step of the folded variants, but not the DB2 precompile that
    uses the same program; pass `atoms` so such roles can be told apart), anonymous temp DDs that differ
    only in space (same unit) are set to the component-wise maximum of primary and secondary.
    DDs that differ in anything else are left alone; returns a message for each of those.
    Steps are modified in place.
    """
    skipped: list[str] = []
    roles: dict[tuple, list[dict]] = {}
    for st, key in zip(steps, role_buckets(steps, atoms)):
        roles.setdefault(key, []).append(st)
    for group in roles.values():
        if len(group) < 2:
            continue
        by_name: dict[str, list[dict]] = {}
        for st in group:
            for dd in st.get("dds", []):
                if _anonymous_temp(dd) and any(fnmatchcase(dd["name"], p) for p in patterns):
                    by_name.setdefault(dd["name"], []).append(dd)
        for name, dds in by_name.items():
            parts = [_split_space(dd["options"]) for dd in dds]
            if None in parts or len({p[0] for p in parts}) != 1 or len({p[3] for p in parts}) != 1:
                skipped.append(f"DD {name} of {group[0]['step']!r} and its variants differ in more than the "
                               "space (or have no space); left as is")
                continue
            unit, template = parts[0][0], parts[0][3]
            unified = template.replace("\0", f"{unit} space({max(p[1] for p in parts)},{max(p[2] for p in parts)})")
            for dd in dds:
                dd["options"] = unified
    return skipped


# -- consolidation ----------------------------------------------------------------------


def _role(step: dict) -> tuple:
    rest = {k: v for k, v in step.items() if k not in ("step", "parm", "command", "dds")}
    ident = ""
    if step.get("type") == "tso":
        m = re.search(r"'([^']*)'", step.get("command", ""))
        ident = m.group(1) if m else step["step"]
    elif step.get("type") != "mvs":
        ident = step["step"]  # copy etc.: never merged with another step
    return (json.dumps(rest, sort_keys=True), ident, "parm" in step, "command" in step)


_VAR_REF = re.compile(r"[@$]\{[^}]*\}")


def _shape(step: dict) -> tuple:
    """What a step's parm/command looks like with variable references blanked out."""
    return tuple(_VAR_REF.sub("<v>", step[f]) if f in step else None for f in ("parm", "command"))


def role_buckets(steps: list[dict], atoms: list[frozenset] | None) -> list[tuple]:
    """A key per step: its role, refined by the shape of its parm where steps of one role overlap.

    Steps of one role that run together in some variant cannot all be the same step: the role's
    program is used twice there (e.g. IBMZPLI as the DB2 precompile and as the compile). Only
    then is the parm's shape used to tell the kinds apart. Without `atoms` roles are not refined.
    """
    roles = [_role(st) for st in steps]
    by_role: dict[tuple, list[int]] = {}
    for i, r in enumerate(roles):
        by_role.setdefault(r, []).append(i)
    ambiguous = set()
    if atoms is not None:
        ambiguous = {
            r for r, idx in by_role.items()
            if any(atoms[a] & atoms[b] for a, b in itertools.combinations(idx, 2))
        }
    return [(r, _shape(st)) if r in ambiguous else (r, None) for r, st in zip(roles, steps)]


def _unique(name: str, taken: set[str], sep: str = " ") -> str:
    out, n = name, 2
    while out in taken:
        out, n = f"{name}{sep}{n}", n + 1
    taken.add(out)
    return out


def _merge_dds(its: list[Item], on_all: frozenset[Atom], space: Space, universe: frozenset[Atom]) -> list[dict]:
    """Merge the DD lists of `its` into one, guarding each entry for the steps that have it.

    What matters in a DD list is the order *inside* a concatenation (a named DD and the unnamed
    entries after it): they attach to it, and their order is the search order. The order of
    different DDs relative to each other is irrelevant, so each DD's entries are merged on their
    own and DDs are only laid out in a common order where one exists. A DD whose own entries are
    ordered differently by different steps gets one copy per distinct order (`split_conflicts`).
    """
    entry: dict[tuple, dict] = {}
    per_item: list[dict[str, list[tuple]]] = []
    for it in its:
        seen: dict[str, int] = {}
        blocks: dict[str, list[tuple]] = {}
        owner = ""  # the named DD an unnamed (concatenated) entry belongs to
        for dd in it.step.get("dds", []):
            owner = dd.get("name", owner)
            # Identical entries of different DDs (every concatenation lists ${CPY1}, ${CPY2}, ...)
            # must not be confused, so the key includes the DD; the n-th identical entry of a DD
            # lines up with the n-th identical entry of the same DD in the other steps.
            base = owner + "\0" + json.dumps(dd, sort_keys=True)
            n = seen.get(base, 0)
            seen[base] = n + 1
            key = (base, n)
            blocks.setdefault(owner, []).append(key)
            entry.setdefault(key, dd)
        per_item.append(blocks)
    names = merge_orders([list(b) for b in per_item])
    if names is None:  # steps lay their DDs out differently: any order will do, keep first appearance
        names = list(dict.fromkeys(n for b in per_item for n in b))
    out: list[dict] = []
    for name in names:
        having = [i for i, b in enumerate(per_item) if name in b]
        seqs = split_conflicts([per_item[i][name] for i in having])
        atoms_of: dict[tuple, set] = {}
        for i, seq in zip(having, seqs):
            for key in seq:
                atoms_of.setdefault(key, set()).update(its[i].atoms)
        order = merge_orders(seqs)
        if order is None:
            raise ConsolidationError(f"entries of DD {name or '(none)'} are ordered inconsistently")
        for key in order:
            dd, on = entry[key[:2]], atoms_of[key]
            if on == set(on_all):
                out.append(dict(dd))
                continue
            for c in conditions(space, on, set(universe - on_all)):
                out.append(_dd_with_condition(dd, and_conditions(dd.get("condition"), c)))
    return out


def _merge_slot(its: list[Item], space: Space, universe: frozenset[Atom], taken: set[str],
                reserved: set[str], notes: list[str]) -> tuple[list[dict], list[dict]]:
    """Merge one slot into a single step (+ new variables); several steps if it cannot be merged."""
    on_all = frozenset().union(*(it.atoms for it in its))
    if len(its) > 1:
        conds = conditions(space, set(on_all), set())
        if len(conds) == 1:
            try:
                return _merge_group(its, on_all, conds[0], space, universe, taken, reserved)
            except ConsolidationError as e:
                notes.append(f"{[it.step['step'] for it in its]} not merged: {e}")
        else:
            notes.append(f"{[it.step['step'] for it in its]} not merged: applies to a combination of "
                         "properties that one condition cannot express")
    # unmerged: each item keeps its own (unconsolidated) condition and name
    steps = []
    for it in its:
        st = dict(it.step)
        st["step"] = _unique(st["step"], taken)
        steps.append(with_condition(st, it.condition))
    return steps, []


def _merge_group(its, on_all, step_cond, space, universe, taken, reserved):
    name = _unique(merged_name([it.step["step"] for it in its]), taken)
    step = dict(its[0].step)
    step["step"] = name
    new_vars: list[dict] = []
    for field_ in ("parm", "command"):
        if field_ not in step:
            continue
        by_val: dict[str, set] = {}
        for it in its:
            by_val.setdefault(it.step[field_], set()).update(it.atoms)
        if len(by_val) == 1:
            continue
        fac = factor(list(by_val))
        if fac:
            prefix, suffix, mids = fac
            var = _unique(camel(name) + "Arg", reserved, "")
            values = dict(zip(by_val, mids))
            step[field_] = f"{prefix}${{{var}}}{suffix}"
        else:
            var = _unique(camel(name) + field_.capitalize(), reserved, "")
            values = {v: v for v in by_val}
            step[field_] = f"${{{var}}}"
        select = []
        for value, atoms in by_val.items():
            for c in conditions(space, atoms, set(universe - on_all)):
                if c is None:
                    raise ConsolidationError("select value without a condition")
                select.append({"condition": c, "value": values[value]})
        new_vars.append({"name": var, "select": select})
    if any("dds" in it.step for it in its):
        step["dds"] = _merge_dds(its, on_all, space, universe)
    else:
        step.pop("dds", None)
    return [with_condition(step, step_cond)], new_vars


def consolidate(items: list[Item], seqs: list[list[int]], space: Space, reserved: set[str],
                notes: list[str] | None = None) -> Result:
    """Merge `items` (in a valid run order) into fewer steps.

    `seqs` are the per-variant orders (indexes into `items`) that must be preserved;
    `reserved` are variable names already in use.
    """
    notes = [] if notes is None else notes
    universe = space.universe()
    keys = role_buckets([it.step for it in items], [it.atoms for it in items])
    slots: list[list[int]] = []
    buckets: dict[tuple, list[int]] = {}
    slot_of: dict[int, int] = {}
    for i, it in enumerate(items):
        key = keys[i]
        for s in buckets.get(key, []):
            if all(not (items[j].atoms & it.atoms) for j in slots[s]):
                slots[s].append(i)
                slot_of[i] = s
                break
        else:
            slots.append([i])
            slot_of[i] = len(slots) - 1
            buckets.setdefault(key, []).append(len(slots) - 1)
    # order the slots so that every variant's own step order is kept
    after: dict[int, set[int]] = {s: set() for s in range(len(slots))}
    indeg = {s: 0 for s in range(len(slots))}
    for seq in seqs:
        for a, b in zip(seq, seq[1:]):
            sa, sb = slot_of[a], slot_of[b]
            if sa != sb and sb not in after[sa]:
                after[sa].add(sb)
                indeg[sb] += 1
    ready = sorted((s for s, n in indeg.items() if n == 0), key=lambda x: min(slots[x]))
    order: list[int] = []
    while ready:
        s = ready.pop(0)
        order.append(s)
        for nxt in sorted(after[s]):
            indeg[nxt] -= 1
            if indeg[nxt] == 0:
                ready.append(nxt)
        ready.sort(key=lambda x: min(slots[x]))
    if len(order) != len(slots):
        raise ConsolidationError("merged steps would need to run in conflicting orders")
    taken: set[str] = set()
    steps: list[dict] = []
    variables: list[dict] = []
    for s in order:
        st, vs = _merge_slot([items[i] for i in slots[s]], space, universe, taken, reserved, notes)
        steps += st
        variables += vs
    return Result(steps, variables)
