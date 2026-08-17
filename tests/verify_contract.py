#!/usr/bin/env python3
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ALLOWLIST = ROOT / "app/src/main/java/com/rjkyker/shizukuai/broker/ActionAllowList.kt"
SCHEMA = ROOT / "schemas/action.schema.json"
EXAMPLE = ROOT / "examples/action.example.json"


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def kotlin_actions(text: str) -> set[str]:
    match = re.search(r"allowedActions\s*=\s*setOf\((.*?)\)", text, re.DOTALL)
    if not match:
        fail("could not locate allowedActions setOf(...) in ActionAllowList.kt")
    actions = set(re.findall(r'"([A-Z][A-Z0-9_]*)"', match.group(1)))
    if not actions:
        fail("Kotlin allowlist is empty")
    return actions


def main() -> None:
    allowlist = kotlin_actions(ALLOWLIST.read_text(encoding="utf-8"))
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    example = json.loads(EXAMPLE.read_text(encoding="utf-8"))

    schema_actions = set(schema.get("properties", {}).get("action", {}).get("enum", []))
    if not schema_actions:
        fail("schema action enum is missing or empty")

    if allowlist != schema_actions:
        only_kotlin = sorted(allowlist - schema_actions)
        only_schema = sorted(schema_actions - allowlist)
        fail(f"action drift detected; only_kotlin={only_kotlin}, only_schema={only_schema}")

    required = set(schema.get("required", []))
    missing = sorted(required - set(example))
    if missing:
        fail(f"example is missing required schema keys: {missing}")

    if example.get("action") not in schema_actions:
        fail(f"example action is not allowlisted: {example.get('action')!r}")

    if schema.get("additionalProperties") is not False:
        fail("schema must reject unknown top-level properties")

    print(f"PASS: contract synchronized across Kotlin/schema/example ({len(allowlist)} actions)")


if __name__ == "__main__":
    main()
