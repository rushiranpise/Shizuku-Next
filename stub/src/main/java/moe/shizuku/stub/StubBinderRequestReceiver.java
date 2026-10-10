package moe.shizuku.stub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Passes a binder request aimed at the name this stub holds on to the hidden copy.
 *
 * This is the request rish and old clients make, and the reason the stub is installed at all: an
 * export made before hiding, or anything else that names the package the app used to have, keeps
 * working once this is in place.
 */
public class StubBinderRequestReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String target = StubForwarding.target(context);
        if (target == null) {
            Log.w(StubForwarding.TAG, "Nothing to forward " + intent.getAction() + " to");
            return;
        }

        // FLAG_RECEIVER_FOREGROUND shares its bit with FLAG_ACTIVITY_CLEAR_TOP, which a broadcast
        // does not read: here it asks for the copy to be woken now rather than queued behind
        // whatever else is on its way.
        context.sendBroadcast(
                StubForwarding.forwarded(intent, target).addFlags(Intent.FLAG_RECEIVER_FOREGROUND));
    }
}
