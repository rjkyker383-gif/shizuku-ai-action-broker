package com.rjkyker.shizukuai.broker.priv

/** Pure helpers shared by the broker and the app_process server. JVM-unit-testable. */
object PrivProtocol {
    const val SHELL_UID = 2000
    const val ROOT_UID = 0
    const val NICE_NAME = "rjk_broker_priv"
    const val SERVER_CLASS = "com.rjkyker.shizukuai.broker.priv.PrivServerMain"
    const val METHOD_SEND_BINDER = "sendBinder"
    const val EXTRA_BINDER = "binder"
    const val LOG_PATH = "/data/local/tmp/rjk_broker_priv.log"
    const val RESEND_INTERVAL_MS = 60_000L
    const val MAX_OUTPUT_CHARS = 64 * 1024

    fun authority(pkg: String) = "$pkg.priv"

    /** Server side: only the broker app's uid may use the privileged binder. */
    fun isServerCallerAllowed(callingUid: Int, brokerUid: Int): Boolean = brokerUid > 0 && callingUid == brokerUid

    /** Broker provider side: only shell (2000) or root (0) may hand us a server binder. */
    fun isProviderCallerAllowed(callingUid: Int): Boolean = callingUid == SHELL_UID || callingUid == ROOT_UID

    fun isValidPropKey(key: String): Boolean = Regex("^[A-Za-z0-9._-]{1,92}$").matches(key)

    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Detached launch: kill any old instance, then setsid app_process so the server is re-parented to
     * init and survives the launching Shizuku/ADB shell exiting. Contains no secrets.
     */
    fun startCommand(apkPath: String, pkg: String, brokerUid: Int, versionCode: Int): String {
        require(apkPath.startsWith("/") && apkPath.endsWith(".apk")) { "bad apk path" }
        require(brokerUid > 0) { "bad uid" }
        return "for p in \$(pidof $NICE_NAME); do kill \$p; done; " +
            "CLASSPATH=${q(apkPath)} setsid app_process /system/bin --nice-name=$NICE_NAME $SERVER_CLASS " +
            "--broker-uid=$brokerUid --pkg=$pkg --vc=$versionCode --apk=${q(apkPath)} " +
            "</dev/null >$LOG_PATH 2>&1 &"
    }

    fun startScript(apkPath: String, pkg: String, brokerUid: Int, versionCode: Int): String =
        "#!/system/bin/sh\n# Broker privileged server bootstrap (uid 2000). Run: adb shell sh <this file>\n" +
            "# Needed once after every reboot (no root). Re-run after updating the broker.\n" +
            startCommand(apkPath, pkg, brokerUid, versionCode) + "\necho started $NICE_NAME\n"

    fun adbCommand(scriptPath: String): String = "adb shell sh $scriptPath"

    data class Args(val brokerUid: Int, val pkg: String, val versionCode: Int, val apk: String)

    fun parseArgs(argv: Array<String>): Args {
        var uid = -1; var pkg = "com.rjkyker.shizukuai.broker"; var vc = 0; var apk = ""
        for (a in argv) when {
            a.startsWith("--broker-uid=") -> uid = a.substringAfter('=').toIntOrNull() ?: -1
            a.startsWith("--pkg=") -> pkg = a.substringAfter('=')
            a.startsWith("--vc=") -> vc = a.substringAfter('=').toIntOrNull() ?: 0
            a.startsWith("--apk=") -> apk = a.substringAfter('=')
        }
        return Args(uid, pkg, vc, apk)
    }

    fun cap(s: String): String = if (s.length <= MAX_OUTPUT_CHARS) s else s.substring(0, MAX_OUTPUT_CHARS) + "…[truncated]"
}
