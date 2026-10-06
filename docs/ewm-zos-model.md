# EWM 7.2 z/OS source metadata model

How EWM Enterprise Extensions (EE) stores z/OS metadata on versioned files, and which client APIs read it. This is the reference for the z/OS part of `migrate-to-git` and for its hand-off to `ewm2zbuilder`.

These findings come from the EWM 7.2.0 bundles (build `I20251211-0829-r720`): the Build System Toolkit offering's `com.ibm.team.install.rtc.scmtools.common.fbz.plugins` and its EE bundles. Statements marked **(bytecode)** were read from class constants and disassembly. Statements marked **(live)** were confirmed on a 7.2 server with IBM's Mortgage sample (JKE Banking; streams "Mortgage Development Stream" and "Mortgage Production Stream", 5 components, 57 files, 17 zFolders) using `scm migrate-inventory`.

## Where the bundles come from

Plain SCM Tools (`EWM-scmTools-*-7.2.0.zip`) contains **no** EE bundles. The Build System Toolkit's Installation Manager repository ships an SCM Tools plugin set with EE added, in `native/com.ibm.team.install.rtc.scmtools.common.fbz.plugins_7.2.0.*.zip` → `eclipse/plugins/`. It contains the same 7.2 platform bundles plus these:

| Bundle | Used for |
|---|---|
| `com.ibm.team.enterprise.common.common` | property key constants (`IEnterprisePropertyConstants`, `ITeamZConstants`) |
| `com.ibm.team.enterprise.systemdefinition.client` / `.common` | system definition model and client (`ClientFactory`, `ISystemDefinitionModelClient`) |
| `com.ibm.teamz.common` / `com.ibm.teamz.client` | z/OS metadata constants, zComponent helpers |
| `com.ibm.teamz.zimport.cli`, `com.ibm.teamz.filesystem.cli.client` | `zimport` and z/OS `scm` subcommands; reference implementation for the property writes below |

So z/OS-aware migrations must run on this EE-enabled SCM Tools plugin set, and compiling the z/OS code needs it in the target platform. `tools/assemble-ee-scmtools.sh <offering-repo> <dest>` builds one from the toolkit repository without Installation Manager:
- it unpacks the platform's `scmtools.<platform>.main`, `common.ext.plugins` and `common.fbz.plugins` zips;
- it registers the EE bundles in `bundles.info` (the main package lists only the platform bundles; Installation Manager adds the rest);
- it restores the execute bits of `scripts/unix/*`, which `scm` needs to secure `~/.jazz-scm`.

The plugin imports the EE packages with `resolution:=optional`. On plain SCM Tools everything except name resolution works: `migrate-inventory` then reports the definition UUIDs as unresolved.

## Property keys (bytecode, live)

The keys are on `com.ibm.team.enterprise.common.common.IEnterprisePropertyConstants`. They are stored as versionable **user properties** (`IVersionable.getUserProperties()`). The first two and `alwaysload` were seen live; every value was a definition UUID that resolved:

| Key | On | Value |
|---|---|---|
| `team.enterprise.language.definition` | file (member) | item UUID of the language definition (`ILanguageDefinition.getItemId().getUuidValue()`, written by `ZImportCommand`) |
| `team.enterprise.resource.definition` | folder (zFolder) | item UUID of the data set (resource) definition (read by `ZImportCommand` via `IFolder.getUserProperties()`) |
| `team.enterprise.build.var.<name>` | file | per-file build variable (prefix `USER_VARIABLE_PREFIX`); maps to `forFiles` variables in `dbb-app.yaml` |
| `team.enterprise.build.changes.ignoreForDependencyBuild` | file | flag; report only |
| `team.enterprise.build.alwaysload` | file | `true`: the build always loads the member (seen live on the two REXX members of the BIND/REXX zFolders, which have no language definition) |
| `mvsCodePage` | file | the member's MVS code page, when it differs from the default (used for `zos-working-tree-encoding`) |

These five, plus the language definition, are what the z/OS client reads as member metadata (`ZFilesystemRestClient.getMemberMetadata`). The Build System Toolkit's metadata tasks write them as ordinary versioned user properties: the `filemetadata`, `remotefilemetadata` and `globalfilemetadata` tasks call `IVersionable.setUserProperty` and commit a change set. Metadata assigned by rules ("global" metadata) is therefore in the history like any other property change, and the migration picks it up.
| `teamz.user.maintained.properties` | file | list of user-maintained property names (`MetadataConstants.USER_ADDED_PROPERTIES`) |

Other relevant constants:
- `MetadataConstants` (`com.ibm.teamz.common`): `remote.codepage` and `local.codepage` (code pages), `transfer.mode`, and language codes (`COB`, `ASM`, `PLI`, `JCL`, `LNK`, `BND`, `EASY`, `C`, `CPP`, `BIN`, `OTH`, `UNK`, `EMP`).
- `ITeamZConstants`: the zComponent project layout is `zOSsrc/` (source, renamed to `src/` in Git), `.zOSbin/`, `zOSout/` and `.antzBuild/`.

**(live)** On the Mortgage sample:
- Members carry **no code page**: no `remote.codepage`/`local.codepage` user property, file encoding `UTF-8`, content type `text/text`. EWM stores members as Unicode text; the conversion to EBCDIC happens when the build or `zload` writes them to data sets. The Git side therefore needs a configured z/OS code page for `zos-working-tree-encoding` (for example `IBM-1047`); nothing in EWM supplies it per file.
- No member was binary, so the binary flag of `BIN` members is still unconfirmed.
- No `team.enterprise.build.var.*` properties were set.
- Line delimiters are `LF`, except one copybook that is `PLATFORM` in the Production stream only.

## Resolving UUIDs to definitions (bytecode, live)

`zos/EeSystemDefinitions` does this, in batches and with a cache:

```java
ISystemDefinitionModelClient client = ClientFactory.getSystemDefinitionModelClient(teamRepository);
// member property → IZosLanguageDefinition.ITEM_TYPE; zFolder property → model IDataSetDefinition.ITEM_TYPE
ISystemDefinitionHandle h = (ISystemDefinitionHandle) IZosLanguageDefinition.ITEM_TYPE.createItemHandle(UUID.valueOf(value), null);
List<ISystemDefinition> found = client.fetchSystemDefinitionsComplete(List.of(h), false, monitor); // false: do not resolve translators
```

Use the `...systemdefinition.common.model` interfaces, as `zimport` does: `model.IDataSetDefinition` and `IZosLanguageDefinition`. `com.ibm.team.enterprise.zos.systemdefinition.common.IDataSetDefinition` belongs to an older, non-model API. If a batch fails, typically because a definition was deleted, the definitions are fetched one by one and the missing ones are reported as unresolved.

Package `com.ibm.team.enterprise.systemdefinition.common.model` provides:
- `ISystemDefinition` (base type): `getName()` is the join key with the system definition export, which cross-references **by name**. It also has `getDescription()`, `isArchived()`, `getProjectArea()` and `getProperties()`.
- `ILanguageDefinition`: `getLanguageCode()`, `getDefaultPatterns()`, `getDependencyTypes()`, `getScopedProperties()`. The z/OS subtype is `IZosLanguageDefinition`.
- `IResourceDefinition`: `getUsageType()`, `getResourceName()`. The z/OS subtype is `model.IDataSetDefinition`, which adds `getDsName()`, `isPrefixDSN()`, `getRecordFormat()`, `getRecordLength()`, `getBlockSize()`, `getDsType()` and space attributes. Usage type 0 is `DATA_SET_DEF_USAGE_TYPE_ZFOLDER`.

`ZImportCommand.findLanguageDefinition` looks definitions up by project area with `findSystemDefinitions(ITEM_TYPE, projectArea, usageType, archived, …)`. To resolve many UUIDs, fetch all definitions of a project area once and build a UUID → definition map.

**Versioning caveat.** A property stores only the item UUID, not a state. Resolution therefore returns the definition's *current* name, even for historical commits. `computeHistory()` and `getModifiedDateByStateId()` exist if historical names are ever needed. The migration records both the UUID and the resolved name in `.ewm/zos-metadata.json`.

## Live findings that shape Phase 4

From `scm migrate-inventory -r <repo> -o inventory.json "Mortgage Development Stream"`:

- **Layout.** The layout matches `ITeamZConstants`. Each zComponent project is a folder at the component root, with a `.project` (natures `com.ibm.teamz.zcomponent.zComponent` and `com.ibm.teamz.zcomponent.AntzBuild`) and `zOSsrc/<zFolder>/<member>`. `zOSbin`/`zOSout` are not versioned. Non-z/OS projects (`JCL/`, `REXX/`, `wsdl/` without `zOSsrc`) sit next to them in the same components.
- **zFolders.** Only zFolders carry `team.enterprise.resource.definition`, with usage type 0 (zFolder). `zOSsrc` itself and the project folder carry nothing.
- **Data set definitions** hold only the last qualifier (`COBOL`, `COPYBOOK`, `BMS`, `LINK`, `BIND`, `REXX`) with `prefixDsn=true`; the high-level qualifier comes from the build definition. All are FB 80 PDSE. Different projects share one DSD (6 COBOL zFolders → "JKE COBOL source codes").
- **Language definitions** have empty default patterns (`[""]`), so the patterns for zBuilder must come from the actual assignments, as planned.
  - Language codes seen: `COB`, `ASM` (BMS), `OTH` (link-edit).
- **Mixed zFolders.** One zFolder mixes langdefs: `MortgageApplication-JKECMORT/zOSsrc/COBOL` holds three COBOL members with three different langdefs (CICS, CICS&DB2, no CICS). Folder globs alone are not enough; per-file assignments are needed (`forFiles` or explicit paths).
- **Members without a language definition** exist on purpose: BIND and REXX members (`alwaysload`). They are not compiled, so they need no task.
- **Streams.** Development and Production have the same assignments. A langdef change in history did not occur in this sample, so the Phase 4 history test needs a prepared change (on the server, with the user's OK).
- **Not observed, handled defensively.** Archived or deleted definitions (reported as unresolved, by UUID), binary members, and build variables.
- **Not examined.** SCD `IFileSourceCodeData.language`. The language definition is what the EWM build uses, so it stays the source for zBuilder.

## `scm migrate-inventory`

The inventory is a read-only subcommand: `ZosInventoryCmd` builds the document and `ZosInventory` the summary. It reads a stream's or workspace's current configuration through the same `EwmChangeSetDetails` reader the migration uses:

```bash
scm migrate-inventory -r <repo> [-C <component>...] [-o inventory.json] "<stream or workspace>"
```

It prints the following:
- files and folders per language definition and data set definition
- the langdefs found in each zFolder
- members without a langdef, and langdefs outside a zFolder
- all user property keys
- encodings and content types

With `-o`, it also writes every item with its properties, resolved names, and the definitions themselves as JSON. Run it before a migration to see what will be carried over. Its output can contain client data set names: keep it out of this repository.
