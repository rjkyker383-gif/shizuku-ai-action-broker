// Broker privileged server (app_process, uid 2000). Only the broker uid may call it.
package com.rjkyker.shizukuai.broker.priv;

interface IBrokerPrivService {
    int getUid() = 1;
    int getPid() = 2;
    int getVersionCode() = 3;
    /** JSON {exit_code, stdout, stderr, timed_out}. ShellDenylist enforced server-side too. */
    String exec(String command, int timeoutSec) = 10;
    String getprop(String key) = 11;
    void destroy() = 16777114;
}
