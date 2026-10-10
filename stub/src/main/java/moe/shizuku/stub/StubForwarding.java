package moe.shizuku.stub;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * What the stub that holds the original package name does with a request addressed to it.
 *
 * While the app is hidden, the clients that name it by the package it was installed as before -
 * rish exported then, automation written while it was visible - reach this stub instead of the
 * copy. The stub cannot answer them itself and must not pretend to: the server hands the binder to
 * the manager it was started for, and this package is not it, so a stub claiming it has a binder
 * would be lying to every one of those clients.
 *
 * What it can do is pass the request on, untouched, to the copy. That is enough, because the
 * caller's own binder travels inside the request: the copy answers the caller directly, and the
 * answer never comes back through this process. The copy also sends its own APK path, so a shell
 * started from an old rish export still loads the shell classes out of the app that is installed.
 */
public final class StubForwarding {

    public static final String TAG = "ShizukuStub";

    /**
     * Which package to pass requests to. Written into the stub's manifest when the stub is built,
     * so it names the copy this stub was made for.
     */
    public static final String TARGET_META_DATA = "moe.shizuku.stub.target";

    private StubForwarding() {
    }

    /** The package to pass a request to, or null when this is the app itself. */
    public static String target(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            if (info == null || info.metaData == null) return null;

            String declared = info.metaData.getString(TARGET_META_DATA);

            // A stub only ever exists to hold a name other than the app's own, so a target that is
            // this very package means the manifest was not generated and there is nothing to pass
            // anything to. Forwarding to itself would be a request that goes round and round.
            if (declared == null || declared.equals(context.getPackageName())) return null;

            return declared;
        } catch (Throwable tr) {
            Log.w(TAG, "Could not read the package this stub forwards to", tr);
            return null;
        }
    }

    /**
     * The request as the copy has to receive it.
     *
     * The component is cleared, and that is the whole reason this is not a one-liner. The platform
     * hands a request to a manifest receiver that is not running yet by starting the process and
     * delivering it with the component filled in, and an explicit component outranks the package:
     * a request forwarded with it still on arrives back at this same receiver, which forwards it
     * again. What the caller waits for never leaves the stub, so it timed out while the request
     * went round in a loop - which is what the broadcast history showed on the device.
     */
    public static Intent forwarded(Intent intent, String target) {
        return new Intent(intent)
                .setComponent(null)
                .setPackage(target)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
    }
}
