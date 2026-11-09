#!/usr/bin/env python3
"""Validate an export against checked-out ISA/MARS schemas, without submitting any data."""
import argparse
import json
from pathlib import Path

from jsonschema import Draft4Validator, Draft202012Validator, FormatChecker
from referencing import Registry, Resource


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export", type=Path)
    parser.add_argument("--isa-schema-dir", type=Path, required=True)
    parser.add_argument("--mars-schema", type=Path, required=True)
    args = parser.parse_args()
    document = json.loads(args.export.read_text())
    schema_dir = args.isa_schema_dir.resolve()
    registry = Registry().with_resources([
        (path.as_uri(), Resource.from_contents(json.loads(path.read_text())))
        for path in schema_dir.glob("*.json")
    ])
    isa = Draft4Validator({"$ref": (schema_dir / "investigation_schema.json").as_uri()},
                          registry=registry, format_checker=FormatChecker())
    mars = Draft202012Validator(json.loads(args.mars_schema.read_text()))
    failed = False
    # MARS publishes an IsaJson wrapper schema; ISA itself uses the Investigation as the document root.
    for name, validator, value in (("ISA-JSON", isa, document),
                                   ("MARS schema", mars, {"investigation": document})):
        errors = list(validator.iter_errors(value))
        for error in errors:
            print(f"{name}: /{'/'.join(map(str, error.absolute_path))}: {error.message}")
        print(f"{name}: {'FAIL' if errors else 'PASS'}")
        failed |= bool(errors)
    definitions, references = {}, set()

    def visit(node):
        if isinstance(node, dict):
            if "@id" in node:
                if len(node) == 1:
                    references.add(node["@id"])
                else:
                    definitions.setdefault(node["@id"], []).append(node)
            for child in node.values():
                visit(child)
        elif isinstance(node, list):
            for child in node:
                visit(child)

    visit(document)
    unresolved = sorted(references - definitions.keys())
    print(f"Internal references: {'FAIL ' + str(unresolved) if unresolved else 'PASS'}")
    # A repeated definition is only harmless if it is identical; otherwise consumers pick one arbitrarily.
    conflicting = sorted(key for key, nodes in definitions.items()
                         if any(node != nodes[0] for node in nodes[1:]))
    print(f"Unique definitions: {'FAIL ' + str(conflicting) if conflicting else 'PASS'}")
    # Repository adapters walk backwards from each data file to the process that produced it.
    unproduced = []
    for study in document.get("studies", []):
        for assay in study.get("assays", []):
            outputs = {output.get("@id") for process in assay.get("processSequence", [])
                       for output in process.get("outputs", [])}
            unproduced += [file["@id"] for file in assay.get("dataFiles", []) if file.get("@id") not in outputs]
    print(f"Data files produced by a process: {'FAIL ' + str(sorted(unproduced)) if unproduced else 'PASS'}")
    print("These checks do not establish MARS adapter compatibility or ENA submission readiness.")
    raise SystemExit(1 if failed or unresolved or conflicting or unproduced else 0)


if __name__ == "__main__":
    main()
