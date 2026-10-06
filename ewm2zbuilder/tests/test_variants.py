"""Variants of one compiler (batch, CICS, DB2, CICS+DB2) become one zBuilder task."""

import json
import os
from pathlib import Path

import pytest
import yaml
from jsonschema import Draft202012Validator

from ewm2zbuilder.emit import emit_all
from ewm2zbuilder.parser import parse
from ewm2zbuilder.variants import condition_for, family_key, features, find_families, merge_sequences

NS = 'xmlns:ld="antlib:com.ibm.team.enterprise.zos.systemdefinition.toolkit"'


def translator(name, pgm_dsdef, options, dds="", variables=""):
    return (f'<ld:translator callMethod="0" dataSetDefinition="{pgm_dsdef}" maxRC="4" name="{name}" '
            f'defaultOptions="{options}">{dds}{variables}</ld:translator>')


SYSLIB = '<ld:allocation dataSetDefinition="Copybooks" name="SYSLIB"/>'
CICSLIB = '<ld:allocation dataSetDefinition="CICS macros"/>'

VARIANTS = f"""<?xml version="1.0"?>
<project {NS} default="all" name="exported">
 <target name="dsdefs">
  <ld:dsdef dsDefUsageType="3" dsMember="IGYCRCTL" dsName="SYS1.COBOL" name="Cobol compiler" prefixDSN="false"/>
  <ld:dsdef dsDefUsageType="3" dsMember="DFHECP1$" dsName="CICS.LOAD" name="CICS translator" prefixDSN="false"/>
  <ld:dsdef dsDefUsageType="3" dsMember="DSNHPC" dsName="DB2.LOAD" name="Db2 precompiler" prefixDSN="false"/>
  <ld:dsdef dsDefUsageType="3" dsMember="IEWL" dsName="SYS1.LINKLIB" name="Binder" prefixDSN="false"/>
  <ld:dsdef dsDefUsageType="0" dsName="COPY" name="Copybooks" prefixDSN="true"/>
  <ld:dsdef dsDefUsageType="3" dsName="CICS.SDFHCOB" name="CICS macros" prefixDSN="false"/>
 </target>
 <target name="translators">
  {translator("Compile batch", "Cobol compiler", "&amp;BATOPTS",
              f'<ld:concatenation name="SYSLIB"><ld:allocation dataSetDefinition="Copybooks"/></ld:concatenation>',
              '<ld:variable name="BATOPTS" value="LIB"/>')}
  {translator("Compile CICS", "Cobol compiler", "&amp;CICSOPTS",
              f'<ld:concatenation name="SYSLIB"><ld:allocation dataSetDefinition="Copybooks"/>{CICSLIB}'
              f'</ld:concatenation>', '<ld:variable name="CICSOPTS" value="LIB,RENT"/>')}
  {translator("CICS translate", "CICS translator", "CICS,SP")}
  {translator("Db2 precompile", "Db2 precompiler", "HOST(IBMCOB)")}
  {translator("Link", "Binder", "LIST")}
  {translator("Link other", "Binder", "MAP")}
 </target>
 <target name="langdefs">
  <ld:langdef languageCode="COB" name="Cobol Compile and Link" translators="Compile batch,Link"/>
  <ld:langdef languageCode="COB" name="Cobol CICS Compile and Link" translators="CICS translate,Compile CICS,Link"/>
  <ld:langdef languageCode="COB" name="Cobol DB2 Compile and Link" translators="Db2 precompile,Compile batch,Link"/>
  <ld:langdef languageCode="COB" name="Cobol DB2 CICS Compile and Link"
              translators="Db2 precompile,CICS translate,Compile CICS,Link"/>
  <ld:langdef languageCode="COB" name="Cobol Compile and Link - Fetch" translators="Compile batch,Link other"/>
  <ld:langdef languageCode="COB" name="Cobol Compile" translators="Compile batch"/>
  <ld:langdef languageCode="COB" name="Copybook" translators=""/>
 </target>
</project>
"""


@pytest.fixture
def sd(tmp_path):
    p = tmp_path / "variants.xml"
    p.write_text(VARIANTS)
    return parse(p)


def test_family_key_ignores_feature_words():
    assert family_key("Cobol DB2 CICS Compile and Link") == "cobol compile"
    assert family_key("JKE COBOL compilation (CICS&DB2) and link-edit") == "jke cobol compilation"
    assert family_key("JKE COBOL compilation (no CICS)") == "jke cobol compilation"
    assert family_key("JKE COBOL compilation") == "jke cobol compilation"
    assert family_key("HOGN Assembler BATCHPEM and CICSPEM and Link") == "hogn assembler batchpem and cicspem"


def test_features_from_programs_and_names(sd):
    assert features(sd, sd.langdefs["Cobol CICS Compile and Link"]) == \
        {"IS_CICS": True, "IS_SQL": False, "doLinkEdit": True}
    assert features(sd, sd.langdefs["Cobol DB2 Compile and Link"]) == \
        {"IS_CICS": False, "IS_SQL": True, "doLinkEdit": True}
    assert features(sd, sd.langdefs["Cobol Compile"]) == {"IS_CICS": False, "IS_SQL": False, "doLinkEdit": False}


def test_four_variants_form_one_family(sd):
    families, notes = find_families(sd)
    assert len(families) == 1 and not notes
    fam = families[0]
    assert fam.task == "Cobol Compile and Link"
    assert fam.features == ["IS_CICS", "IS_SQL", "doLinkEdit"]
    assert fam.members["Cobol DB2 CICS Compile and Link"] == {"IS_CICS": True, "IS_SQL": True, "doLinkEdit": True}
    assert fam.members["Cobol Compile"] == {"IS_CICS": False, "IS_SQL": False, "doLinkEdit": False}
    assert "Cobol Compile and Link - Fetch" not in fam.members  # different name: its own task


def test_condition_for(sd):
    fam = find_families(sd)[0][0]
    combos = {fam.values(m) for m in fam.members}
    assert condition_for(combos, fam) is None
    assert condition_for({(True, False, True), (True, True, True)}, fam) == "${IS_CICS}"
    assert condition_for({(False, True, True)}, fam) == "${IS_CICS} != true && ${IS_SQL}"
    assert condition_for({(True, False, True), (False, True, True), (True, True, True)}, fam) == \
        "${IS_CICS} || ${IS_SQL}"
    assert condition_for(combos - {(False, False, False)}, fam) == "${doLinkEdit}"


def test_merge_sequences_keeps_each_order():
    slots = merge_sequences({"a": ["C", "L"], "b": ["T", "C", "L"], "c": ["P", "T", "C", "L"]})
    assert [sorted(s) for s in slots] == [["c"], ["b", "c"], ["a", "b", "c"], ["a", "b", "c"]]


def test_merged_task(sd):
    out = emit_all(sd)
    task = yaml.safe_load(out.files["Cobol_Compile_and_Link.yaml"])["tasks"][0]
    assert task["language"] == "Cobol Compile and Link"
    assert {"name": "IS_CICS", "value": False} in task["variables"]
    assert {"name": "doLinkEdit", "value": True} in task["variables"]
    steps = {s["step"]: s for s in task["steps"]}
    assert steps["Db2 precompile"]["condition"] == "${IS_SQL}"
    assert steps["CICS translate"]["condition"] == "${IS_CICS}"
    assert steps["Link"]["condition"] == "${doLinkEdit}"  # compile-only variant skips the binder
    # one compile step: options chosen per variant, CICS library only for CICS variants
    compile_step = steps["Compile batch"]
    assert compile_step["parm"] == "${COMPILE_BATCH_PARMS}"
    select = next(v for v in task["variables"] if v["name"] == "COMPILE_BATCH_PARMS")["select"]
    assert {"condition": "${IS_CICS}", "value": "${CICSOPTS}"} in select
    assert {"condition": "${IS_CICS} != true", "value": "${BATOPTS}"} in select
    assert compile_step["dds"][0]["name"] == "TASKLIB" and compile_step["dds"][1]["name"] == "SYSLIB"
    assert compile_step["dds"][2] == {"dsn": "${CICS_MACROS}", "options": "shr", "condition": "${IS_CICS}"}
    assert "Compile CICS" not in steps
    assert out.files["Cobol_Compile_and_Link.yaml"].startswith("# One task for these EWM language definitions")
    langs = yaml.safe_load(out.files["Languages.yaml"])["tasks"][0]["tasks"]
    assert langs.count("Cobol Compile and Link") == 1 and "Cobol CICS Compile and Link" not in langs


def test_language_map(sd):
    out = emit_all(sd)
    entry = out.language_map["Cobol DB2 CICS Compile and Link"]
    assert entry["task"] == "Cobol Compile and Link"
    assert entry["variables"] == {"IS_CICS": True, "IS_SQL": True, "doLinkEdit": True}
    assert {"IS_CICS", "BATOPTS", "CICSOPTS", "COMPILE_BATCH_PARMS"} <= set(entry["taskVariables"])
    assert out.language_map["Cobol Compile and Link - Fetch"]["variables"] == {}
    assert out.language_map["Copybook"]["task"] == "Copybook"


def test_without_variants_every_langdef_is_a_task(sd):
    out = emit_all(sd, variants=False)
    assert len(out.files) == 1 + len(sd.langdefs)
    assert out.language_map["Cobol CICS Compile and Link"]["task"] == "Cobol CICS Compile and Link"


def test_same_features_are_not_merged(tmp_path):
    p = tmp_path / "dup.xml"
    p.write_text(VARIANTS.replace(
        '<ld:langdef languageCode="COB" name="Copybook" translators=""/>',
        '<ld:langdef languageCode="COB" name="Cobol CICS Compile and Link (copy)" '
        'translators="CICS translate,Compile CICS,Link"/>').replace(
        'name="Cobol CICS Compile and Link (copy)"', 'name="Cobol Compile and CICS Link"'))
    families, notes = find_families(parse(p))
    assert "Cobol CICS Compile and Link" not in families[0].members
    assert any("same CICS/SQL/link-edit features" in n for n in notes)


ROOT = Path(__file__).parent.parent
SCHEMA = Path(os.environ.get("EWM2ZBUILDER_SCHEMA", ROOT / "schema" / "buildConfigurationSchema-v1.0.3.json"))


@pytest.mark.skipif(not SCHEMA.exists(), reason="schema not present")
def test_merged_task_matches_schema(sd):
    validator = Draft202012Validator(json.loads(SCHEMA.read_text()))
    for text in emit_all(sd).files.values():
        assert not list(validator.iter_errors(yaml.safe_load(text)))
