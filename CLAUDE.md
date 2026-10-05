# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`rtc2gitcli` migrates history from an IBM RTC (Rational Team Concert / Jazz SCM) workspace into a Git repository. It is **not a standalone app**: it is an Eclipse/OSGi plugin that adds a `migrate-to-git` subcommand to IBM's `scm` CLI (registered in `rtc2git.cli.extension/plugin.xml` via the `com.ibm.team.rtc.cli.infrastructure.subcommand` extension point).

Usage (from README.adoc), run inside the sandbox directory after `scm load ... <TARGET_WORKSPACE>`:

```bash
scm migrate-to-git -r <uri> -u <username> -P <password> -m <migration.properties> <SOURCE_WORKSPACE> <TARGET_WORKSPACE>
```

`migration.properties` at the repo root is the documented template of every supported migration option (git user defaults, encoding, baseline include regex, work item / commit message formatting, `.gitattributes`/`.gitignore` entries, JGit tuning, etc.). When adding a new property, document it there and read it in `GitMigrator`.

## Build and test

Java code lives in `rtc2git.cli.extension/` (sources in `src/`, tests in `src_test/`). It is built with **Tycho 4** through the Maven wrapper at the repo root. The `com.ibm.team.*` dependencies are not on Maven Central. They come from a local **EWM 7.2 SCM Tools** install, which `ewm-7.2.target` uses as the target platform:

```bash
export SCMTOOLS_HOME=/path/to/jazz/scmtools          # folder containing eclipse/plugins
./mvnw verify                                         # compile, run all JUnit tests, package the bundle
./mvnw verify -pl rtc2git.cli.extension -Dtest=GitMigratorTest#testCommitChanges -Dsurefire.failIfNoSpecifiedTests=false
```

- Tycho's incremental compile sometimes keeps stale test classes after signature changes. These show up as `IncompatibleClassChangeError` or as unresolved methods in tests. Run `./mvnw clean verify` when that happens.
- Java 17 (`maven.compiler.release`, `Bundle-RequiredExecutionEnvironment: JavaSE-17`); the 7.2 `scm` runtime is Java 17.
- JGit and its dependencies are embedded as private inner jars. The `initialize` phase copies runtime deps, version-stripped, into `lib/`. `META-INF/MANIFEST.MF` `Bundle-ClassPath` and `build.properties` `bin.includes` must list the same `lib/*.jar` names; update all three when a dependency changes.
- Tests are JUnit 4, run by `maven-surefire-plugin` outside OSGi. Tests that load IBM CLI classes need the SCM Tools target platform, so every test run needs `SCMTOOLS_HOME`.
- Install into an SCM Tools copy with `tools/install-scmtools-plugin.sh <scmtools-dir>`. EWM 7.x starts bundles through `simpleconfigurator`, so the script registers the jar in `bundles.info`; `dropins/` is ignored. Check the install with `scm help migrate-to-git`. On Apple silicon without Rosetta, the bundled x86_64 JRE won't start. Run the launcher jar from `eclipse/scm` with a local Java 17+ instead.
- Eclipse workflow: import as a Maven project with `ewm-7.2.target` as the active target platform, and run `launch/rtc2git.launch`. Its plugin list is stale (RTC 4.x era) and needs regenerating in Eclipse.
- Formatting: Eclipse formatter profile `eclipse-rtccli-format-settings.xml` (tabs for indentation).

### ewm2zbuilder (Python)

`ewm2zbuilder/` converts an EWM system definition export (XML from the Build System Toolkit's Ant `ld:` tasks) into DBB zBuilder YAML. It is a separate Python 3.11+ project managed with `uv`:

```bash
cd ewm2zbuilder
uv run --group dev pytest -q                                   # all tests
uv run --group dev pytest -q tests/test_convert.py::test_emit_step_shape
uv run ewm2zbuilder <export.xml> -o out --schema <schema.json> [--sources-map map.yaml]
```

Neither the IBM zBuilder schema nor any client export is committed. Tests find them through `EWM2ZBUILDER_SCHEMA` and `EWM2ZBUILDER_EXPORT`, or in the git-ignored `ewm2zbuilder/schema/` and `ewm2zbuilder/local/` folders, and skip when they are absent. This repo is public: never commit client system definitions, data set names, or generated output.

End-to-end testing against a live EWM server is described in `docs/e2e-testing.md`.

## Architecture

Flow of one `scm migrate-to-git` run:

1. **`git/MigrateToGit`** (the registered subcommand) loads and trims `migration.properties`, builds the baseline-include regex and a `GitMigrator`, then delegates to the abstract base `MigrateTo.run()`. Options are declared in `MigrateToOptions` / `MigrateToGitOptions`.
2. **`MigrateTo`** (VCS-agnostic driver) logs into RTC, collects all baselines of the source workspace's flow target into an `RtcTagList`, then computes an incoming change log (source stream vs. target workspace) and walks it with `HistoryEntryVisitor` to attach each `RtcChangeSet` (with its work items) to the baseline/tag it belongs to.
   - The change log lists change sets by creation date, which is not the order they were delivered. `MigrateTo.getChangeSetHistory` therefore reads each component's full history from the source workspace (`IChangeHistory`), and `RtcTag` accepts each component's change sets in that delivery order. Never drop or reorder change sets based on the change log alone. The list is sorted by creation date, inactive and non-matching (`rtc.baseline.include`) tags are pruned, and an implicit HEAD tag catches change sets after the last baseline. Other options: `-t/--timeout` (RTC connection timeout, default 900s), `-L/--list-tags-only` (stop after printing tags), and `-U/--update` (update migration: skip a leading empty tag).
3. **`RtcMigrator`** iterates tags → change sets.
   - For each change set it runs `scm accept` on the target workspace through `command/RtcCommands`, loads newly seen components (by UUID), then calls `Migrator.commitChanges`. A change set already committed by an earlier run is accepted but not committed again.
   - Every status is checked. OUT_OF_SYNC leads to a forced reload and a second accept. A GAP stops the run unless `rtc.accept.missing.changesets=true`. Any other failure stops the run; never let a failed accept fall through to a commit.
   - After each tag it calls `Migrator.createTag` if the tag is to be created. It periodically deletes Eclipse local history under `.metadata` to save disk.
4. **`git/GitMigrator`** (the only `Migrator` implementation, uses JGit) initializes the git repo in the sandbox, maintains root `.gitignore`/`.gitattributes`, translates `.jazzignore` files into `.gitignore` (`util/JazzignoreTranslator`), builds commit messages from changeset comment + work items (`util/CommitCommentTranslator`, `commit.message.*` / `rtc.workitem.*` properties), stages and commits with the RTC author/date (`IdentityResolver`: user mapping file, email domain, time zone), and creates annotated tags.
   - Each change set becomes exactly one commit, even an empty one, with an `EWM-ChangeSet: <uuid>` trailer. The trailers are the resume state: reopening an existing repository reads them (`isMigrated`) and refuses a dirty sandbox.
   - The root `.gitignore` holds the migration's own entries (`/.jazz5`, `/.metadata`, configured exclusions). A root `.jazzignore` only owns a marked block inside it; `.gitignore` files in subfolders are fully owned by their `.jazzignore`.
   - Ignored-but-present files are force-added (`ForceAddTreeIterator`), except scm metadata and configured exclusions, because only scm writes to the sandbox. It also applies JGit window-cache tuning and runs `git gc` every 1000 commits (`needsIntermediateCleanup`/`intermediateCleanup`).

Key seams:
- `Migrator`, `ChangeSet`, `Tag` are the interfaces separating RTC-side logic from the git backend; tests for the git side (`GitMigrator*Test`, `IdentityResolverTest`, `util/*Test`) exercise these without RTC. `ChangeSet` and `Tag` have `default` methods for EWM-specific data (UUIDs, user ID, baseline UUIDs), so test doubles stay small.
- RTC CLI commands are invoked in-process via `command/*CommandDelegate`, which builds an `scm` sub-command line, swaps it into the IBM `ClientConfiguration` via reflection, and restores the original afterwards.
  - Repository and login options come from `command/RtcConnection`, which is captured once at the start of `MigrateTo.run()` before any sub-command runs.
  - `RtcMigratorTest` fakes `RtcCommands` to test status handling without a server.
  - `CLIClientException` extends `Throwable`, not `Exception`. `MigrateTo.setStdOut` likewise swaps the CLI's stdout via reflection. Both rely on IBM internal (`@SuppressWarnings("restriction")`) APIs and are the most fragile parts when RTC versions change.
- `src/org/slf4j/impl/StaticLoggerBinder.java` is a local SLF4J binding so JGit's logging works inside the OSGi bundle.
