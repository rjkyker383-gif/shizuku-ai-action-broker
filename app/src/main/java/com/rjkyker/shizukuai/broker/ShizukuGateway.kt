package com.rjkyker.shizukuai.broker

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

data class ShizukuStatus(
    val binderAlive: Boolean,
    val permissionGranted: Boolean,
    val backendUid: Int?
)

sealed interface PermissionRequestResult {
    data object AlreadyGranted : PermissionRequestResult
    data object Requested : PermissionRequestResult
    data object BinderUnavailable : PermissionRequestResult
    data object RationaleRequired : PermissionRequestResult
    data class Failed(val cause: Throwable?) : PermissionRequestResult
}

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

    fun requestPermission(requestCode: Int): PermissionRequestResult {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return PermissionRequestResult.BinderUnavailable
        }

        val alreadyGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        if (alreadyGranted) {
            return PermissionRequestResult.AlreadyGranted
        }

        val rationaleRequired = runCatching {
            Shizuku.shouldShowRequestPermissionRationale()
        }.getOrDefault(false)

        if (rationaleRequired) {
            return PermissionRequestResult.RationaleRequired
        }

        return runCatching {
            Shizuku.requestPermission(requestCode)
            PermissionRequestResult.Requested
        }.getOrElse {
            PermissionRequestResult.Failed(it)
        }
    }
}
