"""Command line: ewm2zbuilder EXPORT.xml -o OUTDIR"""

from __future__ import annotations

import argparse
import collections
import re
import sys
from pathlib import Path

import yaml

from .emit import Output, emit_all
from .parser import parse
from .resolve import check


def validate(out: Output, schema_path: Path) -> list[str]:
    """Validate every emitted file against the zBuilder JSON schema."""
    import json

    from jsonschema import Draft202012Validator

    validator = Draft202012Validator(json.loads(schema_path.read_text()))
    errors = []
    for fname, text in out.files.items():
        for err in validator.iter_errors(yaml.safe_load(text)):
            errors.append(f"{fname}: {err.message[:200]} (at {'/'.join(map(str, err.absolute_path))})")
    return errors


def write_report(path: Path, issues, out: Output, schema_errors: list[str]) -> None:
    lines = []
    if schema_errors:
        lines += [f"SCHEMA ERRORS ({len(schema_errors)})", *schema_errors, ""]
    lines += [f"UNRESOLVED REFERENCES ({len(issues)})", *map(str, issues), ""]
    groups = collections.defaultdict(list)
    for n in out.notes:
        # Group by message with quoted names removed so repeats collapse.
        groups[re.sub(r"'[^']*'|\[[^\]]*\]", "…", n.message)].append(n)
    lines.append("NEEDS REVIEW (grouped; count, then first examples)")
    for key, ns in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        lines.append(f"{len(ns):5d}  {key}")
        lines += [f"         e.g. {n}" for n in ns[:3]]
    lines += ["", f"BUILD PROPERTIES TO DEFINE ({len(out.properties)}): " + ", ".join(out.properties)]
    path.write_text("\n".join(lines) + "\n")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="ewm2zbuilder", description=__doc__)
    ap.add_argument("export", type=Path, help="EWM system definition export (XML)")
    ap.add_argument("-o", "--out", type=Path, default=Path("zbuilder-out"))
    ap.add_argument("--check", action="store_true", help="parse and validate references only; write nothing")
    ap.add_argument("--sources-map", type=Path,
                    help="YAML mapping langdef name or language code (COB, ASM, ...) to a list of glob patterns")
    ap.add_argument("--schema", type=Path, help="zBuilder JSON schema; validate every emitted file")
    args = ap.parse_args(argv)

    sd = parse(args.export)
    issues = check(sd)
    print(f"parsed {len(sd.dsdefs)} dsdefs, {len(sd.translators)} translators, "
          f"{len(sd.langdefs)} langdefs; {len(issues)} unresolved reference(s)")
    for w in sd.warnings:
        print(f"warning: {w}", file=sys.stderr)
    if args.check:
        for i in issues:
            print(i)
        return 1 if issues else 0

    sources_map = yaml.safe_load(args.sources_map.read_text()) if args.sources_map else None
    for key in sources_map or {}:
        if key not in sd.langdefs and key not in {l.language_code for l in sd.langdefs.values()}:
            print(f"warning: sources map key {key!r} matches no language definition or code", file=sys.stderr)
    out = emit_all(sd, sources_map)
    schema_errors = validate(out, args.schema) if args.schema else []

    for fname, text in out.files.items():
        path = args.out / fname
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    report = args.out / "conversion-report.txt"
    write_report(report, issues, out, schema_errors)
    print(f"wrote {len(out.files)} file(s) to {args.out}; {len(out.notes)} item(s) need review "
          f"(see {report})")
    if args.schema:
        print(f"schema validation: {len(schema_errors)} error(s)")
    return 1 if schema_errors else 0
