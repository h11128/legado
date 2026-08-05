package io.legado.app.help.config

import android.content.Context.MODE_PRIVATE
import androidx.core.content.edit
import io.legado.app.model.checkalgo.ChangeSourceAskMemory
import org.json.JSONObject
import splitties.init.appCtx
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk backing for title-empty ledger (RFC-002 companion).
 * Payload embeds `titleKey` so hash collisions cannot cross-hydrate books.
 */
object ChangeSourceTitleEmptyPrefs {

    private const val PREF = "change_source_title_empty"
    private const val KEY_PREFIX = "t:"
    private const val FIELD_TITLE = "_titleKey"
    private const val FIELD_URLS = "urls"

    private val sp by lazy {
        appCtx.getSharedPreferences(PREF, MODE_PRIVATE)
    }

    /** Serialize writes per titleKey so mapParallel empties do not clobber each other. */
    private val locks = ConcurrentHashMap<String, Any>()

    fun hydrate(name: String, author: String) {
        val titleKey = ChangeSourceAskMemory.titleKey(name, author)
        val raw = sp.getString(prefKey(titleKey), null) ?: return
        val entries = parse(raw, expectedTitleKey = titleKey) ?: return
        if (entries.isEmpty()) return
        ChangeSourceAskMemory.importTitleEmpty(titleKey, entries)
        persist(titleKey, ChangeSourceAskMemory.titleEmptySnapshotTimed(titleKey))
    }

    fun persistCurrent(name: String, author: String) {
        val titleKey = ChangeSourceAskMemory.titleKey(name, author)
        val lock = locks.getOrPut(titleKey) { Any() }
        synchronized(lock) {
            persist(titleKey, ChangeSourceAskMemory.titleEmptySnapshotTimed(titleKey))
        }
    }

    private fun persist(titleKey: String, entries: Map<String, Long>) {
        val key = prefKey(titleKey)
        sp.edit {
            if (entries.isEmpty()) {
                remove(key)
            } else {
                putString(key, serialize(titleKey, entries))
            }
        }
    }

    private fun prefKey(titleKey: String): String =
        KEY_PREFIX + sha256Hex(titleKey).take(32)

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return dig.joinToString("") { b -> "%02x".format(b) }
    }

    private fun serialize(titleKey: String, entries: Map<String, Long>): String {
        val urls = JSONObject()
        entries.forEach { (url, ts) -> urls.put(url, ts) }
        return JSONObject()
            .put(FIELD_TITLE, titleKey)
            .put(FIELD_URLS, urls)
            .toString()
    }

    /**
     * @return null if payload missing/mismatched titleKey (collision or corrupt).
     */
    private fun parse(raw: String, expectedTitleKey: String): Map<String, Long>? {
        return runCatching {
            val root = JSONObject(raw)
            // Legacy flat `{url: ts}` without title — refuse (avoid silent cross-book).
            if (!root.has(FIELD_TITLE)) return null
            val stored = root.optString(FIELD_TITLE, "")
            if (stored != expectedTitleKey) return null
            val urls = root.optJSONObject(FIELD_URLS) ?: return emptyMap()
            buildMap {
                val keys = urls.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, urls.optLong(k, 0L))
                }
            }.filterValues { it > 0L }
        }.getOrNull()
    }

    /** Tests / debug. */
    fun clearAll() {
        sp.edit { clear() }
    }
}
