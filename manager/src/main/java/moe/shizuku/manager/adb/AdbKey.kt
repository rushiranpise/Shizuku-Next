package moe.shizuku.manager.adb

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ktx.isUserUnlockedCompat
import moe.shizuku.manager.utils.Diag
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import rikka.core.ktx.unsafeLazy
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

private const val TAG = "AdbKey"

class AdbKey(private val adbKeyStore: AdbKeyStore, name: String) {

    companion object {

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ENCRYPTION_KEY_ALIAS = "_adbkey_encryption_key_"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** Which side made the AES key that wraps the ADB private key. */
        private const val MODE_KEYSTORE = "keystore"
        private const val MODE_SOFTWARE = "software"
        private const val PREF_ENCRYPTION_MODE = "adbkey_encryption_mode"
        private const val PREF_SOFTWARE_KEY = "adbkey_software_key"

        private const val IV_SIZE_IN_BYTES = 12
        private const val TAG_SIZE_IN_BYTES = 16

        private val PADDING = byteArrayOf(
                0x00, 0x01, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0x00,
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00,
                0x04, 0x14)
    }

    private val encryptionKey: Key

    private val privateKey: RSAPrivateKey
    private val publicKey: RSAPublicKey
    private val certificate: X509Certificate

    init {
        this.encryptionKey = getOrCreateEncryptionKey()

        this.privateKey = getOrCreatePrivateKey()
        this.publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4)) as RSAPublicKey

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val x509Certificate = X509v3CertificateBuilder(X500Name("CN=00"),
                BigInteger.ONE,
                Date(0),
                Date(2461449600 * 1000),
                Locale.ROOT,
                X500Name("CN=00"),
                SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        ).build(signer)
        this.certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(x509Certificate.encoded)) as X509Certificate

        Log.d(TAG, privateKey.toString())
    }

    val adbPublicKey: ByteArray by unsafeLazy {
        publicKey.adbEncoded(name)
    }

    /**
     * The key that encrypts the stored ADB private key.
     *
     * The Android Keystore is the one this app wants: the key never leaves the TEE, and a copy of
     * the preferences cannot take it to another device. Some devices cannot create that key at
     * all - a OnePlus Nord CE 5G on Android 13 answers with `ProviderException: Keystore key
     * generation failed` - and refusing to start there leaves Shizuku permanently broken over a
     * KeyMint that will not make an AES key. So the failure is answered with a software key kept in
     * this app's preferences, and the choice is written down so every later run uses the same one:
     * a run that went back to the keystore would find the ADB key stored under the software key
     * unreadable and replace it, losing the pairing.
     */
    private fun getOrCreateEncryptionKey(): Key {
        if (adbKeyStore.getString(PREF_ENCRYPTION_MODE) == MODE_SOFTWARE) {
            return softwareEncryptionKey()
        }

        val fromKeyStore = runCatching { getOrCreateKeyStoreKey() }
            .onFailure {
                Diag.warn(TAG, "AndroidKeyStore cannot make the ADB key encryption key", it)
            }
            .getOrNull()
        if (fromKeyStore != null) {
            adbKeyStore.putString(PREF_ENCRYPTION_MODE, MODE_KEYSTORE)
            return fromKeyStore
        }

        // Written before the key is made: a process killed in between would otherwise come back
        // to the keystore and generate a new ADB key over the one it left here.
        adbKeyStore.putString(PREF_ENCRYPTION_MODE, MODE_SOFTWARE)
        return softwareEncryptionKey()
    }

    /** The keystore key, made when there is none. */
    private fun getOrCreateKeyStoreKey(): Key {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        keyStore.getKey(ENCRYPTION_KEY_ALIAS, null)?.let { return it }

        // Tried more than once because a KeyMint under load reports transient failures as the same
        // exception, and a phone that answers this way three times is one to fall back on rather
        // than to keep the user waiting on.
        var failure: Throwable? = null
        repeat(3) { attempt ->
            try {
                val parameterSpec = KeyGenParameterSpec.Builder(
                    ENCRYPTION_KEY_ALIAS,
                    KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Naming the ordinary TEE rather than leaving it to a device that may report a
                    // StrongBox it cannot reach and fail the generation for that reason.
                    parameterSpec.setIsStrongBoxBacked(false)
                }
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                keyGenerator.init(parameterSpec.build())
                return keyGenerator.generateKey()
            } catch (t: Throwable) {
                failure = t
                // An alias that exists but cannot be read looks the same as one that is not there
                // from the answer above, and generation then fails because the name is taken.
                // Removing it is safe: a key that could not be read is not one this run can use,
                // and the ciphertext it would have opened is unreadable either way.
                runCatching { keyStore.deleteEntry(ENCRYPTION_KEY_ALIAS) }
                if (attempt < 2) Thread.sleep(100)
            }
        }
        throw failure ?: IllegalStateException("AndroidKeyStore generated no key and gave no reason")
    }

    /**
     * A key this app can always make.
     *
     * It is not hardware-backed and it sits beside the ciphertext, so it is not the protection the
     * keystore is. It is what keeps a phone whose keystore cannot make an AES key able to start
     * Shizuku at all: the ADB private key is still not stored as plain text, and both halves stay
     * inside this app's sandbox.
     */
    private fun softwareEncryptionKey(): Key {
        adbKeyStore.getString(PREF_SOFTWARE_KEY)?.let { stored ->
            return SecretKeySpec(Base64.decode(stored, Base64.NO_WRAP), "AES")
        }

        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        adbKeyStore.putString(PREF_SOFTWARE_KEY, String(Base64.encode(bytes, Base64.NO_WRAP)))
        return SecretKeySpec(bytes, "AES")
    }

    private fun encrypt(plaintext: ByteArray, aad: ByteArray?): ByteArray? {
        if (plaintext.size > Int.MAX_VALUE - IV_SIZE_IN_BYTES - TAG_SIZE_IN_BYTES) {
            return null
        }
        val ciphertext = ByteArray(IV_SIZE_IN_BYTES + plaintext.size + TAG_SIZE_IN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        cipher.updateAAD(aad)
        cipher.doFinal(plaintext, 0, plaintext.size, ciphertext, IV_SIZE_IN_BYTES)
        System.arraycopy(cipher.iv, 0, ciphertext, 0, IV_SIZE_IN_BYTES)
        return ciphertext
    }

    private fun decrypt(ciphertext: ByteArray, aad: ByteArray?): ByteArray? {
        if (ciphertext.size < IV_SIZE_IN_BYTES + TAG_SIZE_IN_BYTES) {
            return null
        }
        val params = GCMParameterSpec(8 * TAG_SIZE_IN_BYTES, ciphertext, 0, IV_SIZE_IN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, params)
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext, IV_SIZE_IN_BYTES, ciphertext.size - IV_SIZE_IN_BYTES)
    }

    private fun getOrCreatePrivateKey(): RSAPrivateKey {
        var privateKey: RSAPrivateKey? = null

        val aad = ByteArray(16)
        "adbkey".toByteArray().copyInto(aad)

        var ciphertext = adbKeyStore.get()
        if (ciphertext != null) {
            try {
                val plaintext = decrypt(ciphertext, aad)

                val keyFactory = KeyFactory.getInstance("RSA")
                privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(plaintext)) as RSAPrivateKey
            } catch (e: Exception) {
                // A key that cannot be read is not a key that does not exist, and before the first
                // unlock the keystore may well not be able to read it yet. Generating a new one here
                // would be saved over the good one, and the device that trusted it would no longer
                // know this app: a pairing nobody asked to lose. So while the user is locked the
                // failure is the answer, and the start reports it; the start made at the unlock
                // reads the key properly. Unlocked, a key that cannot be decrypted really is gone
                // (a restored backup, a reset keystore) and a new one is the only way forward.
                if (!ShizukuApplication.appContext.isUserUnlockedCompat()) {
                    throw IllegalStateException(
                        "The ADB key could not be read while the user is locked", e
                    )
                }
            }
        }
        if (privateKey == null) {
            val keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
            keyPairGenerator.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            val keyPair = keyPairGenerator.generateKeyPair()
            privateKey = keyPair.private as RSAPrivateKey

            ciphertext = encrypt(privateKey.encoded, aad)
            if (ciphertext != null) {
                adbKeyStore.put(ciphertext)
            }
        }
        return privateKey
    }

    fun sign(data: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(PADDING)
        return cipher.doFinal(data)
    }

    private val keyManager
        get() = object : X509ExtendedKeyManager() {
            private val alias = "key"

            override fun chooseClientAlias(keyTypes: Array<out String>, issuers: Array<out Principal>?, socket: Socket?): String? {
                Log.d(TAG, "chooseClientAlias: keyType=${keyTypes.contentToString()}, issuers=${issuers?.contentToString()}")
                for (keyType in keyTypes) {
                    if (keyType == "RSA") return alias
                }
                return null
            }

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
                Log.d(TAG, "getCertificateChain: alias=$alias")
                return if (alias == this.alias) arrayOf(certificate) else null
            }

            override fun getPrivateKey(alias: String?): PrivateKey? {
                Log.d(TAG, "getPrivateKey: alias=$alias")
                return if (alias == this.alias) privateKey else null
            }

            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
                return null
            }

            override fun getServerAliases(keyType: String, issuers: Array<out Principal>?): Array<String>? {
                return null
            }

            override fun chooseServerAlias(keyType: String, issuers: Array<out Principal>?, socket: Socket?): String? {
                return null
            }
        }


    private val trustManager
        get() =
            @RequiresApi(Build.VERSION_CODES.R)
            object : X509ExtendedTrustManager() {

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
                }

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
                }

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                }

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
                }

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
                }

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    return emptyArray()
                }
            }

    @delegate:RequiresApi(Build.VERSION_CODES.R)
    val sslContext: SSLContext by unsafeLazy {
        val sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        sslContext
    }
}

interface AdbKeyStore {

    fun put(bytes: ByteArray)

    fun get(): ByteArray?

    /** A value kept with the key, for the encryption mode and the software key itself. */
    fun getString(key: String): String?

    fun putString(key: String, value: String)
}

class PreferenceAdbKeyStore(private val preference: SharedPreferences) : AdbKeyStore {

    private val preferenceKey = "adbkey"

    override fun put(bytes: ByteArray) {
        preference.edit { putString(preferenceKey, String(Base64.encode(bytes, Base64.NO_WRAP))) }
    }

    override fun get(): ByteArray? {
        if (!preference.contains(preferenceKey)) return null
        return Base64.decode(preference.getString(preferenceKey, null), Base64.NO_WRAP)
    }

    override fun getString(key: String): String? = preference.getString(key, null)

    override fun putString(key: String, value: String) {
        preference.edit { putString(key, value) }
    }
}

const val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8
const val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4
const val RSAPublicKey_Size = 524

private fun BigInteger.toAdbEncoded(): IntArray {
    // little-endian integer with padding zeros in the end

    val endcoded = IntArray(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    val r32 = BigInteger.ZERO.setBit(32)

    var tmp = this.add(BigInteger.ZERO)
    for (i in 0 until ANDROID_PUBKEY_MODULUS_SIZE_WORDS) {
        val out = tmp.divideAndRemainder(r32)
        tmp = out[0]
        endcoded[i] = out[1].toInt()
    }
    return endcoded
}

private fun RSAPublicKey.adbEncoded(name: String): ByteArray {
    // https://cs.android.com/android/platform/superproject/+/android-10.0.0_r30:system/core/libcrypto_utils/android_pubkey.c

    /*
    typedef struct RSAPublicKey {
        uint32_t modulus_size_words; // ANDROID_PUBKEY_MODULUS_SIZE
        uint32_t n0inv; // n0inv = -1 / N[0] mod 2^32
        uint8_t modulus[ANDROID_PUBKEY_MODULUS_SIZE];
        uint8_t rr[ANDROID_PUBKEY_MODULUS_SIZE]; // rr = (2^(rsa_size)) ^ 2 mod N
        uint32_t exponent;
    } RSAPublicKey;
    */

    val r32 = BigInteger.ZERO.setBit(32)
    val n0inv = modulus.remainder(r32).modInverse(r32).negate()
    val r = BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8)
    val rr = r.modPow(BigInteger.valueOf(2), modulus)

    val buffer = ByteBuffer.allocate(RSAPublicKey_Size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    buffer.putInt(n0inv.toInt())
    modulus.toAdbEncoded().forEach { buffer.putInt(it) }
    rr.toAdbEncoded().forEach { buffer.putInt(it) }
    buffer.putInt(publicExponent.toInt())

    val base64Bytes = Base64.encode(buffer.array(), Base64.NO_WRAP)
    val nameBytes = " $name\u0000".toByteArray()
    val bytes = ByteArray(base64Bytes.size + nameBytes.size)
    base64Bytes.copyInto(bytes)
    nameBytes.copyInto(bytes, base64Bytes.size)
    return bytes
}
