package eu.kanade.tachiyomi.animeextension.all.mygoogledrive

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MyGoogleDriveAnime : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "My Google Drive"
    override val baseUrl = "https://drive.google.com"
    override val lang = "all"
    override val supportsLatest = false

    private val apiUrl = "https://www.googleapis.com/drive/v3/files"

    // Endpoint field standar
    private val listFields = "nextPageToken,files(id,name)"
    private val episodeFields = "nextPageToken,files(id,name,modifiedTime,size)"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val apiKey: String
        get() = preferences.getString(API_KEY_PREF, "") ?: ""

    private val pathList: String
        get() = preferences.getString(PATH_LIST_PREF, "") ?: ""

    private val browsePageTokens = mutableMapOf<Int, String>()
    private var lastBrowseQuery = ""

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val apiKeyPref = EditTextPreference(screen.context).apply {
            key = API_KEY_PREF
            title = "API Key Google Cloud"
            summary = "Gunakan API Key yang sudah dibatasi untuk Google Drive API."
        }
        screen.addPreference(apiKeyPref)

        val pathListPref = EditTextPreference(screen.context).apply {
            key = PATH_LIST_PREF
            title = "Path list"
            summary = "Format: [Nama]URL;[Nama]URL"
        }
        screen.addPreference(pathListPref)
    }

    private fun checkPreferences() {
        if (apiKey.isBlank()) throw Exception("API Key belum diisi di pengaturan extension!")
        if (pathList.isBlank()) throw Exception("Path List belum diisi di pengaturan extension!")
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun extractFolderId(path: String): String? =
        Regex("folders/([a-zA-Z0-9_-]+)").find(path)?.groupValues?.get(1)

    private fun buildParentClauses(): List<String> =
        pathList.split(";")
            .filter { it.isNotBlank() }
            .mapNotNull { path -> extractFolderId(path)?.let { "'$it' in parents" } }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(dateStr)?.time ?: 0L
            } catch (e2: Exception) { 0L }
        }
    }

    // Helper natural sort untuk memastikan urutan episode akurat
    private fun naturalSortComparator(): Comparator<String> = Comparator { a, b ->
        val tokensA = tokenize(a)
        val tokensB = tokenize(b)
        val len = minOf(tokensA.size, tokensB.size)
        for (i in 0 until len) {
            val ta = tokensA[i]
            val tb = tokensB[i]
            val cmp = if (ta.first().isDigit() && tb.first().isDigit()) {
                ta.toBigInteger().compareTo(tb.toBigInteger())
            } else {
                ta.lowercase().compareTo(tb.lowercase())
            }
            if (cmp != 0) return@Comparator cmp
        }
        tokensA.size.compareTo(tokensB.size)
    }

    private fun tokenize(s: String): List<String> =
        Regex("(\\d+|\\D+)").findAll(s).map { it.value }.toList()

    private fun buildApiUrl(
        query: String,
        orderBy: String = "name",
        pageSize: Int = PAGE_SIZE_BROWSE,
        fields: String = listFields,
        pageToken: String? = null,
    ): String {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val sb = StringBuilder(
            "$apiUrl?q=$encodedQuery&orderBy=$orderBy&pageSize=$pageSize&fields=$fields&key=$apiKey",
        )
        if (pageToken != null) sb.append("&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}")
        return sb.toString()
    }

    // ─── Cover Image ─────────────────────────────────────────────────────────

    private fun getCoverUrlForAnime(animeFolderId: String): String {
        return try {
            val query = "'$animeFolderId' in parents and trashed = false and " +
                "(name = 'cover.jpg' or name = 'cover.jpeg' or name = 'cover.png' or name = 'poster.jpg')"
            val url = buildApiUrl(query, pageSize = 1, fields = "files(id)")
            val responseBody = client.newCall(GET(url, headers)).execute()
                .body?.string() ?: return fetchFirstImageAsCover(animeFolderId)
            val files = JSONObject(responseBody).optJSONArray("files")

            if (files != null && files.length() > 0) {
                "$baseUrl/uc?export=view&id=${files.getJSONObject(0).getString("id")}"
            } else {
                fetchFirstImageAsCover(animeFolderId)
            }
        } catch (e: Exception) { "" }
    }

    private fun fetchFirstImageAsCover(animeFolderId: String): String {
        return try {
            val query = "'$animeFolderId' in parents and mimeType contains 'image/' and trashed = false"
            val url = buildApiUrl(query, pageSize = 1, fields = "files(id)")
            val responseBody = client.newCall(GET(url, headers)).execute()
                .body?.string() ?: return ""
            val files = JSONObject(responseBody).optJSONArray("files")
            if (files != null && files.length() > 0) {
                "$baseUrl/uc?export=view&id=${files.getJSONObject(0).getString("id")}"
            } else { "" }
        } catch (e: Exception) { "" }
    }

    // ─── Browse / Search ─────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request {
        checkPreferences()
        val parentClauses = buildParentClauses()
        if (parentClauses.isEmpty()) throw Exception("Tidak ada URL folder valid di Path List.")

        val joinedParents = parentClauses.joinToString(" or ")
        val query = "($joinedParents) and mimeType = 'application/vnd.google-apps.folder' and trashed = false"

        if (page == 1) {
            browsePageTokens.clear()
            lastBrowseQuery = query
        }

        val token = browsePageTokens[page]
        return GET(buildApiUrl(query, pageToken = token), headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val body = response.body?.string() ?: return AnimesPage(emptyList(), false)
        val json = JSONObject(body)
        val files = json.optJSONArray("files") ?: return AnimesPage(emptyList(), false)

        val nextToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        val currentPage = browsePageTokens.size + 1
        if (nextToken != null) {
            browsePageTokens[currentPage + 1] = nextToken
        }

        val animes = (0 until files.length()).map { i ->
            val file = files.getJSONObject(i)
            SAnime.create().apply {
                title = file.getString("name")
                url = file.getString("id") // URL diisi dengan Folder ID
                thumbnail_url = null
            }
        }
        return AnimesPage(animes, hasNextPage = nextToken != null)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        checkPreferences()
        val parentClauses = buildParentClauses()
        if (parentClauses.isEmpty()) throw Exception("Tidak ada URL folder valid di Path List.")

        val joinedParents = parentClauses.joinToString(" or ")
        val nameFilter = if (query.isNotBlank()) {
            val safeQuery = query.replace("\\", "\\\\").replace("'", "\\'")
            " and name contains '$safeQuery'"
        } else { "" }

        val driveQuery = "($joinedParents) and mimeType = 'application/vnd.google-apps.folder'" +
            "$nameFilter and trashed = false"

        if (page == 1) {
            browsePageTokens.clear()
            lastBrowseQuery = driveQuery
        }

        val token = browsePageTokens[page]
        return GET(buildApiUrl(driveQuery, pageToken = token), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ─── Anime Details ───────────────────────────────────────────────────────

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$apiUrl/${anime.url}?fields=id,name&key=$apiKey", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val body = response.body?.string() ?: return SAnime.create()
        val json = JSONObject(body)
        val animeId = json.getString("id")

        return SAnime.create().apply {
            title = json.getString("name")
            thumbnail_url = getCoverUrlForAnime(animeId)
            description = "Streaming langsung dari Google Drive menggunakan API."
            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    // ─── Episode List ────────────────────────────────────────────────────────

    private var currentEpisodeQuery = ""

    override fun episodeListRequest(anime: SAnime): Request {
        val rawQuery = "'${anime.url}' in parents and (mimeType contains 'video/' or fileExtension = 'mkv' or fileExtension = 'mp4' or fileExtension = 'avi') and trashed = false"
        currentEpisodeQuery = "q=${URLEncoder.encode(rawQuery, "UTF-8")}&orderBy=name"
        return GET(buildApiUrl(rawQuery, pageSize = PAGE_SIZE_LARGE, fields = episodeFields), headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val body = response.body?.string() ?: return emptyList()
        val json = JSONObject(body)

        val allFiles = mutableListOf<JSONObject>()
        val firstBatch = json.optJSONArray("files") ?: return emptyList()
        for (i in 0 until firstBatch.length()) allFiles.add(firstBatch.getJSONObject(i))

        // Mengambil seluruh episode yang dipaginasi oleh API Drive
        var pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        while (pageToken != null) {
            val nextUrl = "$apiUrl?${currentEpisodeQuery}&pageSize=$PAGE_SIZE_LARGE&fields=$episodeFields&key=$apiKey&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}"
            val nextBody = client.newCall(GET(nextUrl, headers)).execute()
                .body?.string() ?: break
            val nextJson = JSONObject(nextBody)
            val nextFiles = nextJson.optJSONArray("files") ?: break
            for (i in 0 until nextFiles.length()) allFiles.add(nextFiles.getJSONObject(i))
            pageToken = nextJson.optString("nextPageToken").takeIf { it.isNotEmpty() }
        }

        val comparator = naturalSortComparator()
        allFiles.sortWith { a, b -> comparator.compare(a.getString("name"), b.getString("name")) }

        return allFiles.mapIndexed { i, file ->
            SEpisode.create().apply {
                name = file.getString("name")
                url = file.getString("id") // File ID digunakan sebagai identifier video
                episode_number = (i + 1).toFloat()
                date_upload = parseDate(file.optString("modifiedTime"))
            }
        }.reversed() // Aniyomi menampilkan episode terbaru (tertinggi) di urutan teratas
    }

    // ─── Video List (Standar Aniyomi) ────────────────────────────────────────

    override fun videoListRequest(episode: SEpisode): Request {
        // Request sekadar untuk memicu parse (API Drive)
        return GET("$apiUrl/${episode.url}?fields=id,name&key=$apiKey", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body?.string() ?: return emptyList()
        val json = JSONObject(body)
        val fileId = json.getString("id")
        val fileName = json.optString("name", "Unknown Quality")
        
        // Membangun stream URL menggunakan parameter alt=media
        val videoUrl = "$apiUrl/$fileId?alt=media&key=$apiKey"
        
        // Konstruktor standar kelas Video di Aniyomi
        return listOf(
            Video(
                url = videoUrl, // URL Referensi
                quality = "Direct Stream: $fileName", // Penamaan Kualitas
                videoUrl = videoUrl, // URL Stream Asli untuk ExoPlayer
                headers = Headers.Builder()
                    .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
            )
        )
    }

    // ─── Unused ──────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")

    // ─── Constants ───────────────────────────────────────────────────────────

    companion object {
        private const val API_KEY_PREF = "API_KEY_PREF"
        private const val PATH_LIST_PREF = "PATH_LIST_PREF"
        private const val PAGE_SIZE_LARGE = 1000
        private const val PAGE_SIZE_BROWSE = 50
    }
}

