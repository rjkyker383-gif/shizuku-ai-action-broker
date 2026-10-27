package com.rjkyker.shizukuai.broker.shell

/**
 * Muse-aligned shell denylist enforced on EVERY exec_shell route (shizuku / termux / local / auto).
 * Also blocks the dumpsys forms that hard-froze TC22 system_server on stalled remote shells.
 */
object ShellDenylist {

    data class Verdict(val allowed: Boolean, val reason: String = "")

    private val PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\bpm\s+(uninstall|clear|disable-user|hide)\b""") to "pm uninstall/clear/disable-user/hide is forbidden",
        Regex("""(?i)\brm\s+(-[a-zA-Z]*f[a-zA-Z]*\s+)?(/data|/sdcard|/storage|/system)""") to "rm -rf on data/sdcard/storage/system is forbidden",
        Regex("""(?i)\bwipe\b|\bfactory(\s|_|-)?reset\b""") to "wipe / factory reset is forbidden",
        Regex("""(?i)\bdd\s+if=""") to "dd if= is forbidden",
        Regex("""(?i)am\s+start\b.*\b(android\.intent\.action\.(CALL|SEND|SENDTO|DIAL)|sms:|smsto:|tel:|mailto:)""") to
            "dialer / SMS / send intents are forbidden",
        Regex("""(?i)service\s+call\s+isms""") to "SMS service call is forbidden",
        Regex("""(?i)cmd\s+telecom\b""") to "telecom commands are forbidden",
        Regex("""(?i)settings\s+put\s+secure\s+sms_""") to "SMS settings writes are forbidden",
        Regex("""(?i)\breboot\s+-p\b|\brecovery\b""") to "power-off / recovery is forbidden",
        // dumpsys freezes (bare or AMS-holding services)
        Regex("""(?i)\bdumpsys\b\s*($|[;|&])""") to "bare dumpsys is forbidden (freezes system_server)",
        Regex("""(?i)\bdumpsys\s+(activity|jobscheduler|notification|procstats|cpuinfo)\b""") to
            "dumpsys activity/jobscheduler/notification/procstats/cpuinfo is forbidden",
        Regex("""(?i)\bdumpsys\s+meminfo\s*($|[;|&])""") to "dumpsys meminfo without package is forbidden",
        Regex("""(?i)\bbugreportz?\b""") to "bugreport is forbidden (same stall risk as dumpsys)",
    )

    fun check(command: String): Verdict {
        val c = command.trim()
        if (c.isEmpty()) return Verdict(false, "empty command")
        for ((rx, why) in PATTERNS) {
            if (rx.containsMatchIn(c)) return Verdict(false, why)
        }
        return Verdict(true)
    }
}
