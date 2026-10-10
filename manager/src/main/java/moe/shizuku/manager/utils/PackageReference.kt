package moe.shizuku.manager.utils

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock

/**
 * [value] with a leading [oldPackage] replaced by [newPackage], or null when this value has nothing
 * to do with the old package name.
 *
 * The name an app is called by is written into its manifest in more places than its `package`
 * attribute: intent filter actions, permission names and provider authorities all carry it, and
 * renaming only the package leaves a copy that advertises one name and answers to another. That is
 * issue #86: a hidden install whose `.START` action matched no filter at all, while the action that
 * did match was then rejected by a receiver comparing against the runtime package name.
 *
 * Only a value that *is* the package, or begins with it followed by a dot, is rewritten - so a URL
 * or a path that happens to contain those characters somewhere inside it is left alone.
 */
internal fun rewrittenPackageReference(
    value: String,
    oldPackage: String,
    newPackage: String
): String? = when {
    value == oldPackage -> newPackage
    !value.startsWith("$oldPackage.") -> null
    else -> newPackage + value.removePrefix(oldPackage)
}

/**
 * [this] manifest's label replaced with [requested], and whether a name of its own was written.
 *
 * A hidden copy is a copy of this app's own APK, so its label is this app's label: the manifest
 * points the launcher at a string resource, and that resource says this app's name however the
 * package is called. The name a user types is written over it as a literal, because the point is
 * what the copy is listed as, not a renamed app - the same resource is what the app's own screens
 * read, and a copy whose launcher entry and whose screens disagree is its own kind of tell.
 *
 * Blank is not a name: it leaves the manifest alone, which is what an empty field means.
 */
fun AndroidManifestBlock.renameHiddenCopy(requested: String?): Boolean {
    val label = requested?.trim()?.takeIf { it.isNotEmpty() } ?: return false

    setApplicationLabel(label)
    return true
}

/**
 * The name a hidden copy has to keep across an update, or null when it has none of its own.
 *
 * An update downloads the released APK and, while hidden, renames it to the running package name -
 * which rebuilds the label from this app's own string resource and would undo whatever name the
 * user chose when they hid. [currentLabel] is what the running copy is listed as, which is that
 * name; [appName] is what the resource says in the locale in use. When the two differ a name was
 * chosen and is written again, and when they are the same nothing is written at all - which is what
 * keeps the label translated for everybody who never chose one.
 */
fun displayNameToKeep(currentLabel: String, appName: String): String? =
    currentLabel.takeIf { it != appName }
