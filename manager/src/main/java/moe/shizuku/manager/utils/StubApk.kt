package moe.shizuku.manager.utils

import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement

/** The dex the stub APK is assembled around, in the manager's assets. */
const val STUB_DEX_ASSET = "stub_shizuku.dex"

/**
 * Which package a stub passes on what it receives.
 *
 * Written into the stub's manifest here and read back by the stub's own code, so the two have to be
 * the same string - the only thing the two sides share.
 */
const val STUB_TARGET_META_DATA = "moe.shizuku.stub.target"

/** The classes in that dex that the generated manifest names. */
const val STUB_RECEIVER_CLASS = "moe.shizuku.stub.StubBinderRequestReceiver"
const val STUB_ACTIVITY_CLASS = "moe.shizuku.stub.StubBinderRequestActivity"

/** What the stub answers: the request rish and older clients make when they want the binder. */
const val STUB_REQUEST_ACTION = "rikka.shizuku.intent.action.REQUEST_BINDER"

private const val ANDROID_ATTR_NAME = 0x01010003
private const val ANDROID_ATTR_EXPORTED = 0x01010010
private const val ANDROID_ATTR_VALUE = 0x01010024

/**
 * The stub APK in full, as a module.
 *
 * Two things are in it: the dex, which knows how to pass a request on, and a manifest that declares
 * the two components doing that and nothing else. Not the app's permissions - the copy declares the
 * one clients look Shizuku up by, and two apps claiming it is a state the manager itself warns
 * about - and no provider, service or launcher entry, because a stub holding the old package name
 * must not be a second app claiming the names the running copy answers to.
 *
 * Everything a package needs to be installable is here: a name, a label, an icon, a version above
 * Google Play's own ceiling (see [createStubApk]), and the class names as they are in the dex.
 *
 * Pure ARSCLib on purpose, with no context or other Android type in reach, so a test can build the
 * whole APK, write it out, load it again and read back what a device would.
 */
fun stubApkModule(
    dex: ByteArray,
    pkgName: String,
    targetPackage: String,
    label: String,
    iconReference: Int,
    versionCode: Int,
    versionName: String,
    targetSdk: Int,
    minSdk: Int,
): ApkModule {
    val tableBlock = TableBlock()
    val manifest = AndroidManifestBlock()

    val module =
        ApkModule().apply {
            setTableBlock(tableBlock)
            setManifest(manifest)
            add(ByteInputSource(dex, "classes.dex"))
        }

    val packageBlock = tableBlock.newPackage(0x7f, pkgName)
    val appName =
        packageBlock.getOrCreate("", "string", "app_name").apply { setValueAsString(label) }
    val appIcon =
        packageBlock.getOrCreate("", "drawable", "ic_launcher")
            .apply { setValueAsReference(iconReference) }

    manifest.apply {
        setPackageName(pkgName)
        setVersionCode(versionCode)
        setVersionName(versionName)
        setApplicationLabel(appName.resourceId)
        setIconResourceId(appIcon.resourceId)
        setTargetSdkVersion(targetSdk)
        setMinSdkVersion(minSdk)
    }

    val application = manifest.getOrCreateApplicationElement()

    val metaData = application.getOrCreateElement("meta-data")
    metaData.androidAttribute("name", ANDROID_ATTR_NAME, STUB_TARGET_META_DATA)
    metaData.androidAttribute("value", ANDROID_ATTR_VALUE, targetPackage)

    forwardingComponent(application.getOrCreateElement("receiver"), STUB_RECEIVER_CLASS)
    forwardingComponent(application.getOrCreateElement("activity"), STUB_ACTIVITY_CLASS)

    return module
}

/**
 * One of the two components that pass a request on: named, exported - the request comes from
 * outside, from a shell that is not an app - and listening for [STUB_REQUEST_ACTION].
 */
private fun forwardingComponent(element: ResXmlElement, className: String) {
    element.androidAttribute("name", ANDROID_ATTR_NAME, className)
    element.getOrCreateAndroidAttribute("exported", ANDROID_ATTR_EXPORTED)
        .setValueAsBoolean(true)

    element.getOrCreateElement("intent-filter")
        .getOrCreateElement("action")
        .androidAttribute("name", ANDROID_ATTR_NAME, STUB_REQUEST_ACTION)
}

private fun ResXmlElement.androidAttribute(name: String, id: Int, value: String) {
    getOrCreateAndroidAttribute(name, id).setValueAsString(value)
}
