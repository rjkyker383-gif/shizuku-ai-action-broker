package com.rjkyker.shizukuai.broker.owner

import com.rjkyker.shizukuai.broker.owner.OwnerPolicyCatalog.Caller
import com.rjkyker.shizukuai.broker.owner.OwnerPolicyCatalog.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OwnerPolicyCatalogTest {

    private val lockouts = listOf("camera", "lock_task_packages", "always_on_vpn", "permitted_accessibility",
        "permitted_input_methods", "keyguard_disabled", "lockout_restriction")

    @Test fun lockoutOpsDeniedOverMcpEvenWithConfirm() {
        lockouts.forEach { op ->
            assertEquals(Tier.LOCKOUT, OwnerPolicyCatalog.tierOf(op))
            assertFalse(op, OwnerPolicyCatalog.decide(op, Caller.MCP, true).allowed)
            assertFalse(op, OwnerPolicyCatalog.decide(op, Caller.DELEGATED_CLIENT, true).allowed)
        }
    }

    @Test fun lockoutOpsDashboardNeedsConfirm() {
        lockouts.forEach { op ->
            assertFalse(OwnerPolicyCatalog.decide(op, Caller.DASHBOARD, false).allowed)
            assertTrue(OwnerPolicyCatalog.decide(op, Caller.DASHBOARD, true).allowed)
        }
    }

    @Test fun highOpsNeedConfirmAndAreNeverDelegated() {
        OwnerPolicyCatalog.OPS.filterValues { it == Tier.HIGH }.keys.forEach { op ->
            assertFalse(op, OwnerPolicyCatalog.decide(op, Caller.MCP, false).allowed)
            assertTrue(op, OwnerPolicyCatalog.decide(op, Caller.MCP, true).allowed)
            assertFalse(op, OwnerPolicyCatalog.decide(op, Caller.DELEGATED_CLIENT, true).allowed)
        }
        listOf("block_uninstall", "clear_app_storage", "reboot", "screen_capture").forEach {
            assertEquals(Tier.HIGH, OwnerPolicyCatalog.tierOf(it))
        }
    }

    @Test fun forbiddenOpsRefusedEverywhereAndNotImplemented() {
        listOf("wipe", "wipe_data", "factory_reset", "reset_password", "set_password", "clear_device_owner",
            "remove_device_owner", "transfer_ownership", "max_failed_passwords_for_wipe", "zebra_license").forEach { op ->
            assertEquals(Tier.FORBIDDEN, OwnerPolicyCatalog.tierOf(op))
            Caller.values().forEach { c -> assertFalse(OwnerPolicyCatalog.decide(op, c, true).allowed) }
            assertFalse(OwnerPolicyCatalog.OPS.containsKey(op))
        }
    }

    @Test fun engineSourceNeverCallsWipeOrResetPassword() {
        val src = File("src/main/java/com/rjkyker/shizukuai/broker/owner/OwnerPolicyEngine.kt").readText()
            .lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
        listOf("wipeData(", "wipeDevice(", "resetPassword(", "resetPasswordWithToken(", "setResetPasswordToken(",
            "clearDeviceOwnerApp(", "clearProfileOwner(", "transferOwnership(", "setMaximumFailedPasswordsForWipe(",
            "removeActiveAdmin(", "installSystemUpdate(", "requestBugreport(").forEach {
            assertFalse("engine must not call $it", src.contains(it))
        }
    }

    @Test fun readAndMediumAllowedOverMcpWithoutConfirm() {
        listOf("probe", "features", "app_info", "hide", "suspend", "permission", "restriction").forEach {
            assertTrue(it, OwnerPolicyCatalog.decide(it, Caller.MCP, false).allowed)
        }
    }

    @Test fun delegatedClientsOnlyGetClientSubset() {
        assertTrue(OwnerPolicyCatalog.decide("hide", Caller.DELEGATED_CLIENT, false).allowed)
        assertFalse(OwnerPolicyCatalog.decide("lock_now", Caller.DELEGATED_CLIENT, false).allowed)
        assertFalse(OwnerPolicyCatalog.decide("delegates_get", Caller.DELEGATED_CLIENT, false).allowed)
        OwnerPolicyCatalog.CLIENT_OPS.forEach {
            val t = OwnerPolicyCatalog.tierOf(it)
            assertTrue(it, t == Tier.READ || t == Tier.MEDIUM)
        }
    }

    @Test fun unknownOpRejected() {
        val d = OwnerPolicyCatalog.decide("rm -rf", Caller.DASHBOARD, true)
        assertFalse(d.allowed); assertNull(d.tier)
    }

    @Test fun protectedAndSelfPackagesRejected() {
        listOf("com.rjkyker.shizukuai.broker", "com.rosan.dhizuku", "moe.shizuku.privileged.api",
            "com.tailscale.ipn", "com.termux", "com.zebra.mdna.els", "com.android.settings").forEach {
            assertNotNull(it, OwnerPolicyCatalog.checkPackage(it, "block_uninstall"))
        }
        assertNotNull(OwnerPolicyCatalog.checkPackage("", "hide"))
        assertNotNull(OwnerPolicyCatalog.checkPackage("bad pkg; rm", "hide"))
        assertNull(OwnerPolicyCatalog.checkPackage("com.example.app", "hide"))
    }

    @Test fun restrictionTiering() {
        assertNull(OwnerPolicyCatalog.checkRestriction("no_config_bluetooth", false))
        assertNotNull(OwnerPolicyCatalog.checkRestriction("no_debugging_features", false))
        assertNull(OwnerPolicyCatalog.checkRestriction("no_debugging_features", true))
        assertNotNull(OwnerPolicyCatalog.checkRestriction("no_config_bluetooth", true))
        assertNotNull(OwnerPolicyCatalog.checkRestriction("no_such_thing", false))
        assertTrue(OwnerPolicyCatalog.SAFE_RESTRICTIONS.intersect(OwnerPolicyCatalog.LOCKOUT_RESTRICTIONS).isEmpty())
        listOf("no_install_apps", "no_uninstall_apps", "no_config_vpn", "no_factory_reset", "no_camera").forEach {
            assertTrue(it, it in OwnerPolicyCatalog.LOCKOUT_RESTRICTIONS)
        }
    }

    @Test fun parityInventoryIsConsistent() {
        val f = OwnDroidParity.FEATURES
        assertTrue(f.size >= 60)
        f.filter { it.status == OwnDroidParity.Status.SKIPPED }.forEach { assertTrue(it.name, it.how.isNotBlank()) }
        assertTrue(f.none { it.status == OwnDroidParity.Status.ADDED && it.name.contains("Wipe", true) })
        val j = OwnDroidParity.toJson()
        assertEquals(f.size, j.getJSONArray("features").length())
    }
}
