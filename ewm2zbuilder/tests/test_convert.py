import json
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
    out = emit_all(sd)
    assert len(out.files) == 304  # 303 languages + Languages.yaml
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
