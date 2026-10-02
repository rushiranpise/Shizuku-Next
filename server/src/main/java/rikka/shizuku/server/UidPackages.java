package rikka.shizuku.server;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the device still has for a UID, read from the two places that can be asked, or nothing at
 * all.
 *
 * The server used to take one call for an answer on its own, and that call has three outcomes
 * wearing one shape: the UID's packages, an empty list for a UID with nothing installed, and an
 * empty list for a call that never got through. The third was read as the first, so a package
 * manager that was not answering - at boot, or after a transaction failed - deleted the UID's
 * record, and the record is where its granted apps are kept. A device whose manager would not
 * answer came back with those apps revoked, and nothing anywhere said so.
 *
 * The two readings are therefore kept apart here, and the difference between "no packages" and "no
 * answer" is carried by null: a null lookup is a call that threw, and a null list is a list that
 * could not be read - which is not the same as the empty list of a user, or of a UID, with nothing
 * installed. Only when both are silent is the caller told nothing, and told nothing is the one case
 * where a record has to be left where it is. Either reading may answer alone: a lookup that fails
 * still leaves the installed list to speak for the UID, and a lookup that answers a filtered or
 * forgotten nothing is corrected by a list that names it.
 *
 * Kept apart from the manager that asks, because this is the half that decides, and a device cannot
 * be made to fail a call on purpose to test it.
 */
public final class UidPackages {

    private UidPackages() {
    }

    /**
     * @param lookup          what the platform answered for the UID, or null when the call did not
     *                        get through. An empty array is an answer, and says the UID has no
     *                        packages
     * @param installedForUid the UID's packages as the installed list of its own user names them,
     *                        or null when that list could not be read at all. An empty list means
     *                        the list was read and names none for this UID
     * @return the packages either reading vouches for, or null when neither of them answered.
     */
    @Nullable
    public static Set<String> of(@Nullable String[] lookup, @Nullable List<String> installedForUid) {
        if (lookup == null && installedForUid == null) {
            return null;
        }

        Set<String> packages = new LinkedHashSet<>();
        if (lookup != null) {
            Collections.addAll(packages, lookup);
        }
        if (installedForUid != null) {
            packages.addAll(installedForUid);
        }
        return packages;
    }
}
