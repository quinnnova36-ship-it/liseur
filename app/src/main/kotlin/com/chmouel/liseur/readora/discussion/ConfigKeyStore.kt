package com.chmouel.liseur.readora.discussion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.MessageDigest

/**
 * Where each model config's key lives (Readora S3).
 *
 * One file per config id, sealed by the caller's cipher — the Android
 * Keystore path the app already uses for its other credentials. The
 * file name derives from the config id alone (never the origin), so
 * two configs on the same server hold two independent keys, and
 * nothing on disk links a key to an address.
 */
class ConfigKeyStore(
    dir: File,
    private val seal: (String) -> String,
    private val unseal: (String) -> String?,
) {
    private val dir = dir.apply { mkdirs() }

    /** Config ids known to hold a key right now. */
    private val present = MutableStateFlow<Set<String>>(emptySet())
    val configured: StateFlow<Set<String>> = present

    init {
        refresh()
    }

    fun keyFor(configId: String): String? =
        fileFor(configId).takeIf { it.isFile }?.readText()?.let(sealable@{ sealed ->
            unseal(sealed) ?: run {
                // A key the cipher can no longer open (a restored file
                // from another install) is no key at all; drop it.
                fileFor(configId).delete()
                refresh()
                null
            }
        })

    fun put(configId: String, key: String) {
        fileFor(configId).writeText(seal(key))
        refresh()
    }

    fun clear(configId: String) {
        fileFor(configId).delete()
        refresh()
    }

    private fun refresh() {
        present.value = dir.listFiles { f -> f.name.startsWith(PREFIX) }
            .orEmpty()
            .mapNotNull { f -> f.name.removePrefix(PREFIX).takeIf { it.isNotEmpty() } }
            .toSet()
    }

    private fun fileFor(configId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(configId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return File(dir, PREFIX + digest)
    }

    private companion object {
        const val PREFIX = "model-key-"
    }
}
