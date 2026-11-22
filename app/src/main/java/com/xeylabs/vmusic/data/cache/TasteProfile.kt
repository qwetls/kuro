package com.xeylabs.vmusic.data.cache

import android.content.Context
import android.content.SharedPreferences
import com.xeylabs.vmusic.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class TasteArtist(
    val name: String,
    val plays: Int,
    val likedCount: Int
) {
    val score: Int
        get() = plays + TasteProfile.LIKE_WEIGHT * likedCount
}

class TasteProfile private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _topTracks = MutableStateFlow<List<Track>>(emptyList())
    val topTracks: StateFlow<List<Track>> = _topTracks.asStateFlow()

    private val _topArtists = MutableStateFlow<List<TasteArtist>>(emptyList())
    val topArtists: StateFlow<List<TasteArtist>> = _topArtists.asStateFlow()

    private val trackEntries = LinkedHashMap<String, TasteTrackEntry>()
    private val artistEntries = LinkedHashMap<String, TasteArtistEntry>()

    init {
        load()
    }

    @Synchronized
    fun recordPlay(track: Track) {
        if (track.id.isBlank() || track.isCollection || track.isArtistProfile) return
        val entry = trackEntries.getOrPut(track.id) {
            TasteTrackEntry(
                id = track.id,
                url = track.url,
                title = track.title,
                artist = track.artist,
                thumbnailUrl = track.thumbnailUrl,
                durationSeconds = track.durationSeconds
            )
        }
        refreshTrackMeta(entry, track)
        entry.plays += 1
        entry.lastPlayedMs = System.currentTimeMillis()
        val artistName = cleanArtist(track.artist)
        if (artistName != null) {
            val artistEntry = artistEntries.getOrPut(artistName) { TasteArtistEntry(name = artistName) }
            artistEntry.plays += 1
        }
        pruneLocked()
        refreshFlows()
        persistLocked()
    }

    @Synchronized
    fun recordLike(track: Track, liked: Boolean) {
        if (track.id.isBlank() || track.isCollection || track.isArtistProfile) return
        val entry = trackEntries.getOrPut(track.id) {
            TasteTrackEntry(
                id = track.id,
                url = track.url,
                title = track.title,
                artist = track.artist,
                thumbnailUrl = track.thumbnailUrl,
                durationSeconds = track.durationSeconds
            )
        }
        refreshTrackMeta(entry, track)
        entry.liked = liked
        val artistName = cleanArtist(track.artist)
        if (artistName != null) {
            val artistEntry = artistEntries.getOrPut(artistName) { TasteArtistEntry(name = artistName) }
            artistEntry.likedCount = trackEntries.values.count {
                it.liked && cleanArtist(it.artist) == artistName
            }
        }
        pruneLocked()
        refreshFlows()
        persistLocked()
    }

    @Synchronized
    fun isLiked(trackId: String): Boolean {
        return trackEntries[trackId]?.liked == true
    }

    fun topTracks(limit: Int): List<Track> {
        return _topTracks.value.take(limit.coerceAtLeast(0))
    }

    fun topArtists(limit: Int): List<TasteArtist> {
        return _topArtists.value.take(limit.coerceAtLeast(0))
    }

    fun seedTrack(rotation: Int): Track? {
        val pool = _topTracks.value.take(SEED_POOL_SIZE)
        if (pool.isEmpty()) return null
        val index = ((rotation % pool.size) + pool.size) % pool.size
        return pool[index]
    }

    private fun refreshTrackMeta(entry: TasteTrackEntry, track: Track) {
        if (track.url.isNotBlank()) entry.url = track.url
        if (track.title.isNotBlank()) entry.title = track.title
        if (track.artist.isNotBlank()) entry.artist = track.artist
        if (track.thumbnailUrl.isNotBlank()) entry.thumbnailUrl = track.thumbnailUrl
        if (track.durationSeconds > 0L) entry.durationSeconds = track.durationSeconds
    }

    private fun cleanArtist(raw: String): String? {
        val clean = raw.substringBefore("•").substringBefore(",").trim()
        if (clean.length < 2 || clean.equals("Unknown Artist", ignoreCase = true)) return null
        return clean
    }

    private fun pruneLocked() {
        if (trackEntries.size > MAX_TRACKS) {
            val keep = trackEntries.values
                .sortedWith(compareByDescending<TasteTrackEntry> { it.score }.thenByDescending { it.lastPlayedMs })
                .take(MAX_TRACKS)
                .map { it.id }
                .toHashSet()
            val iterator = trackEntries.keys.iterator()
            while (iterator.hasNext()) {
                if (iterator.next() !in keep) iterator.remove()
            }
        }
        if (artistEntries.size > MAX_ARTISTS) {
            val keep = artistEntries.values
                .sortedWith(compareByDescending<TasteArtistEntry> { it.score }.thenByDescending { it.plays })
                .take(MAX_ARTISTS)
                .map { it.name }
                .toHashSet()
            val iterator = artistEntries.keys.iterator()
            while (iterator.hasNext()) {
                if (iterator.next() !in keep) iterator.remove()
            }
        }
    }

    private fun refreshFlows() {
        val sortedTracks = trackEntries.values
            .sortedWith(compareByDescending<TasteTrackEntry> { it.score }.thenByDescending { it.lastPlayedMs })
        _topTracks.value = sortedTracks.map { it.toTrack() }
        val sortedArtists = artistEntries.values
            .sortedWith(compareByDescending<TasteArtistEntry> { it.score }.thenByDescending { it.plays })
        _topArtists.value = sortedArtists.map { TasteArtist(it.name, it.plays, it.likedCount) }
    }

    private fun persistLocked() {
        val tracksJson = tracksToJson()
        val artistsJson = artistsToJson()
        scope.launch {
            runCatching {
                JsonBackupPrefs.writeJsonArray(prefs, KEY_TRACKS, tracksJson)
                JsonBackupPrefs.writeJsonArray(prefs, KEY_ARTISTS, artistsJson)
            }
        }
    }

    private fun tracksToJson(): String {
        val arr = JSONArray()
        for (entry in trackEntries.values) {
            arr.put(JSONObject().apply {
                put("id", entry.id)
                put("url", entry.url)
                put("title", entry.title)
                put("artist", entry.artist)
                put("thumbnailUrl", entry.thumbnailUrl)
                put("durationSeconds", entry.durationSeconds)
                put("plays", entry.plays)
                put("liked", entry.liked)
                put("lastPlayedMs", entry.lastPlayedMs)
            })
        }
        return arr.toString()
    }

    private fun artistsToJson(): String {
        val arr = JSONArray()
        for (entry in artistEntries.values) {
            arr.put(JSONObject().apply {
                put("name", entry.name)
                put("plays", entry.plays)
                put("likedCount", entry.likedCount)
            })
        }
        return arr.toString()
    }

    private fun load() {
        runCatching {
            val tracksRaw = JsonBackupPrefs.readJsonArray(prefs, KEY_TRACKS) ?: "[]"
            val tracksArr = JSONArray(tracksRaw)
            for (i in 0 until tracksArr.length()) {
                val obj = tracksArr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isBlank()) continue
                trackEntries[id] = TasteTrackEntry(
                    id = id,
                    url = obj.optString("url"),
                    title = obj.optString("title"),
                    artist = obj.optString("artist"),
                    thumbnailUrl = obj.optString("thumbnailUrl"),
                    durationSeconds = obj.optLong("durationSeconds", 0L),
                    plays = obj.optInt("plays", 0),
                    liked = obj.optBoolean("liked", false),
                    lastPlayedMs = obj.optLong("lastPlayedMs", 0L)
                )
            }
            val artistsRaw = JsonBackupPrefs.readJsonArray(prefs, KEY_ARTISTS) ?: "[]"
            val artistsArr = JSONArray(artistsRaw)
            for (i in 0 until artistsArr.length()) {
                val obj = artistsArr.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                if (name.isBlank()) continue
                artistEntries[name] = TasteArtistEntry(
                    name = name,
                    plays = obj.optInt("plays", 0),
                    likedCount = obj.optInt("likedCount", 0)
                )
            }
            refreshFlows()
        }
    }

    private class TasteTrackEntry(
        val id: String,
        var url: String,
        var title: String,
        var artist: String,
        var thumbnailUrl: String,
        var durationSeconds: Long,
        var plays: Int = 0,
        var liked: Boolean = false,
        var lastPlayedMs: Long = 0L
    ) {
        val score: Int
            get() = plays + if (liked) LIKE_WEIGHT else 0

        fun toTrack(): Track {
            return Track(
                id = id,
                url = url,
                title = title,
                artist = artist,
                thumbnailUrl = thumbnailUrl,
                durationSeconds = durationSeconds,
                isFavorite = liked
            )
        }
    }

    private class TasteArtistEntry(
        val name: String,
        var plays: Int = 0,
        var likedCount: Int = 0
    ) {
        val score: Int
            get() = plays + LIKE_WEIGHT * likedCount
    }

    companion object {
        const val LIKE_WEIGHT = 5
        private const val PREF_NAME = "vmusic_taste"
        private const val KEY_TRACKS = "taste_tracks"
        private const val KEY_ARTISTS = "taste_artists"
        private const val MAX_TRACKS = 200
        private const val MAX_ARTISTS = 100
        private const val SEED_POOL_SIZE = 3

        @Volatile
        private var INSTANCE: TasteProfile? = null

        fun getInstance(context: Context): TasteProfile {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TasteProfile(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
