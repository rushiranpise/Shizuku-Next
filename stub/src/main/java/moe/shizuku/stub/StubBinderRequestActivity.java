package moe.shizuku.stub;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * The same forwarding, for the callers that ask by starting an activity.
 *
 * Android 16 does not deliver the broadcast an app_process caller sends to another app's receiver
 * on every OEM, which is why rish has a second request that starts the manager's activity instead.
 * That request arrives here, and this passes it on the same way and closes: the answer belongs to
 * the caller, not to a screen.
 */
public class StubBinderRequestActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String target = StubForwarding.target(this);
        if (target != null) {
            try {
                startActivity(
                        StubForwarding.forwarded(getIntent(), target)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            } catch (Throwable tr) {
                Log.w(StubForwarding.TAG, "Could not pass the request on to " + target, tr);
            }
        }

        finish();
    }
}
