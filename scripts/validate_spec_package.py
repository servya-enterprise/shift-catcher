#!/usr/bin/env python3
"""Validate the frozen specification package and mutable execution indexes."""

from __future__ import annotations

import hashlib
import json
import pathlib
import re
import sys

import yaml


ROOT = pathlib.Path(__file__).resolve().parents[1]
HTTP_METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def validate_checksums(errors: list[str]) -> None:
    checksums = json.loads((ROOT / "SHA256SUMS.json").read_text(encoding="utf-8"))
    for relative, expected in checksums.items():
        target = ROOT / relative
        if not target.is_file():
            fail(errors, f"checksum target missing: {relative}")
            continue
        content = target.read_bytes()
        # `.gitattributes` normalises these files to LF, so a CRLF working copy hashes to something
        # CI can never reproduce: the checksum passes locally and fails the moment it is pushed.
        # Editors on Windows reintroduce CRLF silently, so the guard is here rather than in a habit.
        if b"\r\n" in content:
            fail(errors, f"CRLF line endings (the repository stores LF): {relative}")
            continue
        actual = hashlib.sha256(content).hexdigest()
        if actual != expected.lower():
            fail(errors, f"checksum mismatch: {relative}")


def validate_wikilinks(errors: list[str]) -> None:
    pattern = re.compile(r"\[\[([^\]]+)\]\]")
    for note in ROOT.rglob("*.md"):
        for raw in pattern.findall(note.read_text(encoding="utf-8")):
            target = raw.split("|", 1)[0].split("#", 1)[0].strip()
            if not target:
                continue
            candidates = []
            for base in (note.parent, ROOT):
                candidates.extend((base / target, base / f"{target}.md"))
            if not any(candidate.exists() for candidate in candidates):
                fail(errors, f"broken wikilink in {note.relative_to(ROOT)}: [[{raw}]]")


def validate_dag_and_coverage(errors: list[str]) -> None:
    work_packages_doc = yaml.safe_load((ROOT / "10-Roadmap/work-packages.yaml").read_text(encoding="utf-8"))
    coverage_doc = yaml.safe_load((ROOT / "10-Roadmap/endpoint-coverage.yaml").read_text(encoding="utf-8"))
    openapi = yaml.safe_load((ROOT / "openapi/poc-openapi.yaml").read_text(encoding="utf-8"))
    manifest = json.loads((ROOT / "MANIFEST.json").read_text(encoding="utf-8"))

    work_packages = work_packages_doc["work_packages"]
    by_id = {item["id"]: item for item in work_packages}
    if len(by_id) != len(work_packages):
        fail(errors, "duplicate work package id")
    if manifest["work_package_count"] != len(work_packages):
        fail(errors, "manifest work package count mismatch")

    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(work_package_id: str) -> None:
        if work_package_id in visiting:
            fail(errors, f"work package DAG cycle at {work_package_id}")
            return
        if work_package_id in visited:
            return
        visiting.add(work_package_id)
        for dependency in by_id[work_package_id].get("depends_on", []):
            if dependency not in by_id:
                fail(errors, f"unknown dependency: {work_package_id} -> {dependency}")
            else:
                visit(dependency)
        visiting.remove(work_package_id)
        visited.add(work_package_id)

    for work_package_id in by_id:
        visit(work_package_id)

    ready = [item for item in work_packages if item["status"] == "READY"]
    if len(ready) > 1:
        fail(errors, f"more than one READY work package: {[item['id'] for item in ready]}")
    for item in ready:
        incomplete = [dependency for dependency in item.get("depends_on", []) if by_id[dependency]["status"] != "DONE"]
        if incomplete:
            fail(errors, f"READY work package {item['id']} has incomplete dependencies: {incomplete}")

    coverage = coverage_doc["endpoints"]
    coverage_by_operation = {(item["method"].lower(), item["path"]): item for item in coverage}
    if coverage_doc["count"] != len(coverage):
        fail(errors, "endpoint coverage declared count mismatch")
    if len({item["id"] for item in coverage}) != len(coverage):
        fail(errors, "duplicate endpoint coverage id")
    if manifest["endpoint_count"] != len(coverage):
        fail(errors, "manifest endpoint count mismatch")

    operations = {
        (method, path)
        for path, path_item in openapi["paths"].items()
        for method in path_item
        if method in HTTP_METHODS
    }
    if operations != set(coverage_by_operation):
        fail(errors, f"OpenAPI/coverage mismatch: openapi_only={sorted(operations - set(coverage_by_operation))}, coverage_only={sorted(set(coverage_by_operation) - operations)}")

    assigned = [endpoint for item in work_packages for endpoint in item.get("endpoints", [])]
    coverage_ids = {item["id"] for item in coverage}
    if set(assigned) != coverage_ids or len(assigned) != len(set(assigned)):
        fail(errors, "work package endpoint assignment is not exactly one-to-one with coverage")
    for item in coverage:
        work_package_id = item["work_package"]
        if work_package_id not in by_id or item["id"] not in by_id[work_package_id]["endpoints"]:
            fail(errors, f"coverage work package mismatch: {item['id']} -> {work_package_id}")

    operation_ids = [
        operation["operationId"]
        for path_item in openapi["paths"].values()
        for method, operation in path_item.items()
        if method in HTTP_METHODS
    ]
    if len(operation_ids) != len(set(operation_ids)):
        fail(errors, "duplicate OpenAPI operationId")

    def resolve_local_reference(reference: str) -> object:
        current: object = openapi
        for token in reference.removeprefix("#/").split("/"):
            token = token.replace("~1", "/").replace("~0", "~")
            if not isinstance(current, dict) or token not in current:
                raise KeyError(reference)
            current = current[token]
        return current

    def walk(value: object) -> None:
        if isinstance(value, dict):
            reference = value.get("$ref")
            if isinstance(reference, str) and reference.startswith("#/"):
                try:
                    resolve_local_reference(reference)
                except KeyError:
                    fail(errors, f"unresolved OpenAPI reference: {reference}")
            for child in value.values():
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)

    walk(openapi)


def main() -> int:
    errors: list[str] = []
    try:
        validate_checksums(errors)
        validate_wikilinks(errors)
        validate_dag_and_coverage(errors)
    except (KeyError, TypeError, ValueError, yaml.YAMLError, json.JSONDecodeError) as exception:
        fail(errors, f"document parse/shape error: {exception}")

    if errors:
        print("spec_validation=FAIL")
        for error in errors:
            print(f"- {error}")
        return 1

    print("spec_validation=PASS checksums=55 work_packages=10 endpoints=42")
    return 0


if __name__ == "__main__":
    sys.exit(main())
