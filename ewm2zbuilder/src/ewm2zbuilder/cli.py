"""Command line.

  ewm2zbuilder EXPORT.xml -o OUTDIR        system definitions -> shared zBuilder configuration
  ewm2zbuilder app METADATA --language-map OUTDIR/language-map.yaml -o dbb-app.yaml
  ewm2zbuilder layout MIRROR TARGET [--language-map OUTDIR/language-map.yaml]
"""

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

LANGUAGE_MAP = "language-map.yaml"


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
    lines.append(f"VARIANT FAMILIES ({len(out.families)}): one task each, variants set per file in dbb-app.yaml")
    for fam in out.families:
        lines.append(f"  {fam.task}")
        lines += [f"    {m}: {vals}" for m, vals in fam.members.items()]
    lines.append("")
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


def app_main(argv: list[str]) -> int:
    """ewm2zbuilder app METADATA --language-map MAP -o dbb-app.yaml"""
    import json

    from .app import DEFAULT_RENAMES, build
    from .emit import SCHEMA_VERSION, _dump

    ap = argparse.ArgumentParser(prog="ewm2zbuilder app",
                                 description="dbb-app.yaml from .ewm/zos-metadata.json of a migrated repository")
    ap.add_argument("metadata", type=Path, help=".ewm/zos-metadata.json written by scm migrate-to-git")
    ap.add_argument("--language-map", type=Path, required=True,
                    help=f"{LANGUAGE_MAP} written by the system definition conversion")
    ap.add_argument("-o", "--out", type=Path, default=Path("dbb-app.yaml"))
    ap.add_argument("--rename", action="append", metavar="OLD=NEW",
                    help="folder renamed by the repository layout (default zOSsrc=src); 'none' for no renames")
    ap.add_argument("--path-prefix", default="**/", help="prefix of every file pattern (default **/)")
    ap.add_argument("--schema", type=Path, help="zBuilder JSON schema; validate the result")
    args = ap.parse_args(argv)

    renames = DEFAULT_RENAMES
    if args.rename:
        renames = () if args.rename == ["none"] else tuple(tuple(r.split("=", 1)) for r in args.rename)
    config = build(json.loads(args.metadata.read_text()), yaml.safe_load(args.language_map.read_text()) or {},
                   SCHEMA_VERSION, renames, args.path_prefix)
    text = _dump(config.document)
    errors = []
    if args.schema:
        from jsonschema import Draft202012Validator

        validator = Draft202012Validator(json.loads(args.schema.read_text()))
        errors = [f"{e.message[:200]} (at {'/'.join(map(str, e.absolute_path))})"
                  for e in validator.iter_errors(config.document)]
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(text)
    for note in config.notes:
        print(f"note: {note}", file=sys.stderr)
    for error in errors:
        print(f"schema: {error}", file=sys.stderr)
    print(f"wrote {args.out}: {len(config.document['tasks'])} task(s); {len(config.notes)} note(s)")
    return 1 if errors else 0


def layout_main(argv: list[str]) -> int:
    """ewm2zbuilder layout MIRROR TARGET [--language-map MAP]"""
    from .app import DEFAULT_RENAMES, build
    from .emit import SCHEMA_VERSION, _dump
    from .layout import metadata_at, transform

    ap = argparse.ArgumentParser(prog="ewm2zbuilder layout",
                                 description="copy the migration mirror into a repository in the target layout")
    ap.add_argument("mirror", type=Path, help="repository written by scm migrate-to-git (the sandbox)")
    ap.add_argument("target", type=Path, help="new repository (must not exist or be empty)")
    ap.add_argument("--branch", default="main")
    ap.add_argument("--rename", action="append", metavar="OLD=NEW",
                    help="folder rename (default zOSsrc=src); 'none' for no renames")
    ap.add_argument("--language-map", type=Path,
                    help=f"{LANGUAGE_MAP} of the shared configuration: add dbb-app.yaml in a last commit")
    ap.add_argument("--path-prefix", default="**/")
    ap.add_argument("--schema", type=Path, help="zBuilder JSON schema; dbb-app.yaml must validate")
    args = ap.parse_args(argv)

    renames = DEFAULT_RENAMES
    if args.rename:
        renames = () if args.rename == ["none"] else tuple(tuple(r.split("=", 1)) for r in args.rename)
    app_yaml = None
    if args.language_map:
        metadata = metadata_at(args.mirror)
        if metadata is None:
            print("no .ewm/zos-metadata.json in the mirror: no dbb-app.yaml", file=sys.stderr)
        else:
            config = build(metadata, yaml.safe_load(args.language_map.read_text()) or {}, SCHEMA_VERSION,
                           renames, args.path_prefix)
            for note in config.notes:
                print(f"note: {note}", file=sys.stderr)
            if args.schema:
                import json

                from jsonschema import Draft202012Validator

                errors = list(Draft202012Validator(json.loads(args.schema.read_text())).iter_errors(config.document))
                for error in errors:
                    print(f"schema: {error.message[:200]}", file=sys.stderr)
                if errors:
                    return 1
            app_yaml = _dump(config.document).encode("utf-8")
    result = transform(args.mirror, args.target, args.branch, renames, app_yaml)
    print(f"{args.target}: {result.commits} commit(s), {len(result.tags)} tag(s)"
          f"{', plus dbb-app.yaml' if app_yaml else ''}; {args.branch} at {result.head[:12]}")
    return 0


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if argv[:1] == ["app"]:
        return app_main(argv[1:])
    if argv[:1] == ["layout"]:
        return layout_main(argv[1:])
    ap = argparse.ArgumentParser(prog="ewm2zbuilder", description=__doc__)
    ap.add_argument("export", type=Path, help="EWM system definition export (XML)")
    ap.add_argument("-o", "--out", type=Path, default=Path("zbuilder-out"))
    ap.add_argument("--check", action="store_true", help="parse and validate references only; write nothing")
    ap.add_argument("--sources-map", type=Path,
                    help="YAML mapping langdef name or language code (COB, ASM, ...) to a list of glob patterns")
    ap.add_argument("--schema", type=Path, help="zBuilder JSON schema; validate every emitted file")
    ap.add_argument("--no-variants", action="store_true",
                    help="one task per language definition; do not merge CICS/DB2 variants of one compiler")
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
    out = emit_all(sd, sources_map, variants=not args.no_variants)
    schema_errors = validate(out, args.schema) if args.schema else []

    for fname, text in out.files.items():
        path = args.out / fname
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    # not a zBuilder file: the contract with `ewm2zbuilder app` (language definition -> task + variables)
    (args.out / LANGUAGE_MAP).write_text(
        "# EWM language definition -> zBuilder language task and the variables that select its variant\n"
        + yaml.safe_dump(out.language_map, sort_keys=True, width=100_000))
    report = args.out / "conversion-report.txt"
    write_report(report, issues, out, schema_errors)
    print(f"wrote {len(out.files)} file(s) to {args.out}; {len(out.notes)} item(s) need review "
          f"(see {report})")
    if args.schema:
        print(f"schema validation: {len(schema_errors)} error(s)")
    return 1 if schema_errors else 0
