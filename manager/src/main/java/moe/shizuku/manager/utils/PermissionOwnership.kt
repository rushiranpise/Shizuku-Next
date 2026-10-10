package moe.shizuku.manager.utils

/**
 * Who Android says owns Shizuku's permission, and with it what other apps find when they look
 * Shizuku up.
 *
 * An app that uses Shizuku does not look for a package name: it looks up this permission and takes
 * whichever package declares it as the one to ask. So this answer going wrong is enough to break
 * every one of them, and it goes wrong in two ways that this app cannot fix from here: another
 * package owns the name - a copy of Shizuku that was uninstalled whose registration the platform
 * keeps until a reboot - or the declaration has not been read back yet, which a reinstall can leave
 * behind the same way.
 *
 * Neither of those stops Shizuku itself from running: the app knows the name of its own permission,
 * the server grants it by that name, and the two look-ups below are only ever used to tell the user
 * what another app would find. So they are a warning, not a gate.
 */
sealed interface PermissionOwnership {

    /** Ours, which is what every install settles on: nothing to say. */
    object Ours : PermissionOwnership

    /** Another package declares it: a copy of Shizuku, or what is left of one. */
    data class Other(val packageName: String) : PermissionOwnership

    /** Nothing by that name is registered, or the group it belongs to is not there either. */
    object NotRegistered : PermissionOwnership
}

/**
 * The reading of the two look-ups the Home screen makes.
 *
 * [ownerPackage] is who `PackageManager.getPermissionInfo` says declares the permission, or null
 * when that look-up found nothing. [groupResolved] is whether the group the permission belongs to
 * is registered as well, which is the second half of what a reinstall can leave unprocessed.
 *
 * Another package owning the name is reported ahead of the group, because it is the one the user
 * can act on: uninstall it, and reboot.
 */
fun permissionOwnership(
    ourPackage: String,
    ownerPackage: String?,
    groupResolved: Boolean,
): PermissionOwnership = when {
    ownerPackage == null -> PermissionOwnership.NotRegistered
    ownerPackage != ourPackage -> PermissionOwnership.Other(ownerPackage)
    !groupResolved -> PermissionOwnership.NotRegistered
    else -> PermissionOwnership.Ours
}
