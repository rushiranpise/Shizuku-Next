package moe.shizuku.manager.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The community's index of Shizuku modules, which is a website and not something this app owns.
 *
 * It is opened in the browser whenever the device has one, because a list of other people's apps
 * belongs where the rest of the web is: it is bookmarked, shared and read in a browser, and the
 * page is built for one. The screen inside the app is a fallback with a real reason - a device
 * with no browser at all would otherwise have no way to reach the list.
 */
object MoreApps {

    /** Sorted by when a module was added, which is how somebody looking for what is new reads it. */
    const val URL = "https://rushiranpise.github.io/shizuku-modules/?sort=added"

    /**
     * Opens the list in the device's browser, and says whether there was one to open it in.
     *
     * What a browser is here is the platform's own answer and not a guess: an activity that
     * answers `CATEGORY_APP_BROWSER`. That distinction is the whole point. Almost anything can
     * register for an `https` link - a document viewer, an office suite, a store page - so a
     * device can answer this intent perfectly well while having no browser on it at all, and
     * starting the intent blindly would put a chooser full of the wrong apps on screen instead of
     * the page. Asking for a browser is the only question with a useful answer.
     */
    fun openInBrowser(context: Context): Boolean {
        val browserPackage = browserPackage(context) ?: return false

        // Opened in that browser rather than "wherever this link goes": the browser was chosen
        // above, and leaving the package off would hand the decision back to whatever else
        // happens to claim the link.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(URL)).setPackage(browserPackage)

        // A browser launched from anything that is not an activity needs a task of its own; in
        // the app it is an activity, so this only matters if the caller was something else.
        val host = AppLocale.activityOf(context) ?: context
        if (host !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            host.startActivity(intent)
            true
        } catch (tr: Throwable) {
            false
        }
    }

    /** The package of an installed browser, or null when the device has none. */
    private fun browserPackage(context: Context): String? {
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)

        // Asked as a list and not as "the default one". A device with two browsers and none of
        // them chosen resolves to the system's chooser, which is not a browser: treating that as
        // "no browser" would hand every such device the fallback while a browser sat right there.
        // The first entry is the system's own preference order, so the default is still the one
        // that opens when there is one.
        val candidates = try {
            context.packageManager.queryIntentActivities(probe, 0)
        } catch (tr: Throwable) {
            emptyList()
        }

        return candidates
            .mapNotNull { it.activityInfo?.packageName }
            // "android" is the chooser, which answers this too. It is the system asking a
            // question, not a browser answering one.
            .firstOrNull { it != "android" }
    }
}
