# EWM 7.2 z/OS source metadata model

How EWM Enterprise Extensions (EE) stores z/OS metadata on versioned files, and which client APIs read it. This is the reference for the z/OS part of `migrate-to-git` and for its hand-off to `ewm2zbuilder`.

These findings come from the EWM 7.2.0 bundles (build `I20251211-0829-r720`): the Build System Toolkit offering's `com.ibm.team.install.rtc.scmtools.common.fbz.plugins` and its EE bundles. Statements marked **(bytecode)** were read from class constants and disassembly. Statements marked **(to verify)** still need a live 7.2 repository.

## Where the bundles come from

Plain SCM Tools (`EWM-scmTools-*-7.2.0.zip`) contains **no** EE bundles. The Build System Toolkit's Installation Manager repository ships an SCM Tools plugin set with EE added, in `native/com.ibm.team.install.rtc.scmtools.common.fbz.plugins_7.2.0.*.zip` → `eclipse/plugins/`. It contains the same 7.2 platform bundles plus these:

| Bundle | Used for |
|---|---|
| `com.ibm.team.enterprise.common.common` | property key constants (`IEnterprisePropertyConstants`, `ITeamZConstants`) |
| `com.ibm.team.enterprise.systemdefinition.client` / `.common` | system definition model and client (`ClientFactory`, `ISystemDefinitionModelClient`) |
| `com.ibm.teamz.common` / `com.ibm.teamz.client` | z/OS metadata constants, zComponent helpers |
| `com.ibm.teamz.zimport.cli`, `com.ibm.teamz.filesystem.cli.client` | `zimport` and z/OS `scm` subcommands; reference implementation for the property writes below |

So z/OS-aware migrations must run on this EE-enabled SCM Tools plugin set, and compiling the z/OS code needs it in the target platform.

## Property keys (bytecode)

The keys are on `com.ibm.team.enterprise.common.common.IEnterprisePropertyConstants`. They are stored as versionable **user properties** (`IVersionable.getUserProperties()`):

| Key | On | Value |
|---|---|---|
| `team.enterprise.language.definition` | file (member) | item UUID of the language definition (`ILanguageDefinition.getItemId().getUuidValue()`, written by `ZImportCommand`) |
| `team.enterprise.resource.definition` | folder (zFolder) | item UUID of the data set (resource) definition (read by `ZImportCommand` via `IFolder.getUserProperties()`) |
| `team.enterprise.build.var.<name>` | file | per-file build variable (prefix `USER_VARIABLE_PREFIX`); maps to `forFiles` variables in `dbb-app.yaml` |
| `team.enterprise.build.changes.ignoreForDependencyBuild` | file | flag; report only |
| `teamz.user.maintained.properties` | file | list of user-maintained property names (`MetadataConstants.USER_ADDED_PROPERTIES`) |

Other relevant constants:
- `MetadataConstants` (`com.ibm.teamz.common`): `remote.codepage` and `local.codepage` (code pages), `transfer.mode`, and language codes (`COB`, `ASM`, `PLI`, `JCL`, `LNK`, `BND`, `EASY`, `C`, `CPP`, `BIN`, `OTH`, `UNK`, `EMP`).
- `ITeamZConstants`: the zComponent project layout is `zOSsrc/` (source, renamed to `src/` in Git), `.zOSbin/`, `zOSout/` and `.antzBuild/`.

**(to verify)** On a real member, check:
- where the code-page properties live (user property or file content encoding)
- whether `zimport` sets the binary flag on `BIN` members through `IFileItem.getContentType()`
- whether `team.enterprise.build.var.*` values contain substitution tokens

## Resolving UUIDs to definitions (bytecode)

```java
ISystemDefinitionModelClient client = ClientFactory.getSystemDefinitionModelClient(teamRepository);
ILanguageDefinitionHandle h = (ILanguageDefinitionHandle)
        ILanguageDefinition.ITEM_TYPE.createItemHandle(UUID.valueOf(value), null);
ILanguageDefinition langdef = (ILanguageDefinition) client.fetchSystemDefinition(h, null, monitor);
```

Package `com.ibm.team.enterprise.systemdefinition.common.model` provides:
- `ISystemDefinition` (base type): `getName()` is the join key with the system definition export, which cross-references **by name**. It also has `getDescription()`, `isArchived()`, `getProjectArea()` and `getProperties()`.
- `ILanguageDefinition`: `getLanguageCode()`, `getDefaultPatterns()`, `getDependencyTypes()`, `getScopedProperties()`. The z/OS subtype is `IZosLanguageDefinition`.
- `IResourceDefinition`: `getUsageType()`, `getResourceName()`. The z/OS subtype is `com.ibm.team.enterprise.zos.systemdefinition.common.IDataSetDefinition`, which adds `getDsName()`, `isPrefixDSN()`, `getRecordFormat()`, `getRecordLength()`, `getBlockSize()` and space attributes.

`ZImportCommand.findLanguageDefinition` looks definitions up by project area with `findSystemDefinitions(ITEM_TYPE, projectArea, usageType, archived, …)`. To resolve many UUIDs, fetch all definitions of a project area once and build a UUID → definition map.

**Versioning caveat.** A property stores only the item UUID, not a state. Resolution therefore returns the definition's *current* name, even for historical commits. `computeHistory()` and `getModifiedDateByStateId()` exist if historical names are ever needed. The migration records both the UUID and the resolved name in `.ewm/zos-metadata.json`.

## Still to verify on a live 7.2 repository
1. Read the property values on a zimported member and zFolder (`scm show properties` / Java API) and confirm the UUIDs resolve as above.
2. Check that the sandbox layout after `scm load` of a zComponent project matches `ITeamZConstants`.
3. Find out how archived or deleted definitions behave when resolved (the UUID may no longer resolve).
4. Find out whether SCD `IFileSourceCodeData.language` adds anything beyond the language definition.
