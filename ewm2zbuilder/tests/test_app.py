"""dbb-app.yaml from the z/OS metadata that scm migrate-to-git writes into the repository."""

import json
import os
from pathlib import Path

import pytest
import yaml
from jsonschema import Draft202012Validator

from ewm2zbuilder.app import build, rename
from ewm2zbuilder.cli import main


def member(langdef, **extra):
    return {"languageDefinition": {"name": langdef, "uuid": "_" + (langdef or "x")}, **extra}


# like the Mortgage sample: one COBOL zFolder mixes three language definitions
METADATA = {
    "format": 1,
    "defaultCodePage": "IBM-1047",
    "zFolders": {"App/zOSsrc/COBOL": {}, "App/zOSsrc/COPYBOOK": {}, "App/zOSsrc/REXX": {}},
    "members": {
        "App/zOSsrc/COBOL/CICSDB2.cbl": member("COBOL (CICS&DB2) and link-edit"),
        "App/zOSsrc/COBOL/CICS.cbl": member("COBOL (CICS) and link-edit",
                                            buildVariables={"CICSOPTS": "LIB,RENT,DYNAM"}),
        "App/zOSsrc/COBOL/BATCH.cbl": member("COBOL compilation (no CICS)"),
        "App/zOSsrc/COPYBOOK/A.cpy": member("Copybook"),
        "App/zOSsrc/COPYBOOK/B.cpy": member("Copybook"),
        "App/zOSsrc/REXX/BIND.rex": {"languageDefinition": None, "alwaysLoad": True},
        "Other/zOSsrc/COBOL/X.cbl": member("COBOL (CICS) and link-edit"),
        "Other/zOSsrc/COBOL/Y.cbl": member("COBOL and link-edit"),
        "Other/zOSsrc/COBOL/Z.cbl": member("COBOL compile only"),
    },
}
LANGUAGE_MAP = {
    "COBOL and link-edit": {"task": "COBOL and link-edit", "variables": {"IS_CICS": False, "IS_SQL": False},
                            "taskVariables": ["IS_CICS", "IS_SQL", "CICSOPTS"]},
    "COBOL (CICS) and link-edit": {"task": "COBOL and link-edit", "variables": {"IS_CICS": True, "IS_SQL": False},
                                   "taskVariables": ["IS_CICS", "IS_SQL", "CICSOPTS"]},
    "COBOL (CICS&DB2) and link-edit": {"task": "COBOL and link-edit",
                                       "variables": {"IS_CICS": True, "IS_SQL": True},
                                       "taskVariables": ["IS_CICS", "IS_SQL", "CICSOPTS"]},
    "COBOL compile only": {"task": "COBOL and link-edit",
                           "variables": {"IS_CICS": False, "IS_SQL": False, "doLinkEdit": False},
                           "taskVariables": ["IS_CICS", "IS_SQL", "CICSOPTS"]},
    "COBOL compilation (no CICS)": {"task": "COBOL compilation (no CICS)", "variables": {}, "taskVariables": []},
    "Copybook": {"task": "Copybook", "variables": {}, "taskVariables": []},
}


def tasks(config):
    return {t["language"]: t for t in config.document["tasks"]}


def test_rename():
    assert rename("App/zOSsrc/COBOL/A.cbl", (("zOSsrc", "src"),)) == "App/src/COBOL/A.cbl"
    assert rename("zOSsrcX/a", (("zOSsrc", "src"),)) == "zOSsrcX/a"


def test_mixed_folder_gets_per_file_sources():
    t = tasks(build(METADATA, LANGUAGE_MAP, "1.0.3"))
    # variants of one compiler share the task; the other compiler's member is overridden per file
    assert t["COBOL and link-edit"]["sources"] == [
        "**/App/src/COBOL/CICS.cbl", "**/App/src/COBOL/CICSDB2.cbl", "**/Other/src/COBOL/*"]
    assert t["COBOL compilation (no CICS)"]["sources"] == ["**/App/src/COBOL/BATCH.cbl"]
    assert t["Copybook"]["sources"] == ["**/App/src/COPYBOOK/*"]


def test_variant_variables_per_file():
    variables = {v["name"]: v for v in tasks(build(METADATA, LANGUAGE_MAP, "1.0.3"))["COBOL and link-edit"]["variables"]
                 if v["name"].startswith("IS_")}
    assert variables["IS_CICS"] == {"name": "IS_CICS", "value": True, "forFiles": [
        "**/App/src/COBOL/CICS.cbl", "**/App/src/COBOL/CICSDB2.cbl", "**/Other/src/COBOL/X.cbl"]}
    assert variables["IS_SQL"]["forFiles"] == ["**/App/src/COBOL/CICSDB2.cbl"]


def test_compile_only_member_turns_link_edit_off():
    variables = tasks(build(METADATA, LANGUAGE_MAP, "1.0.3"))["COBOL and link-edit"]["variables"]
    assert {"name": "doLinkEdit", "value": False, "forFiles": ["**/Other/src/COBOL/Z.cbl"]} in variables
    # linked members keep the task's default: no entry for them
    assert not any(v["name"] == "doLinkEdit" and v["value"] is True for v in variables)


def test_file_level_build_variables():
    task = tasks(build(METADATA, LANGUAGE_MAP, "1.0.3"))["COBOL and link-edit"]
    assert {"name": "CICSOPTS", "value": "LIB,RENT,DYNAM", "forFiles": ["**/App/src/COBOL/CICS.cbl"]} \
        in task["variables"]


def test_notes():
    notes = build(METADATA, LANGUAGE_MAP, "1.0.3").notes
    assert not any("BIND.rex" in n for n in notes)  # alwaysLoad members are not built on purpose
    other = dict(METADATA, members={"A/x.cbl": member("Unknown", buildVariables={"NOPE": "1"})})
    notes = build(other, LANGUAGE_MAP, "1.0.3").notes
    assert any("not in the language map" in n for n in notes)


def test_unknown_task_variable_is_noted():
    metadata = dict(METADATA, members={"A/x.cbl": member("Copybook", buildVariables={"NOPE": "1"})})
    assert any("NOPE is not a variable of the task" in n for n in build(metadata, LANGUAGE_MAP, "1.0.3").notes)


SCHEMA = Path(os.environ.get("EWM2ZBUILDER_SCHEMA",
                             Path(__file__).parent.parent / "schema" / "buildConfigurationSchema-v1.0.3.json"))


@pytest.mark.skipif(not SCHEMA.exists(), reason="schema not present")
def test_cli_writes_valid_dbb_app_yaml(tmp_path):
    (tmp_path / "zos-metadata.json").write_text(json.dumps(METADATA))
    (tmp_path / "language-map.yaml").write_text(yaml.safe_dump(LANGUAGE_MAP))
    out = tmp_path / "dbb-app.yaml"
    assert main(["app", str(tmp_path / "zos-metadata.json"), "--language-map", str(tmp_path / "language-map.yaml"),
                 "-o", str(out), "--schema", str(SCHEMA)]) == 0
    document = yaml.safe_load(out.read_text())
    assert not list(Draft202012Validator(json.loads(SCHEMA.read_text())).iter_errors(document))
    assert document["version"] == "1.0.3"
