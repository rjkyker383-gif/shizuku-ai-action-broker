# GRAILCORE Lab

GRAILCORE Lab is a local-only policy and UX test bench for the Shizuku AI Action Broker.

## Purpose

The lab lets a theatrical catalog of UI nodes collapse into the broker's canonical action surface without expanding device authority. Every node must resolve to either one of the seven canonical allow-listed actions or `DENIED`.

Canonical actions:

- `GET_DEVICE_INFO`
- `GET_PACKAGE_LIST`
- `GET_CURRENT_APP`
- `OPEN_APP`
- `OPEN_SETTINGS`
- `GET_BATTERY_INFO`
- `GET_DISPLAY_INFO`

The authoritative definitions remain `../docs/ALLOWLIST.md` and `../schemas/action.schema.json`.

## Safety boundary

This directory is simulation-only. It must not execute Android, ADB, Shizuku, shell, microphone, camera, surveillance, credential, persistence, or destructive operations.

The browser/UI is never the authority for permission. A simulated confirmation may produce a canonical JSON action request, but the broker-side schema and allow-list remain the final authority.

## Required interaction model

1. Browse/filter nodes by `all`, `allowed`, or `denied`.
2. Inspect a node's canonical collapse mapping.
3. Stage an allowed action before confirmation.
4. Confirm explicitly before producing canonical JSON.
5. Refuse denied nodes and record the refusal in the local browser log.
6. Treat unknown or malformed nodes as denied/fail-closed.

## Publication status

This bootstrap lives temporarily inside `shizuku-ai-action-broker` because the connected GitHub surface used to create it cannot create a brand-new repository. It is intentionally isolated under `grailcore-lab/` so it can be moved unchanged into a standalone `grailcore-lab` repository once that repository is created.
