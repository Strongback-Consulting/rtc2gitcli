"""Schema-independent model of an EWM system definition export.

Nothing here knows about zBuilder; it mirrors the XML so it can be checked
against the export directly.
"""

from __future__ import annotations

from dataclasses import dataclass, field

# dsDefUsageType values, inferred from the data (not from IBM docs):
#   0, 1 build-managed datasets named by a suffix (CPY, OBJ) under the build
#        prefix; some of the usage-1 ones are VIO (generic_unit) work files
#   2    "&&" temporary datasets
#   3    existing system libraries / program libs (dsMember = program name)
USAGE_STREAM, USAGE_OUTPUT, USAGE_TEMP, USAGE_EXISTING = 0, 1, 2, 3

CALL_PROGRAM, CALL_COMMAND = 0, 1


@dataclass
class DataSetDef:
    name: str
    ds_name: str
    usage_type: int
    member: str | None = None
    prefix_dsn: bool = True  # EWM omits the attribute when true
    ds_type: str | None = None
    record_format: str | None = None
    record_length: int | None = None
    block_size: int | None = None
    primary: int | None = None
    secondary: int | None = None
    space_units: str | None = None
    directory_blocks: int | None = None
    storage_class: str | None = None
    generic_unit: str | None = None
    description: str | None = None


@dataclass
class Allocation:
    """One DD, or one entry of a concatenation (then dd_name is None)."""

    dd_name: str | None = None
    ds_def: str | None = None  # name of a DataSetDef
    property_name: str | None = None  # build property holding a dataset name
    member: bool = False
    input: bool = False
    output: bool = False
    keep: bool = False
    publish: bool = False
    used_as_input: bool = False
    output_name: str | None = None
    output_name_kind: str | None = None


@dataclass
class Concatenation:
    dd_name: str
    allocations: list[Allocation] = field(default_factory=list)


@dataclass
class Translator:
    name: str
    call_method: int
    max_rc: int
    ds_def: str | None = None  # program/library DataSetDef
    command_member: str | None = None  # REXX EXEC / TSO command (call_method 1)
    default_options: str | None = None
    ddname_list: list[str] = field(default_factory=list)
    non_impacting: bool = False
    description: str | None = None
    # DDs in export order; allocation order matters to the tools being driven.
    dds: list[Allocation | Concatenation] = field(default_factory=list)
    variables: dict[str, str] = field(default_factory=dict)


@dataclass
class DependencyType:
    name: str
    translators: list[str] = field(default_factory=list)


@dataclass
class LangDef:
    name: str
    language_code: str
    translators: list[str] = field(default_factory=list)
    # Raw XML condition fragments, positionally parallel to `translators`
    # ("" = unconditional). Empty list if the langdef has no conditions.
    conditions: list[str] = field(default_factory=list)
    default_scanner: bool = False
    default_patterns: list[str] = field(default_factory=list)
    scanners: list[str] = field(default_factory=list)
    dependency_types: list[DependencyType] = field(default_factory=list)
    scoped_properties: dict[str, str] = field(default_factory=dict)
    non_impacting: bool = False
    description: str | None = None


@dataclass
class SystemDefinition:
    dsdefs: dict[str, DataSetDef] = field(default_factory=dict)
    translators: dict[str, Translator] = field(default_factory=dict)
    langdefs: dict[str, LangDef] = field(default_factory=dict)
    warnings: list[str] = field(default_factory=list)
