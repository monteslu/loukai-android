package com.loukai.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

enum class SongType { STEMS, CDG }

data class Song(
    val id: String,
    val uri: Uri,
    val cdgUri: Uri?,
    val title: String,
    val artist: String,
    val type: SongType,
) {
    val display: String get() = if (artist.isEmpty()) title else "$artist - $title"

    /** "A".."Z", or "#" for anything else; used for the car's browse folders. */
    val letter: String
        get() {
            val c = display.firstOrNull()?.uppercaseChar() ?: '#'
            return if (c in 'A'..'Z') c.toString() else "#"
        }
}

/**
 * The song list: every stem file and every mp3 with a matching .cdg under the
 * folder the user picked. Scans go through the Storage Access Framework (the
 * folder is a persisted tree URI) and the result is cached as JSON so startup
 * and the car's browse tree don't wait on a rescan.
 */
object Library {
    @Volatile var songs: List<Song> = emptyList()
        private set
    @Volatile private var byId: Map<String, Song> = emptyMap()
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "songs.json")

    fun folder(ctx: Context): Uri? = prefs(ctx).getString("tree", null)?.let(Uri::parse)

    fun setFolder(ctx: Context, tree: Uri) {
        ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs(ctx).edit().putString("tree", tree.toString()).apply()
    }

    fun get(id: String): Song? = byId[id]

    fun search(query: String): List<Song> {
        val q = query.trim()
        if (q.isEmpty()) return songs
        val terms = q.lowercase().split(Regex("\\s+"))
        return songs.filter { s -> val d = s.display.lowercase(); terms.all { d.contains(it) } }
    }

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val file = cacheFile(ctx)
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Song(
                    id = o.getString("id"),
                    uri = Uri.parse(o.getString("uri")),
                    cdgUri = o.optString("cdg").takeIf { it.isNotEmpty() }?.let(Uri::parse),
                    title = o.getString("title"),
                    artist = o.optString("artist"),
                    type = SongType.valueOf(o.getString("type")),
                )
            }
            publish(list)
        }
    }

    /** Blocking; run off the main thread. [progress] gets the running song count. */
    fun scan(ctx: Context, progress: (Int) -> Unit) {
        val tree = folder(ctx) ?: return
        val out = ArrayList<Song>()
        scanDir(ctx, tree, DocumentsContract.getTreeDocumentId(tree), out, progress)
        out.sortBy { it.display.lowercase() }

        val arr = JSONArray()
        for (s in out) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("uri", s.uri.toString())
                    .put("cdg", s.cdgUri?.toString() ?: "")
                    .put("title", s.title)
                    .put("artist", s.artist)
                    .put("type", s.type.name)
            )
        }
        cacheFile(ctx).writeText(arr.toString())
        publish(out)
    }

    private fun publish(list: List<Song>) {
        songs = list
        byId = list.associateBy { it.id }
        listeners.forEach { it() }
    }

    private class Entry(val docId: String, val name: String, val mime: String)

    private fun scanDir(ctx: Context, tree: Uri, parentId: String, out: MutableList<Song>, progress: (Int) -> Unit) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val entries = ArrayList<Entry>()
        ctx.contentResolver.query(
            children,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) entries += Entry(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "")
        }

        val cdgByBase = entries
            .filter { it.name.endsWith(".cdg", ignoreCase = true) }
            .associateBy { it.name.substringBeforeLast('.').lowercase() }

        for (e in entries) {
            val lower = e.name.lowercase()
            when {
                e.mime == Document.MIME_TYPE_DIR -> scanDir(ctx, tree, e.docId, out, progress)
                lower.endsWith(".mp4") || lower.endsWith(".m4a") -> {
                    val (artist, title) = parseName(e.name)
                    out += Song(e.docId, docUri(tree, e.docId), null, title, artist, SongType.STEMS)
                    progress(out.size)
                }
                lower.endsWith(".mp3") -> {
                    val cdg = cdgByBase[e.name.substringBeforeLast('.').lowercase()] ?: continue
                    val (artist, title) = parseName(e.name)
                    out += Song(e.docId, docUri(tree, e.docId), docUri(tree, cdg.docId), title, artist, SongType.CDG)
                    progress(out.size)
                }
            }
        }
    }

    private fun docUri(tree: Uri, docId: String) = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    /** "Artist - Title.stem.mp4" -> (artist, title). Same rule as Loukai's parseFilenameMetadata. */
    fun parseName(fileName: String): Pair<String, String> {
        val base = fileName.substringBeforeLast('.').replace(Regex("\\.stem$", RegexOption.IGNORE_CASE), "")
        val dash = base.indexOf(" - ")
        return if (dash > 0 && dash < base.length - 3) {
            base.substring(0, dash).trim() to base.substring(dash + 3).trim()
        } else {
            "" to base
        }
    }
}
