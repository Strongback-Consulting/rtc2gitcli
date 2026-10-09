"""The merge engine of folded tasks (`consolidate.py`, from the estimation project): conditions, step and DD
merging, temp-space unification and the equivalence check. Synthetic data only."""

import pytest

from ewm2zbuilder.consolidate import (
    Item, Space, and_conditions, conditions, conflicting_keys, consolidate, expand, factor, merge_orders,
    merged_name, role_buckets, split_conflicts, unify_temp_space, verify, with_condition,
)
from ewm2zbuilder.emit import _fold_spec, _merge_orders, _predicate

FLAGS = ["IS_CICS", "IS_SQL"]


def test_predicate_simplifies_to_minimal_guard():
    both = [(True, False), (True, True)]
    assert _predicate(both, FLAGS) == "${IS_CICS}"
    assert _predicate([(False, True), (True, True)], FLAGS) == "${IS_SQL}"
    assert _predicate([(False, False), (True, False)], FLAGS) == "${IS_SQL} != true"
    assert _predicate([(True, False)], FLAGS) == "${IS_CICS} && ${IS_SQL} != true"
    assert _predicate([(a, b) for a in (False, True) for b in (False, True)], FLAGS) == ""
    # non-adjacent combos stay as an OR of ANDs
    assert _predicate([(False, False), (True, True)], FLAGS) == (
        "(${IS_CICS} && ${IS_SQL}) || (${IS_CICS} != true && ${IS_SQL} != true)"
    )


def test_merge_orders_keeps_each_sequence_and_shares_nodes():
    assert _merge_orders([["a", "x", "b"], ["c", "x", "d"]]) == ["a", "c", "x", "b", "d"]
    assert _merge_orders([["a", "b"], ["b", "a"]]) is None  # conflict


SPACE = Space(("IS_CICS", "IS_SQL"), ("ALT",))


def atoms(*rows):
    return {tuple(bool(v) for v in r) for r in rows}


def test_conditions_use_dont_cares_to_simplify():
    uni = SPACE.universe()
    on = {a for a in uni if a[0] and not a[1]}  # IS_CICS and not IS_SQL, any ALT
    assert conditions(SPACE, on, set()) == ["${IS_CICS} && ${IS_SQL} != true"]
    # if the step can only ever run when IS_SQL is false, IS_CICS alone is enough
    assert conditions(SPACE, on, {a for a in uni if a[1]}) == ["${IS_CICS}"]
    assert conditions(SPACE, set(), set()) == []
    assert conditions(SPACE, set(uni), set()) == [None]  # always


def test_conditions_express_properties_with_exists():
    on = {a for a in SPACE.universe() if not a[2]}  # ALT unset, any variant
    assert conditions(SPACE, on, set()) == [{"notExists": "ALT"}]
    both = {a for a in SPACE.universe() if a[2] and a[0]}  # ALT set and IS_CICS
    assert conditions(SPACE, both, set()) == [{"exists": "ALT", "eval": "${IS_CICS}"}]
    # different variant guards per ALT value cannot be one cube: one disjoint alternative each
    mixed = {a for a in SPACE.universe() if (a[2] and a[0]) or (not a[2] and not a[0])}
    alts = conditions(SPACE, mixed, set())
    assert alts == [{"notExists": "ALT", "eval": "${IS_CICS} != true"}, {"exists": "ALT", "eval": "${IS_CICS}"}]


def test_and_conditions_combines_exists_and_eval():
    assert and_conditions(None, "${A}") == "${A}"
    assert and_conditions({"exists": "CPY1"}, "${A}") == {"exists": "CPY1", "eval": "${A}"}
    assert and_conditions({"exists": "CPY1"}, {"exists": "ALT", "eval": "${A}"}) == {
        "exists": ["CPY1", "ALT"], "eval": "${A}"}


def test_factor_splits_on_token_boundaries_and_skips_pure_punctuation():
    cmds = ["EXEC 'X(P)' 'a,BAT,b'", "EXEC 'X(P)' 'a,ONL,b'"]
    assert factor(cmds) == ("EXEC 'X(P)' 'a,", ",b'", ["BAT", "ONL"])
    assert factor(["${A},SSI(@{s})", "${B},SSI(@{s})"]) == ("", ",SSI(@{s})", ["${A}", "${B}"])
    assert factor(["${A}", "${B}"]) is None
    assert factor(["${A})", "${B})"]) is None  # only ")" is shared


def test_merged_name_keeps_the_words_all_names_share():
    assert merged_name(["COBOL Compile current", "COBOL DB2 CICS Compile alternate"]) == "COBOL Compile"
    assert merged_name(["SCAN - BATCH update", "SCAN - ONLINE update"]) == "SCAN update"
    assert merged_name(["Alpha", "Beta"]) == "Alpha"


def test_split_conflicts_separates_only_disagreeing_orders():
    a, b, x, y = [(k, 0) for k in "abxy"]
    seqs = [[a, x, y, b], [a, y, x, b], [a, x, y, b]]
    assert merge_orders(seqs) is None and conflicting_keys(seqs) == {x, y}
    out = split_conflicts(seqs)
    order = merge_orders(out)
    assert order is not None
    # sequences 1 and 3 agree, so they still share one copy of x and y; sequence 2 gets its own
    assert out[0] == out[2] and out[0] != out[1]
    assert len([k for k in order if k[0] in ("x", "y")]) == 4


def _mvs(name, dds, pgm="IGYCRCTL"):
    return {"step": name, "type": "mvs", "pgm": pgm, "maxRC": 4, "dds": dds}


def _tmp(name, space, extra="recfm(f,b) lrecl(80) unit(vio) new", unit="cyl"):
    return {"name": name, "options": f"{unit} space({space}) {extra}"}


def test_unify_temp_space_uses_the_largest_space_for_matching_dds():
    a = _mvs("Compile", [_tmp("SYSUT1", "5,10"), _tmp("SYSUT8", "5,10"), _tmp("SYSMDECK", "5,10")])
    b = _mvs("CICS Compile", [_tmp("SYSUT1", "5,10"), _tmp("SYSUT8", "20,20"), _tmp("SYSMDECK", "20,20")])
    assert unify_temp_space([a, b], ["SYSUT*"]) == []
    assert a["dds"][0]["options"] == b["dds"][0]["options"] == "cyl space(5,10) recfm(f,b) lrecl(80) unit(vio) new"
    assert a["dds"][1]["options"] == b["dds"][1]["options"] == "cyl space(20,20) recfm(f,b) lrecl(80) unit(vio) new"
    # a name that does not match the pattern keeps its own space
    assert a["dds"][2]["options"].startswith("cyl space(5,10)")
    assert b["dds"][2]["options"].startswith("cyl space(20,20)")


def test_unify_temp_space_takes_the_larger_of_each_quantity():
    a, b = _mvs("A", [_tmp("SYSUT1", "5,20")]), _mvs("B", [_tmp("SYSUT1", "20,5")])
    unify_temp_space([a, b], ["SYSUT1"])
    assert a["dds"][0]["options"].startswith("cyl space(20,20) ") and b["dds"][0] == a["dds"][0]


def test_unify_temp_space_leaves_alone_what_differs_in_more_than_space():
    a = _mvs("A", [_tmp("SYSUT1", "5,10")])
    b = _mvs("B", [_tmp("SYSUT1", "20,20", extra="recfm(f,b) lrecl(133) unit(vio) new")])  # different lrecl
    c = _mvs("C", [_tmp("SYSUT2", "5,10")])
    d = _mvs("D", [_tmp("SYSUT2", "20,20", unit="tracks")])  # different space unit
    msgs = unify_temp_space([a, b, c, d], ["SYSUT*"])
    assert len(msgs) == 2 and "SYSUT1" in msgs[0] and "SYSUT2" in msgs[1]
    assert a["dds"][0]["options"].startswith("cyl space(5,10)")
    assert d["dds"][0]["options"].startswith("tracks space(20,20)")


def test_unify_temp_space_ignores_named_temps_other_roles_and_permanent_datasets():
    named = {"name": "SYSUT1", "dsn": "&&KEEP", "options": "cyl space(5,10) new", "pass": True}
    perm = {"name": "SYSUT2", "dsn": "${HLQ}.X", "options": "shr"}
    other_pgm = _mvs("Link", [_tmp("SYSUT1", "50,50")], pgm="IEWL")
    a = _mvs("A", [named, perm, _tmp("SYSUT3", "5,10")])
    b = _mvs("B", [dict(named, options="cyl space(20,20) new"), perm, _tmp("SYSUT3", "7,7")])
    unify_temp_space([a, b, other_pgm], ["SYSUT*"])
    assert a["dds"][0]["options"] == "cyl space(5,10) new" and b["dds"][0]["options"] == "cyl space(20,20) new"
    assert a["dds"][2]["options"].startswith("cyl space(7,10)")  # only the same-role pair is unified
    assert other_pgm["dds"][0]["options"].startswith("cyl space(50,50)")


def test_fold_spec_shapes(tmp_path):
    legacy = {"A": {"IS_CICS": True}}
    assert _fold_spec("t", legacy) == (legacy, [])
    assert _fold_spec("t", {"variants": legacy, "unify_temp_space": "SYSUT*"}) == (legacy, ["SYSUT*"])
    with pytest.raises(ValueError, match="unknown key"):
        _fold_spec("t", {"variants": legacy, "unify_temp_spce": ["SYSUT*"]})


ONE_FLAG = Space(("IS_CICS",), ())


def _step(name, dds, pgm="P", parm="X"):
    return {"step": name, "type": "mvs", "pgm": pgm, "parm": parm, "maxRC": 4, "dds": dds}


def _variant_items(*steps_and_flags):
    """Items for steps that each run in one IS_CICS value (False / True)."""
    items = []
    for step, cics in steps_and_flags:
        items.append(Item(step, frozenset({(cics,)}), "${IS_CICS}" if cics else "${IS_CICS} != true"))
    return items


def _merged(items, space=ONE_FLAG, seqs=None):
    res = consolidate(items, seqs or [[i] for i in range(len(items))], space, set())
    original = [with_condition(it.step, it.condition) for it in items]
    return res, verify(original, res.steps, res.variables, space)


def test_dds_are_merged_per_block_so_block_order_and_lookalike_entries_do_not_matter():
    # CPY1 is listed under several DDs; one variant has an extra block (MM1) and lists SYSLIN/SYSPRINT
    # the other way round. Nothing here is a real conflict, so nothing may be duplicated.
    a = _step("A", [{"name": "SYSLIB", "dsn": "L"}, {"dsn": "${CPY1}"}, {"name": "DD1", "dsn": "D"}, {"dsn": "${CPY1}"},
                    {"name": "SYSLIN", "dsn": "S"}, {"name": "SYSPRINT", "options": "p"}])
    b = _step("B", [{"name": "SYSLIB", "dsn": "L"}, {"dsn": "${CPY1}"}, {"name": "MM1", "dsn": "M"}, {"dsn": "${CPY1}"},
                    {"name": "DD1", "dsn": "D"}, {"dsn": "${CPY1}"},
                    {"name": "SYSPRINT", "options": "p"}, {"name": "SYSLIN", "dsn": "S"}])
    res, problems = _merged(_variant_items((a, False), (b, True)))
    assert problems == []
    (step,) = res.steps
    assert len(step["dds"]) == 8  # SYSLIB(2) DD1(2) MM1(2) SYSLIN SYSPRINT
    mm1 = [d for d in step["dds"] if d.get("condition")]
    assert len(mm1) == 2 and all(d["condition"] == "${IS_CICS}" for d in mm1)  # only MM1's entries are guarded


def test_a_real_order_conflict_inside_one_dd_still_gets_one_copy_per_order():
    a = _step("A", [{"name": "X", "dsn": "a"}, {"dsn": "b"}, {"dsn": "c"}])
    b = _step("B", [{"name": "X", "dsn": "a"}, {"dsn": "c"}, {"dsn": "b"}])
    res, problems = _merged(_variant_items((a, False), (b, True)))
    assert problems == []
    (step,) = res.steps
    assert [d["dsn"] for d in step["dds"]].count("b") == 2 and [d["dsn"] for d in step["dds"]].count("c") == 2
    assert [d["dsn"] for d in step["dds"]].count("a") == 1  # what agrees stays shared


def test_expand_ignores_the_order_of_different_dds_but_not_which_dd_an_entry_belongs_to():
    base = {"step": "s", "type": "mvs", "pgm": "P", "maxRC": 0}
    ab = dict(base, dds=[{"name": "A", "dsn": "1"}, {"dsn": "2"}, {"name": "B", "dsn": "3"}])
    ba = dict(base, dds=[{"name": "B", "dsn": "3"}, {"name": "A", "dsn": "1"}, {"dsn": "2"}])
    moved = dict(base, dds=[{"name": "A", "dsn": "1"}, {"name": "B", "dsn": "3"}, {"dsn": "2"}])  # "2" now follows B
    reordered = dict(base, dds=[{"name": "A", "dsn": "1"}, {"dsn": "9"}, {"name": "B", "dsn": "3"}])
    sp = ONE_FLAG
    assert expand([ab], [], sp, (False,)) == expand([ba], [], sp, (False,))
    assert expand([ab], [], sp, (False,)) != expand([moved], [], sp, (False,))
    assert expand([ab], [], sp, (False,)) != expand([reordered], [], sp, (False,))
    # ...and the order inside a DD matters
    swapped = dict(base, dds=[{"name": "A", "dsn": "1"}, {"dsn": "2"}, {"dsn": "4"}])
    swapped2 = dict(base, dds=[{"name": "A", "dsn": "1"}, {"dsn": "4"}, {"dsn": "2"}])
    assert expand([swapped], [], sp, (False,)) != expand([swapped2], [], sp, (False,))


def _precompile_and_compile_items():
    batch_compile = _step("PLI compile update", [{"name": "SYSIN", "dsn": "src"}], pgm="IBMZPLI", parm="+DD:PLIOPTNS ${PLICMPOPTS}")
    db2_pre = _step("PLI Precompile update", [{"name": "SYSIN", "dsn": "src"}, {"name": "SYSPUNCH", "dsn": "&&X"}],
                    pgm="IBMZPLI", parm="MACRO,NOSYNTAX")
    db2_compile = _step("PLI DB2 compiler update", [{"name": "SYSIN", "dsn": "&&X"}], pgm="IBMZPLI",
                        parm="+DD:PLIOPTNS ${PLIDB2CMPOPTS}")
    sql = Space(("IS_SQL",), ())
    items = [
        Item(batch_compile, frozenset({(False,)}), "${IS_SQL} != true"),
        Item(db2_pre, frozenset({(True,)}), "${IS_SQL}"),
        Item(db2_compile, frozenset({(True,)}), "${IS_SQL}"),
    ]
    return items, sql


def test_one_program_used_twice_in_a_variant_keeps_precompile_and_compile_apart():
    items, sql = _precompile_and_compile_items()
    res, problems = _merged(items, sql, seqs=[[0], [1, 2]])
    assert problems == []
    # the DB2 variant runs the precompile first, so the precompile step precedes the merged compile
    assert [s["step"] for s in res.steps] == ["PLI Precompile update", "PLI compile update"]
    parms = sorted(s["parm"] for s in res.steps)
    assert parms == ["+DD:PLIOPTNS ${pliCompileUpdateArg}", "MACRO,NOSYNTAX"]  # compile merged (select), precompile alone
    pre = next(s for s in res.steps if s["parm"] == "MACRO,NOSYNTAX")
    assert pre["condition"] == "${IS_SQL}"
    assert len(res.steps) == 2 and len(res.variables) == 1


def test_steps_run_in_opposite_orders_merge_as_far_as_the_orders_allow():
    # one variant builds the online module first, the other the batch module: merging both pairs of steps
    # would need conflicting orders, so the first pair is merged and the second step of the other order stays apart
    online, batch = _step("Online", [{"name": "SYSLIB", "dsn": "O"}], pgm="ON"), _step("Batch", [], pgm="BA")
    items = _variant_items((online, False), (batch, False), (dict(batch), True), (dict(online), True))
    res, problems = _merged(items, seqs=[[0, 1], [2, 3]])
    assert problems == []
    assert [(s["step"], s.get("condition")) for s in res.steps] == [
        ("Online", "${IS_CICS} != true"), ("Batch", None), ("Online 2", "${IS_CICS}")]


def test_role_buckets_only_refine_when_steps_of_a_role_overlap():
    items, _ = _precompile_and_compile_items()
    steps, atoms = [i.step for i in items], [i.atoms for i in items]
    keys = role_buckets(steps, atoms)
    assert keys[0] == keys[2] != keys[1]  # the two compiles are one kind, the precompile another
    assert len(set(role_buckets(steps, None))) == 1  # without overlap information a role is just the program


def test_unify_temp_space_does_not_mix_the_precompile_into_the_compile():
    items, _ = _precompile_and_compile_items()
    items[0].step["dds"].append(_tmp("SYSUT1", "5,10"))
    items[1].step["dds"].append(_tmp("SYSUT1", "50,50"))  # the precompile asks for a lot
    items[2].step["dds"].append(_tmp("SYSUT1", "20,20"))
    steps, atoms = [i.step for i in items], [i.atoms for i in items]
    unify_temp_space(steps, ["SYSUT*"], atoms)
    sizes = [next(d for d in s["dds"] if d["name"] == "SYSUT1")["options"].split(" recfm")[0] for s in steps]
    assert sizes == ["cyl space(20,20)", "cyl space(50,50)", "cyl space(20,20)"]  # compiles unified, precompile apart


def test_merged_name_tolerates_word_endings():
    assert merged_name(["PLI compile update", "PLI DB2 compiler update", "PLI compile ALT"]) == "PLI compile"
    assert merged_name(["PLI Precompile update", "PLI Precompiler DB2 CICS"]) == "PLI Precompile"
