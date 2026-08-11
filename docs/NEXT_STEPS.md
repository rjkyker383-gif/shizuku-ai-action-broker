# Next steps

The repository now has a minimal Android/Shizuku scaffold. The next implementation milestones are:

1. Generate and commit a Gradle wrapper, then run a clean debug build.
2. Install the debug APK on a test Android device with Shizuku installed and running.
3. Verify binder detection, permission request, denial, and reconnect behavior.
4. Replace string action names with a typed action model and parameter validation per action.
5. Add a confirmation screen that displays the action, reason, exact scope, risk level, and parameters before execution.
6. Implement the first read-only executor actions, starting with device/display/battery information.
7. Add package-related actions only through narrowly scoped, validated parameters.
8. Keep arbitrary shell text out of the AI request format; privileged behavior should map to reviewed broker operations.
9. Add an audit record for every request, approval/denial, execution result, and error without storing secrets.
10. Add unit tests for allow-list rejection, malformed requests, and confirmation requirements.

## Current prototype boundary

This scaffold only detects Shizuku and requests the app's Shizuku permission. It does not yet execute privileged actions.

That boundary is intentional: first prove lifecycle and permission handling, then add one reviewed broker operation at a time.
