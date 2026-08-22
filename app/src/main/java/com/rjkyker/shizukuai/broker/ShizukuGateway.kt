package com.rjkyker.shizukuai.broker

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuGateway {
    const val REQUEST_CODE = 1001

    fun isBinderAvailable(): Boolean = runCatching {
        Shizuku.pingBinder()
    }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission() {
        if (!isBinderAvailable()) return
        if (hasPermission()) return
        if (Shizuku.shouldShowRequestPermissionRationale()) return
        Shizuku.requestPermission(REQUEST_CODE)
    }

    fun statusText(): String = when {
        !isBinderAvailable() -> "Shizuku service is not available. Start Shizuku first."
        hasPermission() -> "Shizuku is connected and permission is granted."
        else -> "Shizuku is connected, but this app still needs permission."
    }
}
