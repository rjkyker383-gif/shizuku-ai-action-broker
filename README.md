# Shizuku AI Action Broker

Permissioned AI-to-Shizuku action broker for Android, enabling chatbots to request elevated device actions through structured commands, explicit user confirmation, and a strict allow-list.

## Current status

The project now contains a minimal Android application scaffold with Shizuku API/provider dependencies, Shizuku binder detection, and an explicit permission-request UI.

Privileged action execution is intentionally not implemented yet. The next milestone is to validate the lifecycle/permission path on a real device, then add one reviewed, typed broker action at a time.

## Core flow

```text
AI chatbot
   ↓
Structured ActionRequest
   ↓
Schema + allow-list validation
   ↓
User-visible confirmation
   ↓
Approved broker operation
   ↓
Shizuku
   ↓
Result returned to the user
```

## Design rules

- No arbitrary AI-generated shell strings.
- No silent execution.
- No hidden microphone/camera capture or background surveillance.
- No credential collection.
- Privileged operations must be narrow, typed, reviewed, and allow-listed.
- The user must remain in control of sensitive operations.
- Execution results should be auditable without logging secrets.

## Repository layout

- `app/` — Android app and broker code.
- `schemas/` — structured action request schema.
- `examples/` — example AI action requests.
- `docs/ARCHITECTURE.md` — high-level broker architecture.
- `docs/ALLOWLIST.md` — initial action policy.
- `docs/NEXT_STEPS.md` — implementation milestones.

## Shizuku integration

The prototype uses the official Shizuku API and provider libraries. The first screen reports whether the Shizuku binder is available and lets the user explicitly grant permission to this app.

See `docs/NEXT_STEPS.md` for the build/test sequence and the next implementation slice.
