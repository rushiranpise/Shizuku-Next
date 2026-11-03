-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void check*(...);
	public static void throw*(...);
}

-assumenosideeffects class java.util.Objects{
    ** requireNonNull(...);
}

-keepnames class moe.shizuku.api.BinderContainer

# Missing class android.app.IProcessObserver$Stub
# Missing class android.app.IUidObserver$Stub
-keepclassmembers class rikka.hidden.compat.adapter.ProcessObserverAdapter {
    <methods>;
}

-keepclassmembers class rikka.hidden.compat.adapter.UidObserverAdapter {
    <methods>;
}

# Entrance of Shizuku service
-keep class rikka.shizuku.server.ShizukuService {
    public static void main(java.lang.String[]);
}

# Entrance of user service starter
-keep class moe.shizuku.starter.ServiceStarter {
    public static void main(java.lang.String[]);
}

# Entrance of shell
-keep class moe.shizuku.manager.shell.Shell {
    public static void main(java.lang.String[], java.lang.String, android.os.IBinder, android.os.Handler);
}

-assumenosideeffects class android.util.Log {
    public static *** d(...);
}

-assumenosideeffects class moe.shizuku.manager.utils.Logger {
    public *** d(...);
}

#noinspection ShrinkerUnresolvedReference
-assumenosideeffects class rikka.shizuku.server.util.Logger {
    public *** d(...);
}

# com.reandroid.ARSCLIB implements android/xmlpull stubs which R8 tries to strip, causing crashes on release builds
# https://github.com/REAndroid/ARSCLib/issues/95
-keep public interface android.util.AttributeSet { *; }
-keep public interface android.content.res.XmlResourceParser { *; }
-keep public interface org.xmlpull.v1.** { *; }

# An enum that travels through a Parcel or a Bundle is read back by reflective lookup of its
# values(), and R8 removes that method when nothing in the code calls it directly - the read then
# throws NoSuchMethodException on whatever thread is restoring the state, which for a View is
# onAttachedToWindow and therefore a fatal crash in the app. Reported from an Oppo Reno on
# Android 16 as "java.lang.NoSuchMethodException: <enum>.values []" inside Enum.enumValues, with
# the app itself repackaged into rikka.shizuku by the rule below, which is why the class in the
# trace looks like part of the library.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# A module's WebUI reaches this app through methods the WebView finds by reflection, and it only
# exposes the ones carrying @JavascriptInterface - methods nothing in this app calls, so the
# shrinker is free to remove or rename them. Release builds shipped a hollow window.Shizuku
# (Object.getOwnPropertyNames answered []) while debug builds worked, because the release block
# names only this file and so never applied the default proguard-android-optimize.txt rule that
# keeps these members. The annotation is read at runtime to decide what a page may call, so it
# has to survive next to them.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepattributes *Annotation*

-allowaccessmodification
-repackageclasses rikka.shizuku
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
