package com.rjkyker.shizukuai.broker

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

data class ShizukuStatus(
    val binderAlive: Boolean,
    val permissionGranted: Boolean,
    val backendUid: Int?
)

object ShizukuGateway {

    fun status(): ShizukuStatus {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            return ShizukuStatus(
                binderAlive = false,
                permissionGranted = false,
                backendUid = null
            )
        }

        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        val uid = if (permissionGranted) {
            runCatching { Shizuku.getUid() }.getOrNull()
        } else {
            null
        }

        return ShizukuStatus(
            binderAlive = true,
            permissionGranted = permissionGranted,
            backendUid = uid
        )
    }

    fun requestPermission(requestCode: Int): Boolean {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return false
        }

        if (runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        ) {
            return true
        }

        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
            return false
        }

        return runCatching {
            Shizuku.requestPermission(requestCode)
            true
        }.getOrDefault(false)
    }
}
