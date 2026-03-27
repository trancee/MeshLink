package io.meshlink.transport

import io.meshlink.transport.PermissionChecker.Permission
import io.meshlink.transport.PermissionChecker.PermissionStatus
import io.meshlink.transport.PermissionChecker.PermissionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionCheckerTest {

    // --- AlwaysGrantedPermissionChecker ---

    @Test
    fun alwaysGranted_grantsEveryPermission() {
        val checker = AlwaysGrantedPermissionChecker()
        for (perm in Permission.entries) {
            assertEquals(PermissionStatus.GRANTED, checker.check(perm))
        }
    }

    @Test
    fun alwaysGranted_allGrantedReturnsTrue() {
        val checker = AlwaysGrantedPermissionChecker()
        assertTrue(checker.allGranted())
    }

    @Test
    fun alwaysGranted_checkAllReturnsEmptyList() {
        val checker = AlwaysGrantedPermissionChecker()
        assertEquals(emptyList(), checker.checkAll())
    }

    // --- ConfigurablePermissionChecker ---

    @Test
    fun configurable_deniedPermissionReturnsDenied() {
        val checker = ConfigurablePermissionChecker(
            statuses = mapOf(Permission.BLUETOOTH_SCAN to PermissionStatus.DENIED),
        )
        assertEquals(PermissionStatus.DENIED, checker.check(Permission.BLUETOOTH_SCAN))
    }

    @Test
    fun configurable_allGrantedReturnsFalseWhenAnyDenied() {
        val checker = ConfigurablePermissionChecker(
            statuses = mapOf(Permission.BLUETOOTH_CONNECT to PermissionStatus.DENIED),
        )
        assertFalse(checker.allGranted())
    }

    @Test
    fun configurable_checkAllReturnsDeniedPermissions() {
        val checker = ConfigurablePermissionChecker(
            statuses = mapOf(
                Permission.BLUETOOTH_SCAN to PermissionStatus.DENIED,
                Permission.LOCATION to PermissionStatus.RESTRICTED,
            ),
        )
        val results = checker.checkAll()
        assertEquals(2, results.size)
        assertTrue(results.contains(PermissionResult(Permission.BLUETOOTH_SCAN, PermissionStatus.DENIED)))
        assertTrue(results.contains(PermissionResult(Permission.LOCATION, PermissionStatus.RESTRICTED)))
    }

    @Test
    fun configurable_mixedStatusesReportedCorrectly() {
        val checker = ConfigurablePermissionChecker(
            statuses = mapOf(
                Permission.BLUETOOTH_SCAN to PermissionStatus.GRANTED,
                Permission.BLUETOOTH_CONNECT to PermissionStatus.DENIED,
                Permission.BLUETOOTH_ADVERTISE to PermissionStatus.NOT_DETERMINED,
                Permission.LOCATION to PermissionStatus.RESTRICTED,
            ),
        )
        assertEquals(PermissionStatus.GRANTED, checker.check(Permission.BLUETOOTH_SCAN))
        assertEquals(PermissionStatus.DENIED, checker.check(Permission.BLUETOOTH_CONNECT))
        assertEquals(PermissionStatus.NOT_DETERMINED, checker.check(Permission.BLUETOOTH_ADVERTISE))
        assertEquals(PermissionStatus.RESTRICTED, checker.check(Permission.LOCATION))

        val notGranted = checker.checkAll()
        assertEquals(3, notGranted.size)
        assertFalse(notGranted.any { it.permission == Permission.BLUETOOTH_SCAN })
    }

    @Test
    fun configurable_defaultStatusAppliesToUnconfiguredPermissions() {
        val checker = ConfigurablePermissionChecker(
            statuses = mapOf(Permission.BLUETOOTH_SCAN to PermissionStatus.GRANTED),
            defaultStatus = PermissionStatus.NOT_DETERMINED,
        )
        assertEquals(PermissionStatus.GRANTED, checker.check(Permission.BLUETOOTH_SCAN))
        assertEquals(PermissionStatus.NOT_DETERMINED, checker.check(Permission.BLUETOOTH_CONNECT))
        assertEquals(PermissionStatus.NOT_DETERMINED, checker.check(Permission.BLUETOOTH_ADVERTISE))
        assertEquals(PermissionStatus.NOT_DETERMINED, checker.check(Permission.LOCATION))
    }

    @Test
    fun allPermissionEnumValuesExist() {
        val expected = setOf("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE", "LOCATION")
        assertEquals(expected, Permission.entries.map { it.name }.toSet())
        assertEquals(4, Permission.entries.size)
    }

    @Test
    fun allPermissionStatusEnumValuesExist() {
        val expected = setOf("GRANTED", "DENIED", "NOT_DETERMINED", "RESTRICTED")
        assertEquals(expected, PermissionStatus.entries.map { it.name }.toSet())
        assertEquals(4, PermissionStatus.entries.size)
    }

    @Test
    fun configurable_allDeniedReportsAllPermissions() {
        val checker = ConfigurablePermissionChecker(
            defaultStatus = PermissionStatus.DENIED,
        )
        assertFalse(checker.allGranted())
        val results = checker.checkAll()
        assertEquals(Permission.entries.size, results.size)
        for (result in results) {
            assertEquals(PermissionStatus.DENIED, result.status)
        }
    }

    @Test
    fun configurable_emptyConfigWithGrantedDefaultGrantsAll() {
        val checker = ConfigurablePermissionChecker()
        assertTrue(checker.allGranted())
        assertEquals(emptyList(), checker.checkAll())
    }
}
