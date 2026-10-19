# Dhizuku integration (1.5.0)

Dhizuku (`com.rosan.dhizuku`) holds Device Owner and shares it with approved client apps over a binder.

1. `dpm set-device-owner com.rosan.dhizuku/.server.DhizukuDAReceiver` (no accounts on the device at that moment).
2. Install broker 1.5.0 and open it. It calls `Dhizuku.init()`; if not granted it calls
   `Dhizuku.requestPermission()`, which shows Dhizuku's "Allow Shizuku AI Broker to access Dhizuku?" prompt
   and registers the broker in Dhizuku's app list.
3. Tap **Grant** (prompt auto-denies after ~15 s) or enable the broker later in Dhizuku's app list, then use
   **Connect Dhizuku** / `dhizuku_status` to confirm `granted: true`.

`DhizukuGateway.dpm()` creates a `DevicePolicyManager` from Dhizuku's package context and replaces its
`mService` with `IDevicePolicyManager.Stub.asInterface(Dhizuku.binderWrapper(old))`, so DPM calls execute in
Dhizuku's process with the owner component from `Dhizuku.getOwnerComponent()`.

Ops and risk levels live in `ActionAllowList` (authoritative). See README "Dhizuku" for the table and the
list of deliberately excluded destructive calls.
