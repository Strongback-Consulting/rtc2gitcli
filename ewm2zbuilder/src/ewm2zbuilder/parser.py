"""Parse an EWM system definition export (Ant XML, `ld:` namespace)."""

from __future__ import annotations

import xml.etree.ElementTree as ET
from pathlib import Path

from .model import (
    Allocation,
    Concatenation,
    DataSetDef,
    DependencyType,
    LangDef,
    SystemDefinition,
    Translator,
)

NS = "{antlib:com.ibm.team.enterprise.zos.systemdefinition.toolkit}"


def _b(el: ET.Element, attr: str) -> bool:
    return el.get(attr, "").lower() == "true"


def _i(el: ET.Element, attr: str) -> int | None:
    v = el.get(attr)
    return int(v) if v not in (None, "") else None


def _s(el: ET.Element, attr: str) -> str | None:
    v = el.get(attr)
    return v if v else None


def _csv(value: str | None) -> list[str]:
    return [v for v in (value or "").split(",") if v]


def _allocation(el: ET.Element) -> Allocation:
    return Allocation(
        dd_name=_s(el, "name"),
        ds_def=_s(el, "dataSetDefinition"),
        property_name=_s(el, "propertyName"),
        member=_b(el, "member"),
        input=_b(el, "input"),
        output=_b(el, "output"),
        keep=_b(el, "keep"),
        publish=_b(el, "publish"),
        used_as_input=_b(el, "usedAsInput"),
        output_name=_s(el, "outputName"),
        output_name_kind=_s(el, "outputNameKind"),
    )


def _dsdef(el: ET.Element) -> DataSetDef:
    return DataSetDef(
        name=el.get("name", ""),
        ds_name=el.get("dsName", ""),
        usage_type=_i(el, "dsDefUsageType") or 0,
        member=_s(el, "dsMember"),
        prefix_dsn=el.get("prefixDSN", "true").lower() == "true",
        ds_type=_s(el, "dsType"),
        record_format=_s(el, "recordFormat"),
        record_length=_i(el, "recordLength"),
        block_size=_i(el, "blockSize"),
        primary=_i(el, "primaryQuantity"),
        secondary=_i(el, "secondaryQuantity"),
        space_units=_s(el, "spaceUnits"),
        directory_blocks=_i(el, "directoryBlocks"),
        storage_class=_s(el, "storageClass"),
        generic_unit=_s(el, "genericUnit"),
        description=_s(el, "description"),
    )


def _translator(el: ET.Element) -> Translator:
    t = Translator(
        name=el.get("name", ""),
        call_method=_i(el, "callMethod") or 0,
        max_rc=_i(el, "maxRC") or 0,
        ds_def=_s(el, "dataSetDefinition"),
        command_member=_s(el, "commandMember"),
        default_options=_s(el, "defaultOptions"),
        # Keep empty slots: ddnamelist is positional ("SYSLIN,,,SYSLIB").
        ddname_list=el.get("ddnamelist", "").split(",") if el.get("ddnamelist") else [],
        non_impacting=_b(el, "nonImpacting"),
        description=_s(el, "description"),
    )
    for child in el:
        tag = child.tag.removeprefix(NS)
        if tag == "allocation":
            t.dds.append(_allocation(child))
        elif tag == "concatenation":
            t.dds.append(
                Concatenation(
                    dd_name=child.get("name", ""),
                    allocations=[_allocation(a) for a in child if a.tag == NS + "allocation"],
                )
            )
        elif tag == "variable":
            t.variables[child.get("name", "")] = child.get("value", "")
    return t


def _langdef(el: ET.Element, warnings: list[str]) -> LangDef:
    translators = _csv(el.get("translators"))
    # Conditions are positional, so split without dropping empty entries.
    conditions = el.get("conditions", "").split(",") if el.get("conditions") else []
    raw_translators = el.get("translators", "")
    if conditions and len(conditions) != len(raw_translators.split(",")):
        warnings.append(
            f"langdef {el.get('name')!r}: conditions/translators length mismatch; "
            "conditions dropped"
        )
        conditions = []
    ld = LangDef(
        name=el.get("name", ""),
        language_code=el.get("languageCode", ""),
        translators=translators,
        conditions=conditions,
        default_scanner=_b(el, "defaultScanner"),
        default_patterns=_csv(el.get("defaultpatterns")),
        non_impacting=_b(el, "nonImpacting"),
        description=_s(el, "description"),
    )
    for child in el:
        tag = child.tag.removeprefix(NS)
        if tag == "scanner":
            ld.scanners.append(child.get("name", ""))
        elif tag == "dependencytype":
            ld.dependency_types.append(
                DependencyType(child.get("name", ""), _csv(child.get("translators")))
            )
        elif tag == "scopedProperty":
            ld.scoped_properties[child.get("name", "")] = child.get("value", "")
    return ld


def parse(path: str | Path) -> SystemDefinition:
    root = ET.parse(path).getroot()
    sd = SystemDefinition()

    def add(table: dict, key: str, value, kind: str) -> None:
        if key in table:
            sd.warnings.append(f"duplicate {kind} name {key!r}; last definition wins")
        table[key] = value

    for el in root.iter(NS + "dsdef"):
        d = _dsdef(el)
        add(sd.dsdefs, d.name, d, "dsdef")
    for el in root.iter(NS + "translator"):
        t = _translator(el)
        add(sd.translators, t.name, t, "translator")
    for el in root.iter(NS + "langdef"):
        ld = _langdef(el, sd.warnings)
        add(sd.langdefs, ld.name, ld, "langdef")
    return sd
