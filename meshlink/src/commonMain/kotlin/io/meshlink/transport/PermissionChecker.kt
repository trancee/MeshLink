package io.meshlink.transport

/**
 * Checks BLE-related permissions required for mesh networking.
 * Platform implementations (Android/iOS) provide actual permission checks.
 */
interface PermissionChecker {

    enum class Permission {
        BLUETOOTH_SCAN,
        BLUETOOTH_CONNECT,
        BLUETOOTH_ADVERTISE,
        LOCATION,
    }

    enum class PermissionStatus {
        GRANTED,
        DENIED,
        NOT_DETERMINED,
        RESTRICTED,
    }

    data class PermissionResult(
        val permission: Permission,
        val status: PermissionStatus,
    )

    /**
     * Check the status of a specific permission.
     */
    fun check(permission: Permission): PermissionStatus

    /**
     * Check all required BLE permissions.
     * Returns list of permissions that are NOT granted.
     */
    fun checkAll(): List<PermissionResult>

    /**
     * Whether all required permissions are granted.
     */
    fun allGranted(): Boolean = checkAll().isEmpty()
}

/**
 * Default no-op permission checker that grants all permissions.
 * Used in tests and on platforms where permissions are handled externally.
 */
class AlwaysGrantedPermissionChecker : PermissionChecker {
    override fun check(permission: PermissionChecker.Permission): PermissionChecker.PermissionStatus =
        PermissionChecker.PermissionStatus.GRANTED

    override fun checkAll(): List<PermissionChecker.PermissionResult> = emptyList()
}

/**
 * Permission checker that denies specific permissions (useful for testing).
 */
class ConfigurablePermissionChecker(
    private val statuses: Map<PermissionChecker.Permission, PermissionChecker.PermissionStatus> = emptyMap(),
    private val defaultStatus: PermissionChecker.PermissionStatus = PermissionChecker.PermissionStatus.GRANTED,
) : PermissionChecker {
    override fun check(permission: PermissionChecker.Permission): PermissionChecker.PermissionStatus =
        statuses[permission] ?: defaultStatus

    override fun checkAll(): List<PermissionChecker.PermissionResult> =
        PermissionChecker.Permission.entries.mapNotNull { perm ->
            val status = check(perm)
            if (status != PermissionChecker.PermissionStatus.GRANTED) {
                PermissionChecker.PermissionResult(perm, status)
            } else {
                null
            }
        }
}
