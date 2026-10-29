package dev.liquidcalendar.data

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Contraseñas de las cuentas, cifradas con AES-256-GCM. La clave vive en el Android Keystore (en
 * StrongBox si el celu lo tiene) y no se puede extraer; el texto cifrado se guarda en
 * noBackupFilesDir, fuera de cualquier copia de seguridad. Nunca se escribe la contraseña en
 * claro en ningún sitio.
 */
class CredentialStore(context: Context) {
    private val hasStrongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    private val dir = File(context.noBackupFilesDir, "credentials").apply {
        mkdirs()
        setReadable(false, false); setReadable(true, true)
        setWritable(false, false); setWritable(true, true)
        setExecutable(false, false); setExecutable(true, true)
    }

    private fun file(accountId: Long) = AtomicFile(File(dir, "account-$accountId.bin"))
    private fun alias(accountId: Long) = "liquidcalendar.account.$accountId"

    fun save(accountId: Long, password: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(accountId))
        val iv = cipher.iv
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val atomic = file(accountId)
        val out = atomic.startWrite()
        try {
            out.write(byteArrayOf(VERSION, iv.size.toByte()))
            out.write(iv)
            out.write(encrypted)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    /** La contraseña guardada, o null si no hay o no se puede descifrar (p. ej. clave borrada). */
    fun load(accountId: Long): String? {
        val bytes = try {
            file(accountId).readFully()
        } catch (e: Exception) {
            return null
        }
        if (bytes.size < 2 || bytes[0] != VERSION) return null
        val ivSize = bytes[1].toInt()
        if (bytes.size < 2 + ivSize + 16) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey(accountId) ?: return null, GCMParameterSpec(128, bytes, 2, ivSize))
            String(cipher.doFinal(bytes, 2 + ivSize, bytes.size - 2 - ivSize), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun has(accountId: Long): Boolean = File(dir, "account-$accountId.bin").exists()

    /** Borra el texto cifrado y la clave del Keystore. */
    fun delete(accountId: Long) {
        file(accountId).delete()
        runCatching { keyStore().deleteEntry(alias(accountId)) }
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(accountId: Long): SecretKey? = keyStore().getKey(alias(accountId), null) as? SecretKey

    private fun key(accountId: Long): SecretKey {
        existingKey(accountId)?.let { return it }
        // Sin autenticación del usuario: la sincronización en segundo plano necesita la clave.
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            alias(accountId),
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        return try {
            generator.init(spec(hasStrongBox))
            generator.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            generator.init(spec(false))
            generator.generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
    }
}
