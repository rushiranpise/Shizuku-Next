package moe.shizuku.stub;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Passes automation on to the hidden copy, under the copy's own name.
 *
 * The start, stop and watchdog actions are what an automation app sends this package, and they are
 * built from whichever name the app had when the automation was written. While hidden, the copy
 * answers to its own package name and compares what it receives against it, so a request passed on
 * as it stands is one the copy reads and then ignores - silently, which is the worst way for an
 * automation to stop working. Only the name is translated on the way through.
 *
 * The status request is the one that asks a question rather than giving an order: it is an ordered
 * broadcast whose result is the answer, so it is passed on as one and the answer handed back. Sent
 * unordered, the copy's answer would go nowhere and the caller would read a null result as "off".
 */
public class StubAutomationReceiver extends BroadcastReceiver {

    /**
     * The one action here whose result carries an answer. Its spelling is the manager's, in
     * WatchdogStatusReceiver; a stub cannot share code with the app it was cut out of, so the
     * suffix is repeated rather than referenced.
     */
    private static final String STATUS_SUFFIX = "WATCHDOG_STATUS";

    @Override
    public void onReceive(Context context, Intent intent) {
        String target = StubForwarding.target(context);
        if (target == null) {
            Log.w(StubForwarding.TAG, "Nothing to forward " + intent.getAction() + " to");
            return;
        }

        String action = intent.getAction();
        String translated = StubForwarding.translatedAction(action, context.getPackageName(), target);
        if (translated == null) {
            Log.w(StubForwarding.TAG, "Not an action of this package: " + action);
            return;
        }

        Log.i(StubForwarding.TAG, "Passing " + action + " on to " + target + " as " + translated);

        Intent forwarded = StubForwarding.forwarded(intent, target)
                .setAction(translated)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        if (action.endsWith("." + STATUS_SUFFIX)) {
            askStatus(context, forwarded);
        } else {
            context.sendBroadcast(forwarded);
        }
    }

    /**
     * The status question, forwarded as a question.
     *
     * The answer comes back to the receiver below instead of to this method, which is why the
     * broadcast is held open while it is asked: the caller is waiting on a result, and there is
     * nothing to hand it until the copy has answered. The result is set on the receiver the caller
     * is waiting on - not on the one that carried the answer - and then that receiver is finished.
     */
    private void askStatus(Context context, Intent forwarded) {
        final PendingResult pending = goAsync();

        try {
            context.sendOrderedBroadcast(
                    forwarded,
                    null,
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            // The result of an ordered broadcast is read off the receiver that was
                            // handed it, not out of its intent: code and data together, so whatever
                            // the copy answered arrives at the caller unchanged.
                            pending.setResultCode(getResultCode());
                            pending.setResultData(getResultData());
                            pending.finish();
                        }
                    },
                    null,
                    Activity.RESULT_OK,
                    null,
                    null
            );
        } catch (Throwable tr) {
            Log.w(StubForwarding.TAG, "Could not ask the copy for its status", tr);
            pending.finish();
        }
    }
}
