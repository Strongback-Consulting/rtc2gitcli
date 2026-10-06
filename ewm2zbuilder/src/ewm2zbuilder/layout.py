"""Target repository layout from the migration mirror.

`scm migrate-to-git` keeps a mirror whose working tree is exactly what `scm load`
writes (zComponent projects with `zOSsrc/` folders); resuming and verifying the
migration depend on that. This step copies the mirror's history into a new
repository in the target layout:

  * every commit is rewritten with folder renames applied to its paths
    (`zOSsrc` -> `src` by default), and to the paths inside `.gitattributes`,
    `.gitignore` files and `.ewm/zos-metadata.json`;
  * authors, committers, dates and messages (with their EWM trailers) are kept,
    so the result is deterministic: rerunning on a longer mirror reproduces the
    same commits for the same history;
  * annotated tags are re-created with their original tagger and message;
  * optionally a last commit adds `dbb-app.yaml` (see `app.py`), built from the
    metadata of the newest commit.

Uses the git command line; the mirror is not modified.
"""

from __future__ import annotations

import json
import os
import subprocess
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

from .app import DEFAULT_RENAMES, rename

METADATA = ".ewm/zos-metadata.json"


def git(repo: Path, *args: str, input: bytes | None = None, env: dict | None = None) -> bytes:
    result = subprocess.run(["git", "-C", str(repo), *args], input=input, capture_output=True,
                            env={**os.environ, **(env or {})})
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {result.stderr.decode(errors='replace').strip()}")
    return result.stdout


def rename_pattern_line(line: str, renames) -> str:
    """Rename folders in the path pattern of a .gitattributes/.gitignore line; comments stay."""
    if not line.strip() or line.lstrip().startswith("#"):
        return line
    pattern, sep, rest = line.partition(" ")
    return rename(pattern, renames) + sep + rest


def rewrite_blob(path: str, content: bytes, renames) -> bytes:
    name = path.rsplit("/", 1)[-1]
    if name in (".gitattributes", ".gitignore"):
        text = content.decode("utf-8")
        return "\n".join(rename_pattern_line(line, renames) for line in text.split("\n")).encode("utf-8")
    if path == METADATA:
        document = json.loads(content)
        for key in ("zFolders", "members"):
            if key in document:
                document[key] = dict(sorted((rename(p, renames), v) for p, v in document[key].items()))
        return (json.dumps(document, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    return content


@dataclass
class Result:
    commits: int = 0
    tags: list[str] = field(default_factory=list)
    head: str = ""


class _Rewriter:
    def __init__(self, source: Path, target: Path, renames):
        self.source, self.target, self.renames = source, target, renames
        self.blobs: dict[tuple[str, str], str] = {}  # (path, blob) -> rewritten blob
        self.commits: dict[str, str] = {}  # source commit -> target commit

    def blob(self, path: str, sha: str) -> str:
        name = path.rsplit("/", 1)[-1]
        if name not in (".gitattributes", ".gitignore") and path != METADATA:
            return sha
        key = (path, sha)
        if key not in self.blobs:
            content = git(self.target, "cat-file", "blob", sha)
            rewritten = rewrite_blob(path, content, self.renames)
            self.blobs[key] = sha if rewritten == content else \
                git(self.target, "hash-object", "-w", "--stdin", input=rewritten).decode().strip()
        return self.blobs[key]

    def tree(self, commit: str) -> str:
        entries = git(self.target, "ls-tree", "-r", "-z", "--full-tree", commit).split(b"\0")
        lines = []
        for entry in entries:
            if not entry:
                continue
            meta, path = entry.decode("utf-8").split("\t", 1)
            mode, kind, sha = meta.split(" ")
            if kind == "blob":
                sha = self.blob(path, sha)
            lines.append(f"{mode} {sha}\t{rename(path, self.renames)}")
        with tempfile.TemporaryDirectory() as tmp:
            env = {"GIT_INDEX_FILE": str(Path(tmp) / "index")}
            git(self.target, "update-index", "--add", "-z", "--index-info",
                input="\0".join(lines).encode("utf-8") + b"\0", env=env)
            return git(self.target, "write-tree", env=env).decode().strip()

    def commit(self, sha: str) -> str:
        raw = git(self.target, "cat-file", "commit", sha).decode("utf-8")
        header, _, message = raw.partition("\n\n")
        env = {}
        parents = []
        for line in header.split("\n"):
            key, _, value = line.partition(" ")
            if key == "parent":
                parents.append(self.commits[value])
            elif key in ("author", "committer"):
                name, _, rest = value.partition(" <")
                email, _, date = rest.partition("> ")
                prefix = "GIT_AUTHOR" if key == "author" else "GIT_COMMITTER"
                # "@": raw epoch even for small (old) timestamps, which git would otherwise misread
                env.update({f"{prefix}_NAME": name, f"{prefix}_EMAIL": email, f"{prefix}_DATE": "@" + date})
        args = ["commit-tree", self.tree(sha)]
        for parent in parents:
            args += ["-p", parent]
        new = git(self.target, *args, input=message.encode("utf-8"), env=env).decode().strip()
        self.commits[sha] = new
        return new


def transform(source: Path, target: Path, branch: str = "main", renames=DEFAULT_RENAMES,
              app_yaml: bytes | None = None, app_identity: tuple[str, str] = ("rtc2git", "rtc2git@rtc.to")
              ) -> Result:
    """Copy the history of the mirror's current branch into a new repository at `target`."""
    if target.exists() and any(target.iterdir()):
        raise RuntimeError(f"{target} exists and is not empty")
    subprocess.run(["git", "clone", "--quiet", "--no-checkout", "--no-local", str(source), str(target)],
                   check=True, capture_output=True)
    rewriter = _Rewriter(source, target, renames)
    result = Result()
    head = git(target, "rev-parse", "HEAD").decode().strip()
    for sha in git(target, "rev-list", "--reverse", "--topo-order", head).decode().split():
        rewriter.commit(sha)
        result.commits += 1
    new_head = rewriter.commits[head]

    # annotated tags: same tagger, date and message, on the rewritten commit
    tags = git(target, "for-each-ref", "--format=%(refname:strip=2) %(objecttype) %(*objectname) %(objectname)",
               "refs/tags").decode().split("\n")
    for line in filter(None, tags):
        name, kind, peeled, obj = line.split(" ")
        commit = peeled if kind == "tag" else obj
        if commit not in rewriter.commits:
            continue
        if kind == "tag":
            raw = git(target, "cat-file", "tag", obj).decode("utf-8")
            header, _, message = raw.partition("\n\n")
            lines = [f"object {rewriter.commits[commit]}" if l.startswith("object ") else l
                     for l in header.split("\n")]
            new = git(target, "mktag", input=("\n".join(lines) + "\n\n" + message).encode("utf-8")).decode().strip()
        else:
            new = rewriter.commits[commit]
        git(target, "update-ref", f"refs/tags/{name}", new)
        result.tags.append(name)

    if app_yaml is not None:
        with tempfile.TemporaryDirectory() as tmp:
            env = {"GIT_INDEX_FILE": str(Path(tmp) / "index")}
            git(target, "read-tree", new_head, env=env)
            blob = git(target, "hash-object", "-w", "--stdin", input=app_yaml).decode().strip()
            git(target, "update-index", "--add", "--cacheinfo", f"100644,{blob},dbb-app.yaml", env=env)
            tree = git(target, "write-tree", env=env).decode().strip()
        date = git(target, "log", "-1", "--format=%cd", "--date=raw", new_head).decode().strip()
        name, email = app_identity
        env = {"GIT_AUTHOR_NAME": name, "GIT_AUTHOR_EMAIL": email, "GIT_AUTHOR_DATE": "@" + date,
               "GIT_COMMITTER_NAME": name, "GIT_COMMITTER_EMAIL": email, "GIT_COMMITTER_DATE": "@" + date}
        new_head = git(target, "commit-tree", tree, "-p", new_head,
                       input=b"Add DBB zBuilder application configuration\n", env=env).decode().strip()

    # the target holds only the rewritten history
    for ref in git(target, "for-each-ref", "--format=%(refname)", "refs/heads", "refs/remotes").decode().split():
        git(target, "update-ref", "-d", ref)
    git(target, "remote", "remove", "origin")
    git(target, "update-ref", f"refs/heads/{branch}", new_head)
    git(target, "symbolic-ref", "HEAD", f"refs/heads/{branch}")
    git(target, "reset", "--quiet", "--hard", branch)
    git(target, "gc", "--quiet", "--prune=now")
    result.head = new_head
    return result


def metadata_at(repo: Path, commit: str = "HEAD") -> dict | None:
    try:
        return json.loads(git(repo, "show", f"{commit}:{METADATA}"))
    except RuntimeError:
        return None
