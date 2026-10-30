package com.rjkyker.shizukuai.broker.autonomy.delivery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.rjkyker.shizukuai.broker.autonomy.AutonomyFlags
import com.rjkyker.shizukuai.broker.autonomy.audit.AuditLog
import com.rjkyker.shizukuai.broker.autonomy.queue.AutonomyDb
import com.rjkyker.shizukuai.broker.autonomy.queue.OutboxEntity
import com.rjkyker.shizukuai.broker.autonomy.registry.Actor
import com.rjkyker.shizukuai.broker.autonomy.registry.PermissionEngine
import com.rjkyker.shizukuai.broker.ui.SendConfirmActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * U10 preview-first outbox. request() never sends: it records the preview and, only if every gate passes,
 * posts a notification that opens [SendConfirmActivity]. Approval exists only as a direct call from that
 * activity's guarded button (no MCP tool, no broadcast, no exported entry point). Approved messages are handed
 * to the target app's own compose screen via ACTION_SEND; the owner taps send there. Never shell/SmsManager/a11y.
 */
object SendGate {
    private const val PREFS = "send_gate"
    private const val K_APPS = "apps"
    private const val K_RECIPIENTS = "recipients"
    private const val CHANNEL = "broker_send_confirm"
    const val STATE_HANDED_OFF = "HANDED_OFF"
    const val STATE_DECLINED = "DECLINED"

    fun appAllowlist(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(K_APPS, emptySet())!!.toSet()

    fun recipientAllowlist(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(K_RECIPIENTS, emptySet())!!.toSet()

    /** Device UI only. */
    fun setAllowlistsFromDevice(ctx: Context, apps: Set<String>, recipients: Set<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(K_APPS, apps.map { it.trim() }.filter { it.isNotEmpty() }.toSet())
            .putStringSet(K_RECIPIENTS, recipients.map { it.trim() }.filter { it.contains('|') }.toSet()).apply()
        AuditLog.record(ctx, Actor.USER_DEVICE, "send_allowlist_edit", PermissionEngine.check(Actor.USER_DEVICE, "send_allowlist_edit"),
            outcome = "apps=${apps.size} recipients=${recipients.size}")
    }

    private fun policyInput(ctx: Context, pkg: String, recipient: String, body: String): SendPolicy.Input {
        val dao = AutonomyDb.get(ctx).outbox()
        val now = System.currentTimeMillis()
        val since = now - 24 * 3600_000L
        return SendPolicy.Input(AutonomyFlags.get(ctx, AutonomyFlags.SEND), appAllowlist(ctx), recipientAllowlist(ctx),
            pkg, recipient, body, now, dao.sentSince(since), dao.sentToSince(recipient, since))
    }

    fun request(ctx: Context, pkg: String, recipient: String, body: String, actor: Actor): JSONObject {
        val v = PermissionEngine.check(actor, "send_request")
        if (!v.allowed) { AuditLog.record(ctx, actor, "send_request", v); return JSONObject().put("ok", false).put("error", v.reason) }
        val d = SendPolicy.evaluate(policyInput(ctx, pkg, recipient, body))
        val id = AutonomyDb.get(ctx).outbox().insert(OutboxEntity(targetPkg = pkg.take(200), recipient = recipient.take(200),
            body = body.take(SendPolicy.MAX_BODY + 1), actor = actor.name, state = d.state, reason = d.reason,
            bodyHash = AuditLog.sha256(body), createdAt = System.currentTimeMillis()))
        AuditLog.record(ctx, actor.name, "send_request", v.tier.name, if (d.pending) "PENDING_USER" else "REJECTED", d.reason,
            JSONObject().put("package", pkg).put("recipient", recipient).put("body", body), outcome = "outbox#$id")
        if (d.pending) notifyPending(ctx, id, pkg, recipient)
        return JSONObject().put("ok", d.pending).put("outbox_id", id).put("state", d.state).put("reason", d.reason)
            .put("sent", false).put("note", "Nothing is sent automatically. The owner must approve on the phone.")
    }

    /** Called only by SendConfirmActivity after a genuine, unobscured touch. Re-checks every gate. */
    internal fun approveFromDevice(ctx: Context, id: Long): Intent? {
        val dao = AutonomyDb.get(ctx).outbox()
        val o = dao.get(id) ?: return null
        if (o.state != SendPolicy.PENDING_CONFIRM) return null
        val d = SendPolicy.evaluate(policyInput(ctx, o.targetPkg, o.recipient, o.body))
        val v = PermissionEngine.check(Actor.USER_DEVICE, "send_approve")
        if (!d.pending || !v.allowed) {
            dao.update(o.copy(state = SendPolicy.REJECTED, reason = "re-check: ${d.reason}", decidedAt = System.currentTimeMillis()))
            AuditLog.record(ctx, Actor.USER_DEVICE.name, "send_approve", v.tier.name, "DENY", d.reason, outcome = "outbox#$id")
            return null
        }
        dao.update(o.copy(state = STATE_HANDED_OFF, reason = "approved on device", decidedAt = System.currentTimeMillis()))
        AuditLog.record(ctx, Actor.USER_DEVICE, "send_approve", v, outcome = "outbox#$id handed to ${o.targetPkg}")
        return Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, o.body)
            .setPackage(o.targetPkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    internal fun declineFromDevice(ctx: Context, id: Long) {
        val dao = AutonomyDb.get(ctx).outbox()
        val o = dao.get(id) ?: return
        if (o.state != SendPolicy.PENDING_CONFIRM) return
        dao.update(o.copy(state = STATE_DECLINED, reason = "declined on device", decidedAt = System.currentTimeMillis()))
        AuditLog.record(ctx, Actor.USER_DEVICE.name, "send_approve", "HIGH", "DECLINED", "owner declined", outcome = "outbox#$id")
    }

    fun get(ctx: Context, id: Long): OutboxEntity? = AutonomyDb.get(ctx).outbox().get(id)

    fun statusJson(ctx: Context, limit: Int = 10): JSONObject = JSONObject().put("ok", true)
        .put("send_enabled", AutonomyFlags.get(ctx, AutonomyFlags.SEND))
        .put("app_allowlist_size", appAllowlist(ctx).size).put("recipient_allowlist_size", recipientAllowlist(ctx).size)
        .put("outbox", JSONArray().also { a ->
            AutonomyDb.get(ctx).outbox().recent(limit).forEach {
                a.put(JSONObject().put("id", it.id).put("package", it.targetPkg).put("state", it.state)
                    .put("reason", it.reason).put("actor", it.actor).put("body_sha256", it.bodyHash).put("created_at", it.createdAt))
            }
        })

    private fun notifyPending(ctx: Context, id: Long, pkg: String, recipient: String) = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Message approvals", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, id.toInt(), Intent(ctx, SendConfirmActivity::class.java)
            .putExtra(SendConfirmActivity.EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(5000 + (id % 1000).toInt(), NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("Approve message?")
            .setContentText("To $recipient via $pkg — review before anything is sent").setContentIntent(pi)
            .setAutoCancel(true).build())
    }
}
