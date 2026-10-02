"""Cross-reference checks over a parsed SystemDefinition."""

from __future__ import annotations

from dataclasses import dataclass

from .model import Allocation, Concatenation, SystemDefinition


@dataclass(frozen=True)
class Issue:
    kind: str  # unresolved-dsdef | unresolved-translator
    where: str
    ref: str

    def __str__(self) -> str:
        return f"{self.kind}: {self.where} -> {self.ref!r}"


def iter_allocations(translator):
    for dd in translator.dds:
        if isinstance(dd, Concatenation):
            yield from dd.allocations
        else:
            yield dd


def check(sd: SystemDefinition) -> list[Issue]:
    issues: list[Issue] = []
    for t in sd.translators.values():
        if t.ds_def and t.ds_def not in sd.dsdefs:
            issues.append(Issue("unresolved-dsdef", f"translator {t.name!r}", t.ds_def))
        for a in iter_allocations(t):
            if a.ds_def and a.ds_def not in sd.dsdefs:
                issues.append(
                    Issue("unresolved-dsdef", f"translator {t.name!r} DD {a.dd_name}", a.ds_def)
                )
    for ld in sd.langdefs.values():
        for name in ld.translators:
            if name not in sd.translators:
                issues.append(Issue("unresolved-translator", f"langdef {ld.name!r}", name))
    return issues


def required_properties(sd: SystemDefinition, translator_names: list[str]) -> list[str]:
    """Build properties referenced by allocations (e.g. CPY1..CPYn), in first-use order."""
    seen: dict[str, None] = {}
    for n in translator_names:
        t = sd.translators.get(n)
        if t:
            for a in iter_allocations(t):
                if a.property_name:
                    seen.setdefault(a.property_name)
    return list(seen)
