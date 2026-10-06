# Several streams as branches of one repository

Each stream is migrated by its own `scm migrate-to-git` run, in its own sandbox. The first run creates the repository. Every later run adds its stream as a branch of that repository:

```bash
mkdir -p main/.jazz5 test/.jazz5
scm migrate-to-git -r <nick> -m migration.properties --stream "Development Stream" -b main -d main dev-source dev-target
scm migrate-to-git -r <nick> -m migration.properties --stream "Test Stream" -b test -g main -d test test-source test-target
```

- **`-b/--branch`:** the branch a run commits to.
  - For a new repository, it is the initial branch.
  - With `-g`, it is the new branch, which must not exist yet.
  - A sandbox that already has a repository must be on this branch.
- **`-g/--git-repository`:** the sandbox (or git directory) of an earlier migration. It is only used while the sandbox has no repository yet:
  - the sandbox becomes a linked worktree of that repository, so all branches and tags live in one repository;
  - `git worktree list` shows every stream's sandbox.
- **`--stream`:** required with `-g`. The run creates the stream's workspaces and sets the target workspace to the branch point before loading it.
- **Order:** run the streams one after the other, never two runs in the same repository at once. Every run resumes like a single-stream migration.

`tools/migrate-zos` does this for z/OS streams: `-s "<stream>[=<branch>]"` can be given several times. The first stream is the main line, and its mirror is `<work>/mirror`. The others are branches in `<work>/streams/<branch>`. `ewm2zbuilder layout` copies every branch and adds `dbb-app.yaml` to each branch tip.

## Where a branch starts

EWM streams share change sets, not commits. A new branch starts at the **newest commit, on any existing branch, whose configuration the stream contains** (`BranchPoint`). To find it, the run walks each branch from its initial commit:

- **Initial commit:** its `EWM-Base` trailers (the start state of that migration) must be in the stream's history.
- **Commits of change sets:** each `EWM-ChangeSet` must be the next change set of its component in the stream's delivery order.
- **Commits without a change set** (generated files) do not end the walk.
- **End of the walk:** the first change set the stream does not have, or has in another order. The log and the report (`branchPoint`) name it.
- **Several branches:** the branch with the most shared change sets wins.

The run then adds the change sets up to the branch point to the new target workspace in one server-side accept, and loads it. The branch starts at the commit, and the generated files (`.gitignore`, `.gitattributes`, `.ewm/zos-metadata.json`) are restored from it. If anything else in the loaded sandbox differs from the commit, the run stops before migrating.

After the branch point, each change set of the stream becomes a commit on the branch, including change sets that other branches also have after their branch point. The two commits are linked by their identical `EWM-ChangeSet` trailers. There are no merge commits: EWM does not record merges.

If the stream shares no history with any branch, its branch starts with its own initial commit.

## Verified on EWM 7.2

The second stream was "Mortgage Test Stream", a copy of the Mortgage sample's "Mortgage Development Stream". It lacks one Development change set ("Update JKEPDATA.cpy for new field"), which Development delivered before four change sets that both streams have.

- **Branch point:** the new branch starts after the six shared change sets that come before it.
- **Workspace:** those six change sets were accepted into the new target workspace, which was then loaded.
- **Branch commits:** the four later change sets became commits on `test`.
- **Content:** the worktree matches `scm load`.
- **Rerun:** a rerun adds nothing. `ewm2zbuilder layout` reproduces both branch tips with identical commit IDs.
