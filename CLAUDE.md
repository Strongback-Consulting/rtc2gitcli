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

All code lives in `rtc2git.cli.extension/` (sources in `src/`, tests in `src_test/`; Maven outputs to `bin/` and `bin_test/`, and copies runtime deps into `lib/`).

- Most of `src/` imports `com.ibm.team.*` classes that are **not available from Maven**. They come from IBM's SCM Tools installation configured as an Eclipse PDE target platform (`rtc2git.target`, see the wiki "configure-target-platform"). A plain `mvn compile`/`mvn test` without that target platform will fail to compile those classes.
- The intended dev workflow is Eclipse: import as a Maven project with the SCM Tools target platform active, then run the `launch/rtc2git.launch` configuration (prints "Help for: scm migrate-to-git" by default; edit program arguments to run a real migration). RTC 6+ needs the workaround in https://github.com/rtcTo/rtc2gitcli/issues/44#issuecomment-396727582.
- Tests are JUnit 4. `pom.xml` declares only JGit and JUnit; `mvn dependency:copy-dependencies` (bound to `process-sources`) populates `lib/`, which `META-INF/MANIFEST.MF`'s `Bundle-ClassPath` references — keep the jar names in the manifest in sync when bumping dependency versions.
- Run a single test (only works where the IBM classes resolve): `mvn -f rtc2git.cli.extension/pom.xml test -Dtest=GitMigratorTest#testName`, or run it from Eclipse.
- Code targets Java 1.6 source/target (`maven.compiler.source/target`, `Bundle-RequiredExecutionEnvironment: JavaSE-1.6`) — avoid lambdas, diamond operator, try-with-resources, etc.
- Formatting: Eclipse formatter profile `eclipse-rtccli-format-settings.xml` (tabs for indentation).

## Architecture

Flow of one `scm migrate-to-git` run:

1. **`git/MigrateToGit`** (the registered subcommand) loads and trims `migration.properties`, builds the baseline-include regex and a `GitMigrator`, then delegates to the abstract base `MigrateTo.run()`. Options are declared in `MigrateToOptions` / `MigrateToGitOptions`.
2. **`MigrateTo`** (VCS-agnostic driver) logs into RTC, collects all baselines of the source workspace's flow target into an `RtcTagList`, then computes an incoming change log (source stream vs. target workspace) and walks it with `HistoryEntryVisitor` to attach each `RtcChangeSet` (with its work items) to the baseline/tag it belongs to. The list is sorted by creation date, inactive and non-matching (`rtc.baseline.include`) tags are pruned, and an implicit HEAD tag catches change sets after the last baseline. Other options: `-t/--timeout` (RTC connection timeout, default 900s), `-L/--list-tags-only` (stop after printing tags), and `-U/--update` (update migration: skip a leading empty tag).
3. **`RtcMigrator`** iterates tags → change sets. For each change set it runs `scm accept` on the target workspace (retrying with force-load on out-of-sync, or `--accept-missing-changesets` on gaps), loads newly seen components, then calls `Migrator.commitChanges`. After each tag it calls `Migrator.createTag` if the tag is to be created. It periodically deletes Eclipse local history under `.metadata` to save disk.
4. **`git/GitMigrator`** (the only `Migrator` implementation, uses JGit) initializes the git repo in the sandbox, maintains root `.gitignore`/`.gitattributes`, translates `.jazzignore` files into `.gitignore` (`util/JazzignoreTranslator`), builds commit messages from changeset comment + work items (`util/CommitCommentTranslator`, `commit.message.*` / `rtc.workitem.*` properties), stages and commits with the RTC author/date, and creates tags. It also applies JGit window-cache tuning and runs `git gc` every 1000 commits (`needsIntermediateCleanup`/`intermediateCleanup`).

Key seams:
- `Migrator`, `ChangeSet`, `Tag` are the interfaces separating RTC-side logic from the git backend; tests for the git side (`GitMigratorTest`, `GitPlainTest`, `util/*Test`) exercise these without RTC.
- RTC CLI commands are invoked in-process via `command/*CommandDelegate`, which constructs an `scm` sub-command line and injects it into the IBM `ClientConfiguration` via reflection. `MigrateTo.setStdOut` likewise swaps the CLI's stdout via reflection. Both rely on IBM internal (`@SuppressWarnings("restriction")`) APIs and are the most fragile parts when RTC versions change.
- `src/org/slf4j/impl/StaticLoggerBinder.java` is a local SLF4J binding so JGit's logging works inside the OSGi bundle.
