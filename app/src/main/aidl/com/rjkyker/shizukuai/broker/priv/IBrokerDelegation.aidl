// Dhizuku-style delegation of the broker's device-owner rights to approved client apps.
// Bind: action com.rjkyker.shizukuai.broker.DELEGATION, package com.rjkyker.shizukuai.broker.
package com.rjkyker.shizukuai.broker.priv;

interface IBrokerDelegation {
    int getVersion() = 1;
    /** JSON {client, state, allowed_ops}. */
    String status() = 2;
    /** Puts the calling app in PENDING; the owner approves it on-device in the broker dashboard. */
    String requestConsent() = 3;
    /** Runs an owner_policy op (client subset only) for an ALLOWED client. JSON result. */
    String call(String op, String argsJson) = 4;
}
