# End-to-end test against an EWM 7.2 server

This is how the accept/commit flow was verified on the JKE Banking sample: 8 components, 37 change sets. Repeat it after changes to that flow.

## Setup

1. Build the plugin and install it into a **copy** of the 7.2 SCM Tools: `./mvnw verify`, then `tools/install-scmtools-plugin.sh <copy>/jazz/scmtools`.
   - On Apple silicon the bundled x86_64 JRE doesn't run. Use a wrapper that sets `PRGPATH=<scmtools>/eclipse` and runs `java -jar <scmtools>/eclipse/plugins/org.eclipse.equinox.launcher_*.jar -data @noDefault "$@"` with a local Java 17+. `scm` needs `PRGPATH` to find `scripts/unix/mkroot`.
2. Log in once with a nickname: `scm login -r https://<host>:9443/ccm -u <user> -n <nick> -c`. After that, commands use only `-r <nick>`.
3. Create the two repository workspaces and load the target. The simplest way is to let the migration do it: run `mkdir -p <dir>/.jazz5`, then pass `--stream "<stream>" -d <dir>` to the first `scm migrate-to-git` run. It creates both workspaces as described below when they don't exist, and loads the target when it is not loaded in `<dir>`.
   - scm only accepts a `-d` directory that is already a sandbox; the empty `.jazz5` folder is enough.
   - Without `-d`, outside a sandbox, scm would run in its scratch area and the load would fail.
4. Or set them up by hand:
   - **source**, which flows from the stream under test: `scm create workspace -e`, then `scm add component -s <stream>`, then `scm set flowtarget <source> <stream>`.
   - **target**, which flows from source, with each component added at its *Initial Baseline*: `scm add component -b <initial baseline alias> <target> <component>`. Find the alias with `scm list baselines -C <component>`; it is the baseline numbered 1.
   - Then load the target into an empty directory: `scm load -r <nick> --allow <target>`.

## Run and verify

```bash
scm migrate-to-git -r <nick> -L -m migration.properties <source> <target>   # dry run: tags and change sets
scm migrate-to-git -r <nick> -m migration.properties <source> <target>
```

Checks:
- The change set count reported in the log equals the source history minus the Initial Baseline change sets. There is one commit per change set, each with an `EWM-ChangeSet` trailer, and no duplicate trailers.
- `git archive HEAD` is byte-identical to `scm load` of the source workspace (load it into another directory with `--allow`). Compare with `diff -r -x .jazz5 -x .metadata -x .gitignore -x .gitattributes -x .gitkeep`.
  - Line endings match because `.gitattributes` is generated from the EWM line delimiters (`gitattributes.from.ewm`, on by default).
  - Folders that are empty in EWM exist only with `keep.empty.folders=true`; without it they are the only expected difference.
  - With `lfs.threshold`/`lfs.patterns`, `git archive` holds LFS pointers. Compare a checkout made with git-lfs installed (`git lfs checkout`) instead.
- Shared baseline names across components become one tag. Rerunning never creates `<tag>_2`.
- Resume: kill the run with `kill -9` partway, then simply rerun it.
  - The rerun must report `Resuming: N change set(s) were accepted by an interrupted run but not committed` and discard them itself.
  - The final tree and the commit sequence must equal those of an uninterrupted run.
  - The start state comes from the `EWM-Base` trailers of the initial commit. A repository created by a plugin version without these trailers has none: components without a commit are then reported as not checkable.

## Resetting the target for another run

Unload every sandbox that loaded the workspaces (`scm unload -r <nick> --all -N`). Then reset the target: `scm remove component -N <target> <components...>`, then add the components again at their Initial Baselines.
