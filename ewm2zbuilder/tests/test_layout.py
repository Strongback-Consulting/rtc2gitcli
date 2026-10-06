"""The target repository: zOSsrc -> src through the whole history, tags kept, dbb-app.yaml added."""

import json
import subprocess
from pathlib import Path

import pytest
import yaml

from ewm2zbuilder.cli import main
from ewm2zbuilder.layout import rewrite_blob, transform

RENAMES = (("zOSsrc", "src"),)


def run(repo: Path, *args, env=None):
    import os
    return subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True, text=True,
                          env={**os.environ, **(env or {})}).stdout.strip()


def commit(repo: Path, files: dict, message: str, date: str):
    for path, content in files.items():
        f = repo / path
        if content is None:
            f.unlink()
            continue
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(content)
    run(repo, "add", "-A")
    env = {"GIT_AUTHOR_NAME": "Dev", "GIT_AUTHOR_EMAIL": "dev@x", "GIT_AUTHOR_DATE": date,
           "GIT_COMMITTER_NAME": "Dev", "GIT_COMMITTER_EMAIL": "dev@x", "GIT_COMMITTER_DATE": date}
    run(repo, "commit", "-q", "-m", message, env=env)


def metadata(language):
    return json.dumps({"format": 1, "defaultCodePage": "IBM-1047",
                       "zFolders": {"App/zOSsrc/COBOL": {"dataSetDefinition": {"name": "COBOL", "uuid": "_d"}}},
                       "members": {"App/zOSsrc/COBOL/A.cbl": {
                           "languageDefinition": {"name": language, "uuid": "_l"}}},
                       "definitions": {}}, indent=2) + "\n"


@pytest.fixture
def mirror(tmp_path):
    repo = tmp_path / "mirror"
    repo.mkdir()
    run(repo, "init", "-q", "-b", "master")
    commit(repo, {".gitignore": "/.jazz5\n/App/zOSsrc/COBOL/*.lst\n"}, "Initial commit\n\nEWM-Base: c=x", "1700001000 +0000")
    commit(repo, {"App/zOSsrc/COBOL/A.cbl": "       ID DIVISION.\n", ".ewm/zos-metadata.json": metadata("Batch"),
                  ".gitattributes": "# >>> generated\n/App/zOSsrc/** text eol=lf zos-working-tree-encoding=IBM-1047\n"},
           "Add A\n\nEWM-ChangeSet: _cs1", "1700002000 +0100")
    run(repo, "tag", "-a", "Sprint_1", "-m", "EWM baseline: Sprint 1",
        env={"GIT_COMMITTER_NAME": "Lead", "GIT_COMMITTER_EMAIL": "lead@x", "GIT_COMMITTER_DATE": "1700002500 +0000"})
    commit(repo, {".ewm/zos-metadata.json": metadata("CICS")}, "Reassign A\n\nEWM-ChangeSet: _cs2", "1700003000 +0000")
    return repo


def test_rewrite_blob():
    assert rewrite_blob(".gitattributes", b"# zOSsrc comment\n/a/zOSsrc/** text\n", RENAMES) == \
        b"# zOSsrc comment\n/a/src/** text\n"
    meta = json.loads(rewrite_blob(".ewm/zos-metadata.json", metadata("Batch").encode(), RENAMES))
    assert list(meta["members"]) == ["App/src/COBOL/A.cbl"]
    assert rewrite_blob("App/zOSsrc/COBOL/A.cbl", b"zOSsrc", RENAMES) == b"zOSsrc"  # sources untouched


def test_history_in_target_layout(mirror, tmp_path):
    result = transform(mirror, tmp_path / "target")
    target = tmp_path / "target"
    assert result.commits == 3 and result.tags == ["Sprint_1"]
    assert run(target, "ls-tree", "-r", "--name-only", "main").split("\n") == [
        ".ewm/zos-metadata.json", ".gitattributes", ".gitignore", "App/src/COBOL/A.cbl"]
    assert "/App/src/** text eol=lf" in run(target, "show", "main:.gitattributes")
    assert "/App/src/COBOL/*.lst" in run(target, "show", "main:.gitignore")
    # authors, dates, messages and trailers are kept
    log = run(target, "log", "--format=%an|%ad|%cd|%B", "--date=raw", "main")
    assert "Dev|1700002000 +0100|1700002000 +0100|Add A\n\nEWM-ChangeSet: _cs1" in log
    # the tag keeps its tagger and message and points to the rewritten commit
    assert run(target, "cat-file", "-p", "Sprint_1").split("\n")[3].startswith("tagger Lead <lead@x> 1700002500")
    assert run(target, "rev-parse", "Sprint_1^{commit}") == run(target, "rev-parse", "main~1")
    assert run(target, "branch", "--format=%(refname:short)") == "main"
    assert (target / "App/src/COBOL/A.cbl").exists()  # checked out


def test_deterministic(mirror, tmp_path):
    assert transform(mirror, tmp_path / "a").head == transform(mirror, tmp_path / "b").head


def test_cli_adds_dbb_app_yaml(mirror, tmp_path):
    language_map = tmp_path / "language-map.yaml"
    language_map.write_text(yaml.safe_dump({"CICS": {"task": "Cobol", "variables": {"IS_CICS": True}}}))
    assert main(["layout", str(mirror), str(tmp_path / "t"), "--language-map", str(language_map)]) == 0
    target = tmp_path / "t"
    assert run(target, "log", "-1", "--format=%s", "main") == "Add DBB zBuilder application configuration"
    app = yaml.safe_load(run(target, "show", "main:dbb-app.yaml"))
    assert app["tasks"] == [{"language": "Cobol", "sources": ["**/App/src/COBOL/*"], "variables": [
        {"name": "IS_CICS", "value": True, "forFiles": ["**/App/src/COBOL/*"]}]}]


def test_target_must_be_new(mirror, tmp_path):
    (tmp_path / "used").mkdir()
    (tmp_path / "used" / "x").write_text("x")
    with pytest.raises(RuntimeError):
        transform(mirror, tmp_path / "used")


def test_stream_branches_keep_shared_history(mirror, tmp_path):
    # a second stream migrated with --git-repository: a branch from the first change set, with its own work
    run(mirror, "branch", "release", "HEAD~1")
    run(mirror, "checkout", "-q", "release")
    commit(mirror, {"App/zOSsrc/COBOL/B.cbl": "       ID DIVISION.\n"}, "Fix on release\n\nEWM-ChangeSet: _cs9",
           "1700004000 +0000")
    run(mirror, "checkout", "-q", "master")
    language_map = tmp_path / "language-map.yaml"
    language_map.write_text(yaml.safe_dump({"Batch": {"task": "Cobol", "variables": {}},
                                            "CICS": {"task": "Cobol", "variables": {"IS_CICS": True}}}))

    assert main(["layout", str(mirror), str(tmp_path / "t"), "--language-map", str(language_map)]) == 0

    target = tmp_path / "t"
    assert run(target, "branch", "--format=%(refname:short)").split() == ["main", "release"]
    # the shared commits exist once: the branches meet at the rewritten "Add A"
    base = run(target, "merge-base", "main", "release")
    assert run(target, "log", "-1", "--format=%s", base) == "Add A"
    assert run(target, "log", "--format=%s", "release~1", "-1") == "Fix on release"
    assert run(target, "ls-tree", "-r", "--name-only", "release~1", "App").split() == \
        ["App/src/COBOL/A.cbl", "App/src/COBOL/B.cbl"]
    # dbb-app.yaml from each branch's own metadata
    assert "IS_CICS" in run(target, "show", "main:dbb-app.yaml")
    assert "IS_CICS" not in run(target, "show", "release:dbb-app.yaml")
    assert run(target, "describe", "--tags", "release~1").startswith("Sprint_1")
