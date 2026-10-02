package moe.shizuku.manager;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import java.lang.annotation.Retention;
import java.util.Locale;
import moe.shizuku.manager.service.WatchdogService;
import moe.shizuku.manager.receiver.BootCompleteReceiver;
import moe.shizuku.manager.receiver.WatchdogAlarmReceiver;
import moe.shizuku.manager.utils.AppLocale;
import moe.shizuku.manager.utils.Token;
import moe.shizuku.manager.utils.EmptySharedPreferencesImpl;
import moe.shizuku.manager.utils.EnvironmentUtils;
import static java.lang.annotation.RetentionPolicy.SOURCE;

public class ShizukuSettings {

    public static final String NAME = "settings";
    public static class Keys {
        public static final String KEY_START_ON_BOOT = "start_on_boot";
        public static final String KEY_ADB_WITHOUT_DEVELOPER_OPTIONS = "adb_without_developer_options";
        public static final String KEY_WATCHDOG = "watchdog";
        public static final String KEY_TCP_MODE = "tcp_mode";
        public static final String KEY_TCP_PORT = "tcp_port";
        public static final String KEY_AUTO_DISABLE_USB_DEBUGGING = "auto_disable_usb_debugging";
        public static final String KEY_AUTO_DISABLE_WIRELESS_DEBUGGING = "auto_disable_wireless_debugging";
        public static final String KEY_LANGUAGE = "language";
        public static final String KEY_TRANSLATION = "translation";
        public static final String KEY_TRANSLATION_CONTRIBUTORS = "translation_contributors";
        public static final String KEY_LIGHT_THEME = "light_theme";
        public static final String KEY_NIGHT_MODE = "night_mode";
        public static final String KEY_BLACK_NIGHT_THEME = "black_night_theme";
        public static final String KEY_USE_SYSTEM_COLOR = "use_system_color";
        public static final String KEY_UPDATE_MODE = "update_mode";
        public static final String KEY_HELP = "help";
        public static final String KEY_REPORT_BUG = "report_bug";
        public static final String KEY_LEGACY_PAIRING = "legacy_pairing";
        public static final String KEY_CATEGORY_ADVANCED = "category_advanced";
        public static final String KEY_MANUALLY_STOPPED = "manually_stopped";
        public static final String KEY_LAST_ADB_TRANSPORT = "last_adb_transport";
        public static final String KEY_START_METHOD = "start_method";
        public static final String KEY_RUNNING_START_METHOD = "running_start_method";
        public static final String KEY_WAIT_FOR_WIFI = "wait_for_wifi";
        public static final String KEY_FORCE_WIRELESS_DEBUGGING = "force_wireless_debugging";
        public static final String KEY_PERSIST_ADB_PORT = "persist_adb_port";
        public static final String KEY_SYSTEM_START_METHOD = "system_start_method";

        /** Whether the start and stop intents have to carry the auth token. */
        public static final String KEY_REQUIRE_INTENT_TOKEN = "require_intent_token";
    }

    public static class UpdateMode {
        public static final int OFF = 0;
        public static final int STABLE = 1;
        public static final int BETA = 2;
    }

    private static SharedPreferences sPreferences;

    public static SharedPreferences getPreferences() {
        return sPreferences;
    }

    @NonNull
    private static Context getSettingsStorageContext(@NonNull Context context) {
        Context storageContext;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageContext = context.createDeviceProtectedStorageContext();
        } else {
            storageContext = context;
        }

        storageContext = new ContextWrapper(storageContext) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                try {
                    return super.getSharedPreferences(name, mode);
                } catch (IllegalStateException e) {
                    // SharedPreferences in credential encrypted storage are not available until after user is unlocked
                    return new EmptySharedPreferencesImpl();
                }
            }
        };

        return storageContext;
    }

    public static void initialize(Context context) {
        if (sPreferences == null) {
            sPreferences = getSettingsStorageContext(context)
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
        }
    }

    /**
     * Which method a start uses. This is what the Start button, start on boot,
     * the watchdog and the start intents all follow, so "start" behaves the same
     * everywhere instead of guessing from whichever method happened to work last.
     */
    @IntDef({
        StartMethod.WIRELESS,
        StartMethod.USB,
        StartMethod.SYSTEM,
        StartMethod.ROOT,
        StartMethod.WIRELESS_NO_NETWORK,
    })
    @Retention(SOURCE)
    public @interface StartMethod {
        int WIRELESS = 0;
        int USB = 1;
        int SYSTEM = 2;
        int ROOT = 3;

        /**
         * Wireless debugging with the no-network experiment pinned on.
         *
         * The experiment used to be a setting somebody had to remember to switch on beside
         * the method, which is two settings that can disagree about how a start should work.
         * As a method it says what it does, and it is offered only while that experiment is
         * enabled, since without it there is nothing to choose.
         */
        int WIRELESS_NO_NETWORK = 4;
    }

    @StartMethod
    public static int getStartMethod() {
        int fallback = getLastLaunchMode() == LaunchMethod.ROOT
                ? StartMethod.ROOT
                : StartMethod.WIRELESS;
        return getPreferences().getInt(Keys.KEY_START_METHOD, fallback);
    }

    public static void setStartMethod(@StartMethod int method) {
        getPreferences().edit().putInt(Keys.KEY_START_METHOD, method).apply();
    }

    private static final int START_METHOD_UNRECORDED = -1;

    /**
     * How the server that is running now was started. Kept separately from
     * [getStartMethod] (which is what the next start will use), so the UI can show both
     * and they can't be mistaken for each other. [START_METHOD_UNRECORDED] means no
     * start of ours launched it e.g. it was started by another tool.
     */
    public static int getRunningStartMethod() {
        return getPreferences().getInt(Keys.KEY_RUNNING_START_METHOD, START_METHOD_UNRECORDED);
    }

    public static void setRunningStartMethod(@StartMethod int method) {
        getPreferences().edit().putInt(Keys.KEY_RUNNING_START_METHOD, method).apply();
    }

    @IntDef({
        LaunchMethod.UNKNOWN,
        LaunchMethod.ROOT,
        LaunchMethod.ADB,
    })
    @Retention(SOURCE)
    public @interface LaunchMethod {
        int UNKNOWN = -1;
        int ROOT = 0;
        int ADB = 1;
    }

    /** Which method was observed to work last informational (status card, transport). */
    @LaunchMethod
    public static int getLastLaunchMode() {
        return getPreferences().getInt("mode", LaunchMethod.UNKNOWN);
    }

    public static void setLastLaunchMode(@LaunchMethod int method) {
        getPreferences().edit().putInt("mode", method).apply();
    }

    public static boolean getAutoDisableUsbDebugging() {
        return getPreferences().getBoolean(Keys.KEY_AUTO_DISABLE_USB_DEBUGGING, false);
    }

    /**
     * Wireless debugging is left on by default that is what lets Shizuku restart with
     * no Wi-Fi and USB debugging off so turning it off when stopping is opt-in.
     */
    public static boolean getAutoDisableWirelessDebugging() {
        return getPreferences().getBoolean(Keys.KEY_AUTO_DISABLE_WIRELESS_DEBUGGING, false);
    }
    
    public static String getLastPromptedVersion() {
        return getPreferences().getString("lastPromptedVersion", "");
    }

    public static void setLastPromptedVersion(String version) {
        getPreferences().edit().putString("lastPromptedVersion", version).apply();
    }

    /**
     * Whether the start and stop intents have to carry the auth token.
     *
     * On by default, because with it off any app on the device can start and stop Shizuku,
     * and the token is what keeps that to the apps the token was given to. Off is for people
     * who drive several devices from one Tasker or MacroDroid task: the token is generated per
     * install, so the same task has to be edited for every phone, and the automation intents
     * for the watchdog are already token-free, which makes the inconsistency the harder thing
     * to explain. It is the user's risk to take, and the screen that turns it off says so.
     */
    public static boolean getRequireIntentToken() {
        return getPreferences().getBoolean(Keys.KEY_REQUIRE_INTENT_TOKEN, true);
    }

    public static void setRequireIntentToken(boolean require) {
        getPreferences().edit().putBoolean(Keys.KEY_REQUIRE_INTENT_TOKEN, require).apply();
    }

    public static String getAuthToken() {
        String authToken = getPreferences().getString("auth_token", null);
        if (authToken == null || authToken.isEmpty()) {
            authToken = generateAuthToken();
        }
        return authToken;
    }

    public static String generateAuthToken() {
        String token = Token.generateToken();
        getPreferences().edit().putString("auth_token", token).apply();
        return token;
    }

    /**
     * Whether the user asked for a start after a reboot.
     *
     * This is the stored setting, not the state of [BootCompleteReceiver]: the receiver is
     * shared with "ADB without Developer options", so reading the component back made the
     * setting unable to turn itself on - the write asked whether it was already enabled.
     */
    public static boolean getStartOnBoot(Context context) {
        return getPreferences().getBoolean(Keys.KEY_START_ON_BOOT, false);
    }

    public static void setStartOnBoot(Context context, boolean enable) {
        getPreferences().edit().putBoolean(Keys.KEY_START_ON_BOOT, enable).apply();
        updateBootReceiver(context);
    }

    /**
     * Keep ADB on while Developer options is off, so Shizuku can still start there.
     *
     * The boot receiver is what puts the ADB settings back after a reboot, which is why it
     * is enabled for this as well as for start on boot: without it the first reboot would
     * undo the whole point of the setting.
     */
    public static boolean getAdbWithoutDeveloperOptions() {
        return getPreferences().getBoolean(Keys.KEY_ADB_WITHOUT_DEVELOPER_OPTIONS, false);
    }

    public static void setAdbWithoutDeveloperOptions(Context context, boolean enable) {
        getPreferences().edit().putBoolean(Keys.KEY_ADB_WITHOUT_DEVELOPER_OPTIONS, enable).apply();
        updateBootReceiver(context);
    }

    /**
     * The receiver is needed by either setting, so it follows both of them. This also runs on
     * every app start: installs written while the setting read the component back have the
     * preference on but the receiver still disabled, and this puts them right again.
     */
    public static void updateBootReceiver(Context context) {
        boolean needed = getStartOnBoot(context) || getAdbWithoutDeveloperOptions();
        ComponentName bootCompleteReceiver = new ComponentName(context.getPackageName(), BootCompleteReceiver.class.getName());
        context.getPackageManager().setComponentEnabledSetting(
            bootCompleteReceiver,
            needed ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        );
    }
    
    public static boolean getWatchdog() {
        return getPreferences().getBoolean(Keys.KEY_WATCHDOG, false);
    }

    public static boolean isWatchdogRunning() {
        return WatchdogService.isRunning();
    }

    /**
     * True while the last stop was requested by the user (as opposed to a crash or
     * the system killing the server). Suppresses the watchdog's proactive
     * "server is dead, restart it" check. Cleared whenever a start is requested
     * from any entry point and whenever the server is confirmed RUNNING.
     */
    public static boolean getManuallyStopped() {
        return getPreferences().getBoolean(Keys.KEY_MANUALLY_STOPPED, false);
    }

    public static void setManuallyStopped(boolean stopped) {
        getPreferences().edit().putBoolean(Keys.KEY_MANUALLY_STOPPED, stopped).apply();
    }

    public static final int ADB_TRANSPORT_UNKNOWN = 0;
    // Started over wireless debugging (TLS)
    public static final int ADB_TRANSPORT_TLS = 1;
    // Started over the classic adb TCP port (USB debugging)
    public static final int ADB_TRANSPORT_TCP = 2;

    public static int getLastAdbTransport() {
        return getPreferences().getInt(Keys.KEY_LAST_ADB_TRANSPORT, ADB_TRANSPORT_UNKNOWN);
    }

    public static void setLastAdbTransport(int transport) {
        getPreferences().edit().putInt(Keys.KEY_LAST_ADB_TRANSPORT, transport).apply();
    }

    /**
     * When enabled, unattended background restarts wait for an unmetered Wi-Fi
     * connection before attempting discovery. User-initiated starts never wait.
     */
    public static boolean getWaitForWifi() {
        return getPreferences().getBoolean(Keys.KEY_WAIT_FOR_WIFI, true);
    }

    public static void setWaitForWifi(boolean enable) {
        getPreferences().edit().putBoolean(Keys.KEY_WAIT_FOR_WIFI, enable).apply();
    }

    /**
     * Keep wireless debugging on without a Wi-Fi network by asking for it over and over,
     * the way the Settings toggle cannot be asked while offline.
     *
     * Off by default, and deliberately so: what makes it work is a platform bug, so it can
     * stop working after a system update, and it asks for a state a managed device would
     * normally refuse to grant. Nothing relies on it, so a start that cannot use it simply
     * behaves as it did before.
     */
    public static boolean getForceWirelessDebugging() {
        return getPreferences().getBoolean(Keys.KEY_FORCE_WIRELESS_DEBUGGING, false);
    }

    public static void setForceWirelessDebugging(boolean enable) {
        getPreferences().edit().putBoolean(Keys.KEY_FORCE_WIRELESS_DEBUGGING, enable).apply();
    }

    /**
     * Keep the classic ADB port open across reboots by writing persist.adb.tcp.port, so a
     * start after a reboot has a port to use with no network, no hotspot and no race.
     *
     * The property belongs to adbd's own security context, so this can only ever work on a
     * device where the running server is root or the system uid; see AdbPortPersistence.
     */
    public static boolean getPersistAdbPort() {
        return getPreferences().getBoolean(Keys.KEY_PERSIST_ADB_PORT, false);
    }

    public static void setPersistAdbPort(boolean enable) {
        getPreferences().edit().putBoolean(Keys.KEY_PERSIST_ADB_PORT, enable).apply();
    }

    /**
     * Which method the "Start (system)" card uses to launch Shizuku under the
     * system UID: the built-in device exploit, or an external/custom launch.
     */
    public static final String SYSTEM_START_EXPLOIT = "exploit";
    public static final String SYSTEM_START_CUSTOM = "custom";

    public static String getSystemStartMethod() {
        return getPreferences().getString(Keys.KEY_SYSTEM_START_METHOD, SYSTEM_START_CUSTOM);
    }

    public static void setSystemStartMethod(String method) {
        getPreferences().edit().putString(Keys.KEY_SYSTEM_START_METHOD, method).apply();
    }

    public static void setWatchdog(Context context, boolean enable) {
        if (enable) {
            WatchdogService.start(context);
        } else {
            WatchdogService.stop(context);
        }
        getPreferences().edit().putBoolean(Keys.KEY_WATCHDOG, enable).apply();
        // After the preference, not before: the alarm's own schedule() reads that preference to
        // decide whether it should exist at all. Cancelling on the way off is the half that
        // matters - a backstop left armed would start the watchdog again after the user had
        // switched it off, which is the one thing it must never do.
        if (enable) {
            WatchdogAlarmReceiver.schedule(context);
        } else {
            WatchdogAlarmReceiver.cancel(context);
        }
        return;
    }

    public static boolean getTcpMode() {
        return getPreferences().getBoolean(Keys.KEY_TCP_MODE, true);
    }

    public static void setTcpMode(boolean enable) {
        getPreferences().edit().putBoolean(Keys.KEY_TCP_MODE, enable).apply();
    }

    public static int getTcpPort() {
        try {
            return Integer.parseInt(getPreferences().getString(Keys.KEY_TCP_PORT, "5555"));
        } catch (NumberFormatException e) {
            return 5555;
        }
    }

    public static void setTcpPort(@Nullable Integer port) {
        if (port != null) {
            getPreferences().edit().putString(Keys.KEY_TCP_PORT, Integer.toString(port)).apply();
        } else {
            getPreferences().edit().remove(Keys.KEY_TCP_PORT).apply();
        }
        
    }

    public static boolean getLegacyPairing() {
        return getPreferences().getBoolean(Keys.KEY_LEGACY_PAIRING, false);
    }

    @AppCompatDelegate.NightMode
    public static int getNightMode() {
        int defValue = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        if (EnvironmentUtils.isWatch()) {
            defValue = AppCompatDelegate.MODE_NIGHT_YES;
        }
        return getPreferences().getInt(Keys.KEY_NIGHT_MODE, defValue);
    }

    /**
     * The language the user chose, as a BCP 47 tag, or [AppLocale.SYSTEM] to follow the device.
     *
     * What is stored is the tag and not a `Locale`, because the tag is what the framework per-app
     * locale takes on Android 13+, and because a tag is still meaningful for a language this build
     * has no resources for.
     */
    public static String getLanguageTag() {
        String tag = getPreferences().getString(Keys.KEY_LANGUAGE, null);
        return TextUtils.isEmpty(tag) ? AppLocale.SYSTEM : tag;
    }

    public static void setLanguageTag(String tag) {
        getPreferences().edit().putString(Keys.KEY_LANGUAGE, tag).apply();
    }

    public static Locale getLocale() {
        String tag = getLanguageTag();
        if (AppLocale.SYSTEM.equals(tag)) {
            return Locale.getDefault();
        }
        return Locale.forLanguageTag(tag);
    }

    public static int getUpdateMode() {
        return getPreferences().getInt(Keys.KEY_UPDATE_MODE, UpdateMode.STABLE);
    }
}
