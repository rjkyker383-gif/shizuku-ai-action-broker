# Barcode Ghost Engine (BGX-OMEGA v1)

This branch adds a local-first barcode processing core to the existing DataWedge bridge.
The first slice provides inspection, bounded deterministic transformations, registered
BGX command parsing, local catalog-price records, and PNG label generation.

All endpoints below are served by the existing broker on 127.0.0.1:17999. Keep any
reverse proxy or port-forward restricted to trusted devices.

## Natural-language transformation planner

The on-device screen and broker expose a safe instruction planner. For example:

    curl -s -X POST http://127.0.0.1:17999/action \
      -H 'Content-Type: application/json' \
      -d '{"op":"barcode_plan_instruction","value":"12345","instruction":"Add RJ- to the beginning"}'

The default planner is an offline, deterministic fallback for common simple instructions.
A pluggable BarcodeAiProvider interface is present for a model-backed provider, but no
remote AI provider or credentials are configured in this branch. Unknown instructions
are rejected instead of guessed. A proposal is preview-only and must be explicitly
accepted by the user or calling agent before becoming a working draft.

## Inspect a barcode or the latest scan

    curl -s -X POST http://127.0.0.1:17999/action \
      -H 'Content-Type: application/json' \
      -d '{"op":"barcode_inspect","value":"036000291452","symbology":"UPC-A"}'

## Preview a deterministic transformation

    curl -s -X POST http://127.0.0.1:17999/action \
      -H 'Content-Type: application/json' \
      -d '{"op":"barcode_transform_preview","value":"abc123","operation":"prefix","argument":"RJ-"}'

Supported operations: prefix, suffix, strip_prefix, strip_suffix, replace_first,
replace_all, uppercase, lowercase, trim, and collapse_whitespace. The response is
a preview; it does not mutate the source value, physical label, or an external
database. Regex execution and arbitrary code execution are unsupported.

## Validate a programmable BGX barcode

    curl -s -X POST http://127.0.0.1:17999/action \
      -H 'Content-Type: application/json' \
      -d '{"op":"barcode_command_preview","value":"BGX:1|TYPE=GAME|ID=NEON_MAZE|VERSION=1"}'

Recognized command IDs are allow-listed in code. BGX contents are never evaluated as
scripts. When the app is active, supported GAME and EASTER_EGG codes open the local
Barcode Ghost experience. If the app is not active, a short-lived command is queued
until the user opens it.

Example payloads:

    BGX:1|TYPE=GAME|ID=NEON_MAZE|VERSION=1
    BGX:1|TYPE=GAME|ID=GHOST_CIPHER|VERSION=1
    BGX:1|TYPE=EASTER_EGG|ID=GHOST_MODE|VERSION=1
    BGX:1|TYPE=EASTER_EGG|ID=SPIN_LOGO|VERSION=1

## Edit a local catalog price

    curl -s -X POST http://127.0.0.1:17999/action \
      -H 'Content-Type: application/json' \
      -d '{"op":"barcode_catalog_update","confirm":true,"barcode":"RJ-ITEM-001","productName":"Demo item","catalogPrice":"12.99","currency":"USD"}'

Catalog records are stored locally in app-private files and changes are audited.
This changes only this app's catalog. It does not change retailer pricing, point-of-sale
records, or the original encoded barcode.

## Generate a replacement barcode image

    curl -s -X POST http://127.0.0.1:17999/action \
      -H 'Content-Type: application/json' \
      -d '{"op":"barcode_label_generate","value":"RJ-ITEM-001","format":"QR_CODE","width":512,"height":512}'

The response returns a local PNG path. Scan generated images back before using them
as operational labels. Format checks may reject values that do not match a format's
character or length restrictions.

## Implementation boundary

The transformation and command engine is deterministic and usable offline. A live AI
provider, full product lookup connector, richer catalog UI, additional game mechanics,
and external source synchronization remain follow-on work. The app reports analysis
rather than claiming hidden data that was not present in the barcode or a connected
source.
