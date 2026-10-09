"""Variants of one compiler (batch, CICS, DB2, CICS+DB2, with and without link-edit) become one zBuilder task."""

import json
import os
from pathlib import Path

import pytest
import yaml
from jsonschema import Draft202012Validator

from ewm2zbuilder.consolidate import Space, expand
from ewm2zbuilder.emit import emit_all
from ewm2zbuilder.parser import parse
from ewm2zbuilder.variants import family_key, features, find_families

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


def _runs(task: dict, flags: dict) -> list:
    """What a task runs for one variant (select variables resolved, step names ignored)."""
    names = tuple(flags)
    return expand(task["steps"], task.get("variables", []), Space(names, ()), tuple(flags[n] for n in names))


def test_merged_task_runs_what_each_variant_runs(sd):
    merged = emit_all(sd)
    single = emit_all(sd, variants=False)
    task = yaml.safe_load(merged.files["Cobol_Compile_and_Link.yaml"])["tasks"][0]
    for member, flags in merged.language_map.items():
        if merged.language_map[member]["task"] != "Cobol Compile and Link":
            continue
        own = yaml.safe_load(single.files[f"{member.replace(' ', '_').replace('-', '_')}.yaml".replace('__', '_')])
        assert _runs(task, flags["variables"]) == _runs(own["tasks"][0], flags["variables"]), member


def test_merged_task(sd):
    out = emit_all(sd)
    task = yaml.safe_load(out.files["Cobol_Compile_and_Link.yaml"])["tasks"][0]
    assert task["language"] == "Cobol Compile and Link"
    assert {"name": "IS_CICS", "value": False} in task["variables"]
    assert {"name": "doLinkEdit", "value": True} in task["variables"]
    steps = {s["step"]: s for s in task["steps"]}
    assert "${IS_SQL}" in steps["Db2 precompile"]["condition"]
    assert "${IS_CICS}" in steps["CICS translate"]["condition"]
    assert steps["Link"]["condition"] == "${doLinkEdit}"  # compile-only variant skips the binder
    # one compile step: options chosen per variant, CICS library only for CICS variants
    compiles = [s for s in task["steps"] if s.get("pgm") == "IGYCRCTL"]
    assert len(compiles) == 1
    parm = next(v for v in task["variables"] if "${" + v["name"] + "}" == compiles[0]["parm"])
    assert {"condition": "${IS_CICS}", "value": "${CICSOPTS}"} in parm["select"]
    assert compiles[0]["dds"][0]["name"] == "TASKLIB" and compiles[0]["dds"][1]["name"] == "SYSLIB"
    assert {"dsn": "${CICS_MACROS}", "options": "shr", "condition": "${IS_CICS}"} in compiles[0]["dds"]
    assert out.files["Cobol_Compile_and_Link.yaml"].startswith("# One task for these EWM language definitions")
    langs = yaml.safe_load(out.files["Languages.yaml"])["tasks"][0]["tasks"]
    assert langs.count("Cobol Compile and Link") == 1 and "Cobol CICS Compile and Link" not in langs


def test_language_map(sd):
    out = emit_all(sd)
    entry = out.language_map["Cobol DB2 CICS Compile and Link"]
    assert entry["task"] == "Cobol Compile and Link"
    assert entry["variables"] == {"IS_CICS": True, "IS_SQL": True, "doLinkEdit": True}
    assert {"IS_CICS", "BATOPTS", "CICSOPTS", "compileParm"} <= set(entry["taskVariables"])
    assert out.language_map["Cobol Compile and Link - Fetch"]["variables"] == {}
    assert out.language_map["Copybook"]["task"] == "Copybook"


def test_without_variants_every_langdef_is_a_task(sd):
    out = emit_all(sd, variants=False)
    assert len(out.files) == 1 + len(sd.langdefs)
    assert out.language_map["Cobol CICS Compile and Link"]["task"] == "Cobol CICS Compile and Link"


def _with(tmp_path, extra: str):
    p = tmp_path / "more.xml"
    p.write_text(VARIANTS.replace('<ld:langdef languageCode="COB" name="Copybook" translators=""/>',
                                  '<ld:langdef languageCode="COB" name="Copybook" translators=""/>' + extra))
    return parse(p)


def test_identical_duplicates_are_aliases(tmp_path):
    sd = _with(tmp_path, '<ld:langdef languageCode="COB" name="Cobol Compile and CICS Link" '
                         'translators="CICS translate,Compile CICS,Link"/>')
    families, notes = find_families(sd)
    assert families[0].aliases == {"Cobol Compile and CICS Link": "Cobol CICS Compile and Link"}
    out = emit_all(sd)
    assert out.language_map["Cobol Compile and CICS Link"] == out.language_map["Cobol CICS Compile and Link"]
    assert "Cobol_Compile_and_CICS_Link.yaml" not in out.files
    assert "#   Cobol Compile and CICS Link: same translators as Cobol CICS Compile and Link" in \
        out.files["Cobol_Compile_and_Link.yaml"]


def test_differing_duplicates_are_not_merged(tmp_path):
    sd = _with(tmp_path, '<ld:langdef languageCode="COB" name="Cobol Compile and CICS Link" '
                         'translators="CICS translate,Compile CICS,Link other"/>')
    families, notes = find_families(sd)
    assert "Cobol CICS Compile and Link" not in families[0].members
    assert any("same CICS/SQL/link-edit features" in n for n in notes)


def test_batch_is_a_neutral_word(tmp_path):
    sd = _with(tmp_path, '<ld:langdef languageCode="PLI" name="Cobol Batch Compile and Link" '
                         'translators="Compile batch,Link"/>')  # another language code: its own family
    p = tmp_path / "batch.xml"
    p.write_text(VARIANTS.replace('name="Cobol Compile and Link" translators', 'name="Cobol Batch Compile and Link" '
                                  'translators'))
    families, _ = find_families(parse(p))
    assert families[0].task == "Cobol Compile and Link"  # named without "Batch"
    assert "Cobol Batch Compile and Link" in families[0].members
    assert family_key("C Batch Compile and Link") == family_key("C CICS Compile and Link") == "c compile"


def test_names_of_only_feature_words_form_no_family(tmp_path):
    sd = _with(tmp_path, '<ld:langdef languageCode="LNK" name="Batch Link" translators="Link"/>'
                         '<ld:langdef languageCode="LNK" name="Link Edit" translators="Link other"/>')
    families, notes = find_families(sd)
    assert all(f.language_code != "LNK" for f in families) and not any("LNK" in n for n in notes)


def test_fold_map_overrides_the_detected_family(sd):
    folds = {"Cobol CICS family": {"variants": {"Cobol Compile and Link": {"IS_CICS": False},
                                                "Cobol CICS Compile and Link": {"IS_CICS": True}}}}
    out = emit_all(sd, folds=folds)
    assert out.language_map["Cobol CICS Compile and Link"]["task"] == "Cobol CICS family"
    # the rest of the detected family is still folded, named after its plainest member
    assert out.language_map["Cobol DB2 Compile and Link"]["task"] == "Cobol Compile"
    assert out.language_map["Cobol DB2 CICS Compile and Link"]["variables"] == \
        {"IS_CICS": True, "IS_SQL": True, "doLinkEdit": True}
    assert [f.source for f in out.families] == ["fold map", "detected"]


def test_fold_map_errors(sd):
    with pytest.raises(ValueError, match="unknown language definition"):
        emit_all(sd, folds={"X": {"Nope": {"IS_CICS": True}, "Cobol Compile and Link": {"IS_CICS": False}}})
    with pytest.raises(ValueError, match="same flags"):
        emit_all(sd, folds={"X": {"Cobol Compile": {"IS_CICS": True},
                                  "Cobol Compile and Link": {"IS_SQL": False}}})
    with pytest.raises(ValueError, match="belongs to a language definition"):
        emit_all(sd, folds={"Copybook": {"Cobol Compile": {"IS_CICS": True},
                                         "Cobol Compile and Link": {"IS_CICS": False}}})


ROOT = Path(__file__).parent.parent
SCHEMA = Path(os.environ.get("EWM2ZBUILDER_SCHEMA", ROOT / "schema" / "buildConfigurationSchema-v1.0.3.json"))


@pytest.mark.skipif(not SCHEMA.exists(), reason="schema not present")
def test_merged_task_matches_schema(sd):
    validator = Draft202012Validator(json.loads(SCHEMA.read_text()))
    for text in emit_all(sd).files.values():
        assert not list(validator.iter_errors(yaml.safe_load(text)))
