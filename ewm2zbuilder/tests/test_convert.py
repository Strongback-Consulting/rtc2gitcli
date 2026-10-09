import json
import re
import os
from pathlib import Path

import yaml

import pytest
from jsonschema import Draft202012Validator

from ewm2zbuilder.emit import condition_obj, emit_all
from ewm2zbuilder.parser import parse
from ewm2zbuilder.resolve import check

NS = 'xmlns:ld="antlib:com.ibm.team.enterprise.zos.systemdefinition.toolkit"'

SAMPLE = f"""<?xml version="1.0"?>
<project {NS} default="all" name="exported">
 <target name="dsdefs">
  <ld:dsdef dsDefUsageType="3" dsMember="ASMA90" dsName="SYS1.ASM" name="System Assembler" prefixDSN="false"/>
  <ld:dsdef dsDefUsageType="0" dsName="PROD.COPY" name="Copybooks" prefixDSN="true"/>
  <ld:dsdef dsDefUsageType="2" dsName="" name="Temp" prefixDSN="false" primaryQuantity="5"
            secondaryQuantity="10" spaceUnits="trks" recordFormat="FB" recordLength="80" genericUnit="VIO"/>
 </target>
 <target name="translators">
  <ld:translator callMethod="0" dataSetDefinition="System Assembler" maxRC="4" name="Asm"
                 defaultOptions="XREF,&amp;OPTS" ddnamelist="SYSIN,,SYSLIB">
   <ld:concatenation name="SYSLIB">
    <ld:allocation dataSetDefinition="Copybooks"/>
    <ld:allocation propertyName="CPY1"/>
   </ld:concatenation>
   <ld:allocation dataSetDefinition="Temp" name="SYSUT1" keep="true"/>
   <ld:allocation dataSetDefinition="gone" name="SYSPRINT" publish="true"/>
   <ld:allocation input="true" name="SYSIN"/>
   <ld:variable name="OPTS" value="${{DFLT}}"/>
  </ld:translator>
  <ld:translator callMethod="1" commandMember="EXEC 'X.REXX(Y)'" maxRC="0" name="Rexx"/>
 </target>
 <target name="langdefs">
  <ld:langdef languageCode="ASM" name="Asm and Rexx" translators="Asm,Rexx"
              conditions="&lt;not&gt;&lt;isset property=&quot;ALT&quot;/&gt;&lt;/not&gt;,">
   <ld:scanner name="com.ibm.teamz.metadata.scanner.default"/>
  </ld:langdef>
 </target>
</project>
"""


def load(tmp_path: Path):
    p = tmp_path / "x.xml"
    p.write_text(SAMPLE)
    return parse(p)


def test_parse_counts_and_order(tmp_path):
    sd = load(tmp_path)
    assert (len(sd.dsdefs), len(sd.translators), len(sd.langdefs)) == (3, 2, 1)
    t = sd.translators["Asm"]
    assert t.ddname_list == ["SYSIN", "", "SYSLIB"]  # positional, empties kept
    assert [type(d).__name__ for d in t.dds] == ["Concatenation"] + ["Allocation"] * 3
    assert sd.langdefs["Asm and Rexx"].conditions[1] == ""


def test_check_flags_unresolved(tmp_path):
    issues = check(load(tmp_path))
    assert [(i.kind, i.ref) for i in issues] == [("unresolved-dsdef", "gone")]


def test_emit_step_shape(tmp_path):
    out = emit_all(load(tmp_path))
    task = yaml.safe_load(out.files["Asm_and_Rexx.yaml"])["tasks"][0]
    assert task["language"] == "Asm and Rexx"
    copy, asm, rexx = task["steps"]
    assert copy["type"] == "copy" and copy["target"] == "//'${HLQ}.ASM(${MEMBER})'"
    assert asm["pgm"] == "ASMA90" and asm["parm"] == "XREF,${OPTS}" and asm["maxRC"] == 4
    assert asm["condition"] == {"notExists": "ALT"}
    assert "condition" not in rexx and rexx["type"] == "tso"
    dds = asm["dds"]
    assert dds[0] == {"name": "TASKLIB", "dsn": "${SYSTEM_ASSEMBLER}", "options": "shr"}
    assert dds[1] == {"name": "SYSLIB", "dsn": "${HLQ}.PROD.COPY", "options": "shr"}
    # concatenation continuation: no name; property DDs are guarded by exists
    assert dds[2] == {"dsn": "${CPY1}", "condition": {"exists": "CPY1"}, "options": "shr"}
    # temp DD: no dsn, BPXWDYN options, kept for later steps
    assert "dsn" not in dds[3] and dds[3]["pass"] is True
    assert "tracks space(5,10)" in dds[3]["options"] and dds[3]["options"].endswith("new")
    assert dds[4]["log"] == "${LOGS}/${STEP}-${FILE_NAME}.log"  # publish
    assert dds[5] == {"name": "SYSIN", "dsn": "${HLQ}.ASM(${MEMBER})", "options": "shr", "input": True}
    assert {"name": "OPTS", "value": "${DFLT}"} in task["variables"]
    assert {"name": "${HLQ}.ASM"} .items() <= next(d for d in task["datasets"] if d["name"] == "${HLQ}.ASM").items()
    assert "unresolved data set definition 'gone'" in " | ".join(map(str, out.notes))
    assert out.properties == ["CPY1"]


def test_languages_yaml_collects_system_libraries(tmp_path):
    langs = yaml.safe_load(load_out(tmp_path).files["Languages.yaml"])
    assert langs["include"] == [{"file": "Asm_and_Rexx.yaml"}]
    assert langs["tasks"] == [{"stage": "Languages", "tasks": ["Asm and Rexx"]}]
    assert {"name": "SYSTEM_ASSEMBLER", "value": "SYS1.ASM"} in langs["variables"]


def load_out(tmp_path):
    return emit_all(load(tmp_path))


def test_sources_map(tmp_path):
    out = emit_all(load(tmp_path), {"ASM": ["**/asm/*.asm"]})
    task = yaml.safe_load(out.files["Asm_and_Rexx.yaml"])["tasks"][0]
    assert task["sources"] == ["**/asm/*.asm"]


def test_condition_obj():
    assert condition_obj("") == ""
    assert condition_obj('<isset property="A"/>') == {"exists": "A"}
    assert condition_obj('<not><isset property="A"/></not>') == {"notExists": "A"}
    assert condition_obj("<weird/>") is None


ROOT = Path(__file__).parent.parent
# Neither file is committed: the schema is IBM's and the export is client data.
SCHEMA = Path(os.environ.get("EWM2ZBUILDER_SCHEMA", ROOT / "schema" / "buildConfigurationSchema-v1.0.3.json"))
EXPORT = Path(os.environ.get("EWM2ZBUILDER_EXPORT", ROOT / "local" / "sysDefExport.xml"))


@pytest.mark.skipif(not SCHEMA.exists(), reason="schema not present")
def test_sample_output_matches_schema(tmp_path):
    validator = Draft202012Validator(json.loads(SCHEMA.read_text()))
    for text in load_out(tmp_path).files.values():
        assert not list(validator.iter_errors(yaml.safe_load(text)))


@pytest.mark.skipif(not (SCHEMA.exists() and EXPORT.exists()), reason="real export/schema not present")
def test_real_export_matches_schema():
    sd = parse(EXPORT)
    assert (len(sd.dsdefs), len(sd.translators), len(sd.langdefs)) == (1199, 587, 303)
    assert len(emit_all(sd, variants=False).files) == 304  # 303 languages + Languages.yaml
    out = emit_all(sd)
    folded_away = sum(len(f.members) - 1 + len(f.aliases) for f in out.families)
    assert out.families and len(out.files) == 304 - folded_away
    assert set(out.language_map) == set(sd.langdefs)
    validator = Draft202012Validator(json.loads(SCHEMA.read_text()))
    errors = [(f, e.message[:120]) for f, t in out.files.items() for e in validator.iter_errors(yaml.safe_load(t))]
    assert not errors, errors[:5]


def test_each_dd_is_one_line(tmp_path):
    for text in load_out(tmp_path).files.values():
        lines = text.splitlines()
        for i, line in enumerate(lines):
            if line == "    dds:":
                for dd in lines[i + 1:]:
                    if not dd.startswith("    - "):
                        break
                    assert dd.startswith("    - {") and dd.endswith("}"), dd


def test_unnamed_dd_dsn_aligns_with_dsn_above(tmp_path):
    text = load_out(tmp_path).files["Asm_and_Rexx.yaml"]
    lines = text.splitlines()
    named = next(l for l in lines if l.startswith("    - {name: SYSLIB"))
    unnamed = lines[lines.index(named) + 1]
    assert unnamed.startswith("    - {") and "dsn: " in unnamed
    assert unnamed.index("dsn: ") == named.index("dsn: ")
    assert yaml.safe_load(text)  # still valid YAML


def test_program_library_joins_the_translators_own_tasklib(tmp_path):
    p = tmp_path / "tasklib.xml"
    p.write_text(SAMPLE.replace(
        '<ld:allocation input="true" name="SYSIN"/>',
        '<ld:allocation input="true" name="SYSIN"/>'
        '<ld:concatenation name="TASKLIB"><ld:allocation dataSetDefinition="Copybooks"/></ld:concatenation>'))
    task = yaml.safe_load(emit_all(parse(p)).files["Asm_and_Rexx.yaml"])["tasks"][0]
    dds = next(s for s in task["steps"] if s["step"] == "Asm")["dds"]
    assert [dd.get("name") for dd in dds].count("TASKLIB") == 1
    i = next(i for i, dd in enumerate(dds) if dd.get("name") == "TASKLIB")
    assert dds[i]["dsn"] == "${SYSTEM_ASSEMBLER}" and dds[i + 1] == {"dsn": "${HLQ}.PROD.COPY", "options": "shr"}


def test_program_library_under_another_name_is_not_added_twice(tmp_path):
    p = tmp_path / "samelib.xml"
    p.write_text(SAMPLE.replace(
        '<ld:dsdef dsDefUsageType="0" dsName="PROD.COPY" name="Copybooks" prefixDSN="true"/>',
        '<ld:dsdef dsDefUsageType="0" dsName="PROD.COPY" name="Copybooks" prefixDSN="true"/>'
        '<ld:dsdef dsDefUsageType="3" dsName="SYS1.ASM" name="Assembler library" prefixDSN="false"/>').replace(
        '<ld:allocation input="true" name="SYSIN"/>',
        '<ld:allocation input="true" name="SYSIN"/>'
        '<ld:concatenation name="TASKLIB"><ld:allocation dataSetDefinition="Assembler library"/></ld:concatenation>'))
    task = yaml.safe_load(emit_all(parse(p)).files["Asm_and_Rexx.yaml"])["tasks"][0]
    dds = next(s for s in task["steps"] if s["step"] == "Asm")["dds"]
    tasklib = next(i for i, dd in enumerate(dds) if dd.get("name") == "TASKLIB")
    assert dds[tasklib]["dsn"] == "${ASSEMBLER_LIBRARY}"  # SYS1.ASM, the program's own library
    assert "${SYSTEM_ASSEMBLER}" not in [dd.get("dsn") for dd in dds]


def test_identical_duplicates_are_not_reported(tmp_path):
    line = '<ld:dsdef dsDefUsageType="0" dsName="PROD.COPY" name="Copybooks" prefixDSN="true"/>'
    p = tmp_path / "dup.xml"
    p.write_text(SAMPLE.replace(line, line + line))
    assert not parse(p).warnings
    p.write_text(SAMPLE.replace(line, line + line.replace("PROD.COPY", "OTHER.COPY")))
    assert any("different content" in w for w in parse(p).warnings)


# ---- temp dataset hand-off and TASKLIB -------------------------------------------------

HANDOFF = f"""<?xml version="1.0"?>
<project {NS} default="all" name="exported">
 <target name="dsdefs">
  <ld:dsdef dsDefUsageType="3" dsMember="PRE" dsName="SYS1.PRE" name="Precompiler" prefixDSN="false"/>
  <ld:dsdef dsDefUsageType="3" dsMember="CMP" dsName="SYS1.CMP" name="Compiler" prefixDSN="false"/>
  <ld:dsdef dsDefUsageType="3" dsName="SYS1.CMP" name="Compiler dataset" prefixDSN="false"/>
  <ld:dsdef dsDefUsageType="2" dsName="&amp;&amp;PUNCH" name="Temp punch" prefixDSN="false" primaryQuantity="5"
            secondaryQuantity="5" spaceUnits="cyls" recordFormat="FB" recordLength="80" genericUnit="VIO"/>
 </target>
 <target name="translators">
  <ld:translator callMethod="0" dataSetDefinition="Precompiler" maxRC="4" name="Pre">
   <ld:allocation dataSetDefinition="Temp punch" keep="true" name="SYSPUNCH"/>
  </ld:translator>
  <ld:translator callMethod="0" dataSetDefinition="Compiler" maxRC="4" name="Cmp">
   <ld:concatenation name="TASKLIB"><ld:allocation dataSetDefinition="Compiler dataset"/></ld:concatenation>
   <ld:allocation dataSetDefinition="Temp punch" name="SYSIN"/>
  </ld:translator>
  <ld:translator callMethod="0" dataSetDefinition="Compiler" maxRC="4" name="Lonely">
   <ld:allocation dataSetDefinition="Temp punch" name="SYSIN"/>
  </ld:translator>
 </target>
 <target name="langdefs">
  <ld:langdef languageCode="COB" name="Chain" translators="Pre,Cmp"/>
  <ld:langdef languageCode="COB" name="Alone" translators="Lonely"/>
 </target>
</project>
"""


def load_handoff(tmp_path):
    p = tmp_path / "h.xml"
    p.write_text(HANDOFF)
    return emit_all(parse(p))


def test_kept_temp_dataset_is_handed_to_the_next_step(tmp_path):
    out = load_handoff(tmp_path)
    pre, cmp_ = yaml.safe_load(out.files["Chain.yaml"])["tasks"][0]["steps"]
    producer = next(d for d in pre["dds"] if d.get("name") == "SYSPUNCH")
    assert producer["name"] == "SYSPUNCH" and producer["dsn"] == "&&PUNCH" and producer["pass"] is True
    assert producer["options"].endswith("new")
    consumer = next(d for d in cmp_["dds"] if d.get("name") == "SYSIN")
    assert consumer == {"name": "SYSIN", "dsn": "&&PUNCH", "options": "shr"}  # same temp name, not a new dataset


def test_temp_dataset_that_nobody_kept_is_a_fresh_anonymous_temp(tmp_path):
    step = yaml.safe_load(load_handoff(tmp_path).files["Alone.yaml"])["tasks"][0]["steps"][0]
    sysin = next(d for d in step["dds"] if d["name"] == "SYSIN")
    assert "dsn" not in sysin and sysin["options"].endswith("new")


def test_translator_with_its_own_tasklib_gets_no_second_one(tmp_path):
    out = load_handoff(tmp_path)
    cmp_ = yaml.safe_load(out.files["Chain.yaml"])["tasks"][0]["steps"][1]
    assert [d.get("name") for d in cmp_["dds"]].count("TASKLIB") == 1
    lonely = yaml.safe_load(out.files["Alone.yaml"])["tasks"][0]["steps"][0]
    assert [d.get("name") for d in lonely["dds"]].count("TASKLIB") == 1  # synthesized from the program library


# ---- CLI: sources-map keys ------------------------------------------------------------------------------

def test_cli_warns_about_unknown_sources_map_keys_but_not_fold_targets(tmp_path, capsys):
    from ewm2zbuilder.cli import main
    export = tmp_path / "x.xml"
    export.write_text(SAMPLE)
    (tmp_path / "sources.yaml").write_text(yaml.safe_dump({
        "Folded Task": ["**/a.asm"],  # the name of a fold target: valid
        "ASM": ["**/b.asm"],  # a language code: valid
        "Asm and Rexx": ["**/c.asm"],  # a langdef name: valid
        "Typo Task": ["**/d.asm"],  # matches nothing
    }))
    (tmp_path / "folds.yaml").write_text(yaml.safe_dump({
        "Folded Task": {"variants": {"Asm and Rexx": {"IS_CICS": True}}}}))
    rc = main([str(export), "-o", str(tmp_path / "out"), "--sources-map", str(tmp_path / "sources.yaml"),
               "--fold-map", str(tmp_path / "folds.yaml"), "--no-consolidate"])
    err = capsys.readouterr().err
    assert rc == 0
    assert "'Typo Task'" in err and "'Folded Task'" not in err and "'ASM'" not in err
    task = yaml.safe_load((tmp_path / "out" / "Folded_Task.yaml").read_text())["tasks"][0]
    assert task["sources"] == ["**/a.asm"]  # a fold target's own entry wins over the language code's


# ---- log paths -----------------------------------------------------------------------------------------

TWO_LOGS = f"""<?xml version="1.0"?>
<project {NS} default="all" name="exported">
 <target name="dsdefs">
  <ld:dsdef dsDefUsageType="3" dsMember="CC" dsName="SYS1.CC" name="Compiler" prefixDSN="false"/>
  <ld:dsdef dsDefUsageType="2" dsName="" name="Temp" prefixDSN="false" primaryQuantity="5" secondaryQuantity="5"
            spaceUnits="cyls" recordFormat="FB" recordLength="133" genericUnit="VIO"/>
 </target>
 <target name="translators">
  <ld:translator callMethod="0" dataSetDefinition="Compiler" maxRC="4" name="Cc">
   <ld:allocation dataSetDefinition="Temp" name="SYSCPRT" publish="true"/>
   <ld:allocation dataSetDefinition="Temp" name="SYSUT1"/>
   <ld:allocation dataSetDefinition="Temp" name="SYSOUT" publish="true"/>
   <ld:allocation dataSetDefinition="Temp" name="SYSDUMP" publish="true"/>
  </ld:translator>
 </target>
 <target name="langdefs"><ld:langdef languageCode="C" name="Two logs" translators="Cc"/></target>
</project>
"""


def test_published_dds_of_one_step_get_distinct_log_files(tmp_path):
    p = tmp_path / "t.xml"
    p.write_text(TWO_LOGS)
    step = yaml.safe_load(emit_all(parse(p)).files["Two_logs.yaml"])["tasks"][0]["steps"][-1]
    logs = {d["name"]: d["log"] for d in step["dds"] if "log" in d}
    assert logs == {
        "SYSCPRT": "${LOGS}/${STEP}-${FILE_NAME}.log",  # the first keeps the plain step log, as in the IBM samples
        "SYSOUT": "${LOGS}/${STEP}-SYSOUT-${FILE_NAME}.log",
        "SYSDUMP": "${LOGS}/${STEP}-SYSDUMP-${FILE_NAME}.log",
    }
    assert all(d["logEncoding"] == "${LOG_ENCODING}" for d in step["dds"] if "log" in d)


# ---- the real export: every folded task, checked generically (no client details in the assertions) -------

from ewm2zbuilder.consolidate import Space, expand, verify  # noqa: E402


def _tasks(out):
    tasks = {}
    for text in out.files.values():
        for task in yaml.safe_load(text).get("tasks", []):
            if "language" in task:
                tasks[task["language"]] = task
    return tasks


@pytest.fixture(scope="module")
def real():
    if not EXPORT.exists():
        pytest.skip("real export not present")
    sd = parse(EXPORT)
    return sd, emit_all(sd), emit_all(sd, consolidate=False), emit_all(sd, variants=False)


def test_every_consolidated_task_is_equivalent_to_its_unconsolidated_form(real):
    _, merged, flat, _ = real
    merged_tasks, flat_tasks = _tasks(merged), _tasks(flat)
    for fam in merged.families:
        task, plain = merged_tasks[fam.task], flat_tasks[fam.task]
        props = sorted({c[k] if isinstance(c[k], str) else c[k][0]
                        for s in plain["steps"] for c in [s.get("condition")] if isinstance(c, dict)
                        for k in ("exists", "notExists") if k in c})
        selects = [v for v in task.get("variables", []) if "select" in v]
        assert verify(plain["steps"], task["steps"], selects, Space(tuple(fam.features), tuple(props))) == [], fam.task
    assert sum(len(t["steps"]) for t in merged_tasks.values()) < sum(len(t["steps"]) for t in flat_tasks.values())


def _step_properties(task) -> list[str]:
    """Build properties that steps (not just DDs) depend on, e.g. an alternate-compiler switch."""
    names = set()
    for step in task["steps"]:
        cond = step.get("condition")
        if isinstance(cond, dict):
            for key in ("exists", "notExists"):
                names.update([cond[key]] if isinstance(cond.get(key), str) else cond.get(key, []))
    return sorted(names)


def _resolved(task, env) -> list[dict]:
    """The task's steps with plain and selected variables substituted for one flag/property combination."""
    from ewm2zbuilder.consolidate import _holds
    values = {}
    for v in task.get("variables", []):
        if "select" in v:
            for c in v["select"]:
                ok, rest = _holds(c.get("condition"), env)
                if ok and rest is None:
                    values[v["name"]] = c["value"]
                    break
        elif "value" in v and v["name"] not in env:
            values[v["name"]] = v["value"]
    text = json.dumps(task["steps"])
    for _ in range(5):
        text = re.sub(r"\$\{(\w+)\}", lambda m: json.dumps(values[m.group(1)])[1:-1]
                      if m.group(1) in values else m.group(0), text)
    return json.loads(text)


def test_every_variant_runs_what_its_language_definition_runs(real):
    """For each member of each folded task: the folded task with the member's flags runs what the member's own
    (unfolded) task runs, for every value of the properties its steps depend on."""
    import itertools
    _, merged, flat, single = real
    merged_tasks, flat_tasks, single_tasks = _tasks(merged), _tasks(flat), _tasks(single)
    checked = 0
    for name, entry in merged.language_map.items():
        flags = entry["variables"]
        if not flags:
            continue
        mine, own = merged_tasks[entry["task"]], single_tasks[name]
        # every property a step of the family depends on (the merge may move it onto DDs)
        props = sorted(set(_step_properties(flat_tasks[entry["task"]])) | set(_step_properties(own)))
        space = Space(tuple(flags), tuple(props))
        for pv in itertools.product((False, True), repeat=len(props)):
            atom = tuple(flags.values()) + pv
            env = dict(zip(space.names, atom))
            assert expand(_resolved(mine, env), [], space, atom) == expand(_resolved(own, env), [], space, atom), \
                (name, dict(zip(props, pv)))
        checked += 1
    assert checked >= 2 * len(merged.families)


@pytest.mark.skipif(not SCHEMA.exists(), reason="schema not present")
def test_consolidated_real_output_matches_schema(real):
    validator = Draft202012Validator(json.loads(SCHEMA.read_text()))
    _, merged, _, _ = real
    errors = [(f, e.message[:120]) for f, t in merged.files.items() for e in validator.iter_errors(yaml.safe_load(t))]
    assert not errors, errors[:5]


def test_no_step_in_the_whole_export_has_two_dds_writing_the_same_log(real):
    _, _, flat, _ = real  # one step per translator: every DD is active
    colliding = []
    for fname, text in flat.files.items():
        for task in yaml.safe_load(text).get("tasks", []):
            for step in task.get("steps", []):
                paths = [d["log"] for d in step.get("dds", []) if "log" in d]
                if len(paths) != len(set(paths)):
                    colliding.append((fname, step["step"]))
    assert colliding == []
