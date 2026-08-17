#!/usr/bin/env python3
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BROKER = ROOT / "app/src/main/java/com/rjkyker/shizukuai/broker"
ALLOWLIST = BROKER / "ActionAllowList.kt"
REQUEST = BROKER / "ActionRequest.kt"
SCHEMA = ROOT / "schemas/action.schema.json"
EXAMPLE = ROOT / "examples/action.example.json"


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def kotlin_policy(text: str) -> dict[str, str]:
    match = re.search(r"actionRiskLevels\s*=\s*mapOf\((.*?)\)\s*\n", text, re.DOTALL)
    if not match:
        fail("could not locate actionRiskLevels mapOf(...) in ActionAllowList.kt")
    pairs = re.findall(r'"([A-Z][A-Z0-9_]*)"\s+to\s+RiskLevel\.([A-Z]+)', match.group(1))
    if not pairs:
        fail("Kotlin action risk policy is empty")
    policy = dict(pairs)
    if len(policy) != len(pairs):
        fail("duplicate action found in Kotlin action risk policy")
    return policy


def main() -> None:
    allowlist_text = ALLOWLIST.read_text(encoding="utf-8")
    request_text = REQUEST.read_text(encoding="utf-8")
    policy = kotlin_policy(allowlist_text)
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    example = json.loads(EXAMPLE.read_text(encoding="utf-8"))

    schema_actions = set(schema.get("properties", {}).get("action", {}).get("enum", []))
    if not schema_actions:
        fail("schema action enum is missing or empty")

    policy_actions = set(policy)
    if policy_actions != schema_actions:
        only_kotlin = sorted(policy_actions - schema_actions)
        only_schema = sorted(schema_actions - policy_actions)
        fail(f"action drift detected; only_kotlin={only_kotlin}, only_schema={only_schema}")

    required = set(schema.get("required", []))
    missing = sorted(required - set(example))
    if missing:
        fail(f"example is missing required schema keys: {missing}")

    if example.get("action") not in schema_actions:
        fail(f"example action is not allowlisted: {example.get('action')!r}")

    if schema.get("additionalProperties") is not False:
        fail("schema must reject unknown top-level properties")

    schema_properties = schema.get("properties", {})
    if "riskLevel" in required or "riskLevel" in schema_properties:
        fail("riskLevel must not be caller-controlled in the request schema")
    if "riskLevel" in example:
        fail("example must not supply caller-controlled riskLevel")

    if re.search(r"val\s+riskLevel\s*:\s*RiskLevel\s*[,)]", request_text):
        fail("ActionRequest constructor must not accept caller-controlled riskLevel")
    if "ActionAllowList.riskLevelFor(action)" not in request_text:
        fail("ActionRequest must derive riskLevel from ActionAllowList")
    if "?: RiskLevel.HIGH" not in request_text:
        fail("unknown actions must fail closed to HIGH risk")

    enum_match = re.search(r"enum class RiskLevel\s*\{(.*?)\}", request_text, re.DOTALL)
    if not enum_match:
        fail("RiskLevel enum is missing")
    enum_values = set(re.findall(r"\b(LOW|MEDIUM|HIGH)\b", enum_match.group(1)))
    unknown_levels = sorted(set(policy.values()) - enum_values)
    if unknown_levels:
        fail(f"policy references undefined risk levels: {unknown_levels}")

    print(
        "PASS: contract synchronized; caller-controlled risk removed; "
        f"authoritative risk policy covers {len(policy)} actions"
    )


if __name__ == "__main__":
    main()
