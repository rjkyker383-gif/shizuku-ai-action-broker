package com.rjkyker.shizukuai.broker.priv;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;

import com.rjkyker.shizukuai.broker.shell.ShellDenylist;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * Broker privileged server: our own Shizuku-style service. Started once per boot by
 * `app_process` from ADB or the Shizuku shell (uid 2000, NOT root). It hands its binder to the
 * broker through the broker's ContentProvider (the same handoff Shizuku uses), re-sends it every
 * 60 s, and exits by itself when the broker APK it was started from disappears (update/uninstall).
 * Detached with setsid, so it keeps running if the Shizuku server goes away.
 */
public final class PrivServerMain {

    private static PrivProtocol.Args sArgs;

    public static void main(String[] argv) {
        sArgs = PrivProtocol.INSTANCE.parseArgs(argv);
        int uid = Process.myUid();
        if (uid != PrivProtocol.SHELL_UID && uid != PrivProtocol.ROOT_UID) {
            System.err.println("must run as shell/root via app_process, uid=" + uid);
            System.exit(2);
        }
        if (sArgs.getBrokerUid() <= 0 || sArgs.getApk().isEmpty()) {
            System.err.println("missing --broker-uid / --apk");
            System.exit(2);
        }
        try { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(""); } catch (Throwable ignored) { }
        System.out.println("rjk_broker_priv start uid=" + uid + " pid=" + Process.myPid() + " brokerUid=" + sArgs.getBrokerUid());
        Looper.prepareMainLooper();
        final Service service = new Service();
        final Handler h = new Handler(Looper.getMainLooper());
        final String authority = PrivProtocol.INSTANCE.authority(sArgs.getPkg());
        h.post(new Runnable() {
            @Override public void run() {
                if (!new File(sArgs.getApk()).exists()) {
                    System.out.println("broker apk gone (updated/uninstalled); exiting");
                    System.exit(0);
                }
                try {
                    boolean ok = sendBinder(authority, service);
                    System.out.println("sendBinder ok=" + ok);
                } catch (Throwable t) {
                    System.err.println("sendBinder failed: " + t);
                }
                h.postDelayed(this, PrivProtocol.RESEND_INTERVAL_MS);
            }
        });
        Looper.loop();
    }

    /** Shizuku-style handoff: IActivityManager.getContentProviderExternal + IContentProvider.call. */
    static boolean sendBinder(String authority, IBinder binder) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder amBinder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "activity");
        Object am = Class.forName("android.app.IActivityManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, amBinder);
        IBinder token = null;
        Object holder = null;
        Method gcpe = am.getClass().getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class);
        holder = gcpe.invoke(am, authority, 0, token, PrivProtocol.NICE_NAME);
        if (holder == null) return false;
        Object provider = holder.getClass().getField("provider").get(holder);
        Bundle extras = new Bundle();
        extras.putBinder(PrivProtocol.EXTRA_BINDER, binder);
        extras.putInt("uid", Process.myUid());
        extras.putInt("pid", Process.myPid());
        Bundle reply;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                AttributionSource src = new AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build();
                Method call = provider.getClass().getMethod("call", AttributionSource.class, String.class, String.class, String.class, Bundle.class);
                reply = (Bundle) call.invoke(provider, src, authority, PrivProtocol.METHOD_SEND_BINDER, null, extras);
            } else if (Build.VERSION.SDK_INT == 30) {
                Method call = provider.getClass().getMethod("call", String.class, String.class, String.class, String.class, String.class, Bundle.class);
                reply = (Bundle) call.invoke(provider, "com.android.shell", null, authority, PrivProtocol.METHOD_SEND_BINDER, null, extras);
            } else if (Build.VERSION.SDK_INT == 29) {
                Method call = provider.getClass().getMethod("call", String.class, String.class, String.class, String.class, Bundle.class);
                reply = (Bundle) call.invoke(provider, "com.android.shell", authority, PrivProtocol.METHOD_SEND_BINDER, null, extras);
            } else {
                Method call = provider.getClass().getMethod("call", String.class, String.class, String.class, Bundle.class);
                reply = (Bundle) call.invoke(provider, "com.android.shell", PrivProtocol.METHOD_SEND_BINDER, null, extras);
            }
        } finally {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    am.getClass().getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                        .invoke(am, authority, token, 0);
                } else {
                    am.getClass().getMethod("removeContentProviderExternal", String.class, IBinder.class).invoke(am, authority, token);
                }
            } catch (Throwable ignored) { }
        }
        return reply != null && reply.getBoolean("ok", false);
    }

    static final class Service extends IBrokerPrivService.Stub {
        private void enforce() {
            int c = Binder.getCallingUid();
            if (!PrivProtocol.INSTANCE.isServerCallerAllowed(c, sArgs.getBrokerUid())) {
                throw new SecurityException("uid " + c + " may not use the broker privileged service");
            }
        }

        @Override public int getUid() { enforce(); return Process.myUid(); }
        @Override public int getPid() { enforce(); return Process.myPid(); }
        @Override public int getVersionCode() { enforce(); return sArgs.getVersionCode(); }

        @Override public String exec(String command, int timeoutSec) {
            enforce();
            JSONObject o = new JSONObject();
            try {
                ShellDenylist.Verdict v = ShellDenylist.INSTANCE.check(command == null ? "" : command);
                if (!v.getAllowed()) {
                    return o.put("exit_code", -1).put("stdout", "").put("stderr", "denied: " + v.getReason())
                        .put("timed_out", false).put("denied", true).toString();
                }
                int t = Math.max(1, Math.min(timeoutSec, 120));
                java.lang.Process p = new ProcessBuilder("sh", "-c", command).start();
                p.getOutputStream().close();
                StreamReader out = new StreamReader(p.getInputStream());
                StreamReader err = new StreamReader(p.getErrorStream());
                out.start(); err.start();
                boolean done = p.waitFor(t, TimeUnit.SECONDS);
                if (!done) p.destroyForcibly();
                out.join(2000); err.join(2000);
                o.put("exit_code", done ? p.exitValue() : -1)
                    .put("stdout", PrivProtocol.INSTANCE.cap(out.text()))
                    .put("stderr", PrivProtocol.INSTANCE.cap(err.text()))
                    .put("timed_out", !done)
                    .put("uid", Process.myUid());
            } catch (Exception e) {
                try { o.put("exit_code", -1).put("stderr", e.toString()); } catch (Exception ignored) { }
            }
            return o.toString();
        }

        @Override public String getprop(String key) {
            enforce();
            if (key == null || !PrivProtocol.INSTANCE.isValidPropKey(key)) throw new IllegalArgumentException("bad prop key");
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                return (String) sp.getMethod("get", String.class).invoke(null, key);
            } catch (Exception e) {
                return "";
            }
        }

        @Override public void destroy() {
            enforce();
            new Thread(() -> {
                try { Thread.sleep(200); } catch (InterruptedException ignored) { }
                System.out.println("destroy requested by broker");
                System.exit(0);
            }).start();
        }

        @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws RemoteException {
            return super.onTransact(code, data, reply, flags);
        }
    }

    static final class StreamReader extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StreamReader(InputStream in) { this.in = in; setDaemon(true); }
        @Override public void run() {
            byte[] b = new byte[8192];
            try {
                int n;
                while ((n = in.read(b)) > 0) {
                    if (buf.size() < PrivProtocol.MAX_OUTPUT_CHARS * 2) buf.write(b, 0, n);
                }
            } catch (Exception ignored) { }
        }
        String text() { return buf.toString(); }
    }
}
