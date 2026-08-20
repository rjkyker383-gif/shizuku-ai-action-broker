# GRAILCORE Lab Validation Plan

Before standalone publication, verify these fail-closed cases in the local simulator:

1. Unknown node identifier resolves to `DENIED`.
2. Malformed staged payload cannot reach confirmation.
3. Allowed node with invalid params is rejected by schema validation.
4. Changing a node mapping after staging invalidates the staged request and requires restaging.
5. Double-confirm does not create a duplicate simulated action.
6. Editing client JSON cannot turn a denied node into an allowed action.
7. Logs record the attempted node, collapse mapping, confirmation state, and final verdict.
8. Exported JSON uses only canonical action names from `action.schema.json`.
9. Denied nodes never produce an executable broker request.
10. No code in the lab imports or invokes Android, ADB, Shizuku, shell, microphone, camera, credential, surveillance, persistence, or destructive-operation interfaces.

## Release gate

Publish the standalone lab only after the actual Grok-generated application source is reviewed for the above invariants and confirmed free of secrets, tokens, device-control hooks, and unrelated runtime state.
