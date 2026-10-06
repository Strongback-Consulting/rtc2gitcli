# Migrating an EWM z/OS stream to Git and DBB zBuilder

`tools/migrate-zos` runs the three steps below. Each step can also be run on its own.

```bash
tools/migrate-zos -r <nick> -s "<stream>" -x <sysdef-export.xml> -w <work> -t <app-repo> -c <zBuilder schema.json>
```

Repeat `-s "<stream>[=<branch>]"` to migrate several streams as branches of one repository (see `docs/streams-as-branches.md`):
- The first stream is the main line (branch `main`), in `<work>/mirror`.
- Each further stream is a branch in `<work>/streams/<branch>`.
- Step 3 copies every branch and adds a `dbb-app.yaml` to each branch tip.

| Step | Tool | Output |
|---|---|---|
| 1. History | `scm migrate-to-git --stream "<stream>" -d <work>/mirror` | the **mirror**: one commit per change set, in EWM's layout |
| 2. Shared build configuration | `ewm2zbuilder <export.xml> -o <work>/zbuilder` | `Languages.yaml`, one language task YAML per task, `language-map.yaml`, `conversion-report.txt` |
| 3. Application repository | `ewm2zbuilder layout <work>/mirror <app-repo> --language-map …` | the history in the target layout, plus `dbb-app.yaml` |

The system definition export comes from the Build System Toolkit:
- **Command:** `tools/export-sysdefs -t <toolkit jars> -r <repository URI> -u <user> -p "<project area>" -o export.xml`.
- **Password:** the script asks for it and passes it to Ant through a private, temporary properties file.
- **Project area:** `migrate-inventory` shows the project area that owns each definition.

The shared configuration (step 2) goes to a separate build configuration repository, next to a hand-maintained `dbb-build.yaml`. The application repository (step 3) is the one developers clone.

## What carries the z/OS information

- **Language and data set definitions.** Every commit of the mirror where they change contains `.ewm/zos-metadata.json`:
  - each zFolder's data set definition;
  - each member's language definition (name and UUID), file-level build variable overrides (`team.enterprise.build.var.<NAME>`), `mvsCodePage`, `alwaysLoad` and `ignoreForDependencyBuild`.

  A reassigned language definition is a commit that changes this file.
- **Code pages.** The generated `.gitattributes` gives every non-binary member `zos-working-tree-encoding=<code page> git-encoding=utf-8`. The code page is the member's `mvsCodePage`, otherwise `zos.codepage` (default `IBM-1047`). Git on z/OS uses it to check members out in EBCDIC; other Git clients ignore it.
- **Before migrating**, `scm migrate-inventory -r <nick> -o inventory.json "<stream>" ["<stream>" …]` lists:
  - every definition in use;
  - the zFolders that mix language definitions;
  - every file-level variable override, with its values and files;
  - the per-file code pages.

## How language definitions become zBuilder tasks

- **Variants of one compiler share a task.** Language definitions that differ only in CICS, Db2 and/or link-edit become one task (for example COBOL batch, COBOL CICS, COBOL DB2 and COBOL CICS+DB2, each compile-only or with link-edit). `IS_CICS`, `IS_SQL` and `doLinkEdit` select the variant; in the task they default to false, false and true:
  - steps only some variants run are conditioned on them;
  - a step all variants run with different options or DDs is one step, with a `select` variable for the options and conditions on the extra DDs.
- **Members get the right variant per file.** `dbb-app.yaml` sets `IS_CICS`/`IS_SQL`/`doLinkEdit` for the members whose value differs from the task default (`forFiles`).
- **Members of a different compiler are overridden per file.** If a zFolder's members all belong to one task, `dbb-app.yaml` lists the folder. If a member's language definition maps to another task, the folder's members are listed one by one under their tasks.
- **File-level variable overrides** become task variables with `forFiles` in `dbb-app.yaml`.
- **Review the grouping.** `conversion-report.txt` lists every family and every near miss (same features, different compiler). `--no-variants` turns the grouping off.

## Layout

The mirror keeps EWM's paths: resume and the byte-for-byte check against `scm load` need them. `ewm2zbuilder layout` writes the application repository with `zOSsrc` renamed to `src` in every commit, and in the paths inside `.gitattributes`, `.gitignore` and the metadata file. Authors, dates, messages, `EWM-ChangeSet` trailers and tags are kept. Running it again on a longer mirror reproduces the same commits for the same history. The last commit adds `dbb-app.yaml`.

## Verified on EWM 7.2

IBM's Mortgage sample, "Mortgage Development Stream" (5 components, 11 change sets), was run through all three steps with `tools/migrate-zos`. The system definitions came from `tools/export-sysdefs` for the project area "JKE Banking (Change Management)".
- **Step 1:** created both workspaces, loaded the target and migrated.
  - The mirror's HEAD is byte-identical to `scm load` of the source workspace (57 files).
  - `.ewm/zos-metadata.json` changes in exactly the three property-only change sets that assign the definitions.
  - Members carry `zos-working-tree-encoding=IBM-1047`.
  - A rerun adds nothing. After a plugin upgrade that changes the generated files, it adds one "Update generated files before resuming the migration" commit.
- **Step 2:** the 8 language definitions become 4 tasks. All five COBOL definitions form one family:
  - batch and CICS, each compile-only or with link-edit, plus CICS+Db2;
  - one compile step whose options come from a `select` on `IS_CICS`/`IS_SQL`;
  - the Db2 library and `DBRMLIB` only for `IS_SQL`;
  - the object deck passed to the binder or written to `OBJ` depending on `doLinkEdit`;
  - the binder step conditioned on `doLinkEdit`.
- **Step 3:**
  - Commits, tags and file contents are unchanged, under `src/` instead of `zOSsrc/`.
  - `dbb-app.yaml` validates against the schema.
  - It assigns every COBOL member to that task with per-file `IS_CICS`/`IS_SQL`/`doLinkEdit`. That includes the mixed `MortgageApplication-JKECMORT/src/COBOL` folder (CICS+Db2, CICS, and a batch compile-only member).
  - Running the wrapper twice gives the same commits.
- **Two streams:** "Mortgage Test Stream" was added as branch `test`. The branch point and its verification are in `docs/streams-as-branches.md`. Each branch gets its own `dbb-app.yaml`.

## Not verified yet

A zBuilder build of a migrated application in a DBB environment. The YAML is validated against the zBuilder schema only. Open questions for that build:
- how zBuilder merges `dbb-app.yaml` task entries with the shared tasks;
- whether `**/<path>` patterns match as intended.
