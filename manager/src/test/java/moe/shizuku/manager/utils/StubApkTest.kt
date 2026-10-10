package moe.shizuku.manager.utils

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The stub APK the stealth screen installs, assembled, written out and read back the way a device
 * reads it.
 *
 * Two things can go wrong here and neither throws where it is written: a component the manifest
 * names but the dex does not contain is a `ClassNotFoundException` at the moment a client asks for
 * the binder, and a stub declaring something of the app's own - the permission clients look Shizuku
 * up by, a provider authority - makes a second app claim what the installed copy answers to. Both
 * are checked against the artifact itself rather than against the code that wrote it.
 */
class StubApkTest {

    private val stubPackage = "moe.shizuku.privileged.api"
    private val hiddenCopy = "moe.morphe.shizuku.privileged.api"
    private val label = "Shizuku Stub"

    // android:name, android:exported and android:value: what the platform reads these by.
    private val attrName = 0x01010003
    private val attrExported = 0x01010010
    private val attrValue = 0x01010024

    private fun stubDex(): File =
        sequenceOf("src/main/assets", "manager/src/main/assets")
            .map { File(it, STUB_DEX_ASSET) }
            .firstOrNull { it.isFile }
            ?: throw AssertionError("$STUB_DEX_ASSET is missing; :stub has to be assembled first")

    private fun build(): ApkModule =
        stubApkModule(
            dex = stubDex().readBytes(),
            pkgName = stubPackage,
            targetPackage = hiddenCopy,
            label = label,
            iconReference = 0x7f080001,
            versionCode = Int.MAX_VALUE,
            versionName = "14.0.21-next",
            targetSdk = 36,
            minSdk = 24
        )

    /** The stub manifest as an installed one reads: written out and loaded again. */
    private fun reloaded(): AndroidManifestBlock {
        val out = File.createTempFile("stub", ".apk")
        try {
            build().writeApk(out)
            return ApkModule.loadApkFile(out).androidManifest
        } finally {
            out.delete()
        }
    }

    private fun components(manifest: AndroidManifestBlock): List<ResXmlElement> {
        val application = manifest.applicationElement
        assertNotNull("the stub has no application element", application)
        return application.getElements().asSequence().toList()
    }

    private fun ResXmlElement.androidString(id: Int): String? =
        searchAttributeByResourceId(id)?.valueAsString

    private fun ResXmlElement.androidBoolean(id: Int): Boolean? =
        searchAttributeByResourceId(id)?.valueAsBoolean

    private fun ResXmlElement.actions(): List<String?> =
        getElements("intent-filter")
            .asSequence()
            .flatMap { it.getElements("action").asSequence() }
            .map { it.androidString(attrName) }
            .toList()

    @Test
    fun `it installs as the package it holds, above the store's ceiling`() {
        val manifest = reloaded()

        assertEquals(stubPackage, manifest.packageName)
        assertEquals(Int.MAX_VALUE, manifest.versionCode)
        assertEquals("14.0.21-next", manifest.versionName)
        assertEquals(36, manifest.targetSdkVersion)
        assertEquals(24, manifest.minSdkVersion)
    }

    @Test
    fun `it names the copy it passes requests on to`() {
        val metaData = components(reloaded()).singleOrNull { it.getName() == "meta-data" }
        assertNotNull("the stub has nothing to forward to", metaData)

        assertEquals(STUB_TARGET_META_DATA, metaData!!.androidString(attrName))
        assertEquals(hiddenCopy, metaData.androidString(attrValue))
    }

    @Test
    fun `both components answer the binder request and are exported`() {
        val declared = components(reloaded()).associateBy { it.getName() }

        for ((tag, className) in listOf("receiver" to STUB_RECEIVER_CLASS, "activity" to STUB_ACTIVITY_CLASS)) {
            val component = declared[tag]
            assertNotNull("the stub has no $tag", component)

            assertEquals(className, component!!.androidString(attrName))
            assertEquals(
                "$tag has to be exported: the request comes from a shell, which is not an app",
                true,
                component.androidBoolean(attrExported)
            )
            assertEquals(listOf(STUB_REQUEST_ACTION), component.actions())
        }
    }

    @Test
    fun `the classes it declares are in the dex it ships`() {
        val dex = String(stubDex().readBytes(), Charsets.ISO_8859_1)

        for (className in listOf(STUB_RECEIVER_CLASS, STUB_ACTIVITY_CLASS)) {
            val descriptor = "L" + className.replace('.', '/') + ";"
            assertTrue("$className is named by the manifest but not in the dex", dex.contains(descriptor))
        }
        assertTrue(
            "the stub cannot read $STUB_TARGET_META_DATA back out of its own manifest",
            dex.contains(STUB_TARGET_META_DATA)
        )
    }

    @Test
    fun `it declares nothing of the running copy`() {
        val manifest = reloaded()

        // Present: the two components and what tells them where to pass a request on.
        assertEquals(
            setOf("meta-data", "receiver", "activity"),
            components(manifest).map { it.getName() }.toSet()
        )

        // Absent: everything a second app claiming the app's names would bring with it - the
        // permission clients look Shizuku up by, the authorities, the services, the launcher.
        val declarations = mutableListOf<String>()
        val tagsOfConcern = setOf(
            "permission", "uses-permission", "permission-group",
            "provider", "service", "activity-alias"
        )
        manifest.recursiveElements().forEachRemaining { element ->
            if (element.getName() in tagsOfConcern) declarations.add(element.getName())
        }
        assertEquals(emptyList<String>(), declarations)
    }
}
