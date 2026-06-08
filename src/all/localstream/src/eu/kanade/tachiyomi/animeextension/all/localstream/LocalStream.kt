package eu.kanade.tachiyomi.animeextension.all.localstream

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
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
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

class LocalStream : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "LocalStream"
    override val lang = "all"
    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Pastikan base URL selalu BERSIH tanpa trailing slash ganda
    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF, "http://127.0.0.1:8080")?.trimEnd('/') ?: "http://127.0.0.1:8080"

    private val defaultCoverName: String
        get() = preferences.getString(COVER_NAME_PREF, "cover.jpg") ?: "cover.jpg"

    // ─── Dynamic Cover Fallback Interceptor ──────────────────────────────────
    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            var response = chain.proceed(originalRequest)
            
            val urlString = originalRequest.url.toString()
            val coverBase = defaultCoverName.substringBeforeLast(".").trim()
            
            if (response.code == 404 && urlString.contains("/$coverBase.", ignoreCase = true)) {
                val extensions = listOf("jpg", "jpeg", "png", "webp", "avif")
                val currentExt = urlString.substringAfterLast(".").lowercase(Locale.ROOT)
                
                for (ext in extensions) {
                    if (ext == currentExt) continue
                    response.close()
                    
                    val newUrl = urlString.substringBeforeLast(".") + "." + ext
                    val newRequest = originalRequest.newBuilder().url(newUrl).build()
                    response = chain.proceed(newRequest)
                    
                    if (response.isSuccessful) {
                        break
                    }
                }
            }
            response
        }
        .build()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "Server URL (Round Sync / Termux)"
            summary = "Masukkan URL lokal dari Rclone Serve."
            setDefaultValue("http://127.0.0.1:8080")
        }
        screen.addPreference(baseUrlPref)

        val coverNamePref = EditTextPreference(screen.context).apply {
            key = COVER_NAME_PREF
            title = "Nama File Cover Default"
            summary = "Nama dasar cover (Contoh: cover.jpg). Jika format berbeda (.png/.webp), sistem otomatis mendeteksinya."
            setDefaultValue("cover.jpg")
        }
        screen.addPreference(coverNamePref)
    }

    // ─── Helpers: URL Sanitizer ──────────────────────────────────────────────

    private fun getSafeAbsoluteUrl(element: Element): String {
        val rawHref = element.attr("href")
        if (rawHref.isBlank()) return ""

        val decodedHref = Uri.decode(rawHref)
        val safeHref = Uri.encode(decodedHref, "/:")

        element.attr("href", safeHref)
        val absUrl = element.absUrl("href")
        
        return if (absUrl.isNotBlank()) absUrl else {
            val sep = if (baseUrl.endsWith("/") || safeHref.startsWith("/")) "" else "/"
            "$baseUrl$sep$safeHref"
        }
    }

    // ─── Hierarchy Validation (Pencegah Breadcrumb & Folder Kosong) ──────────

    private fun getValidChildElements(
        document: Document, 
        requireText: Boolean = true, 
        condition: ((String) -> Boolean)? = null
    ): List<Element> {
        val baseUri = document.baseUri().substringBefore("#").substringBefore("?")
        val decodedBaseUri = Uri.decode(baseUri)
        val parentPath = if (decodedBaseUri.endsWith("/")) decodedBaseUri else "$decodedBaseUri/"

        return document.select("a[href]").filter { element ->
            if (requireText && element.text().trim().isEmpty()) return@filter false
            
            val absUrl = getSafeAbsoluteUrl(element)
            if (absUrl.isBlank()) return@filter false
            
            val cleanAbsUrl = absUrl.substringBefore("#").substringBefore("?")
            val decodedAbsUrl = Uri.decode(cleanAbsUrl)
            
            val isChild = decodedAbsUrl.startsWith(parentPath, ignoreCase = true) && decodedAbsUrl.length > parentPath.length
            if (!isChild) return@filter false
            
            condition?.invoke(decodedAbsUrl.lowercase(Locale.ROOT)) ?: true
        }
    }

    // ─── Helpers: Natural Sort ───────────────────────────────────────────────

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

    // ─── Helpers: ComicInfo Metadata Parser ──────────────────────────────────

    private fun getComicInfoXml(document: Document): String {
        val comicInfoLink = getValidChildElements(document, requireText = false) { 
            it.endsWith("comicinfo.xml") 
        }.firstOrNull()
        
        if (comicInfoLink != null) {
            return downloadUrlContent(getSafeAbsoluteUrl(comicInfoLink))
        }

        val subFolder = getValidChildElements(document) { it.endsWith("/") }.firstOrNull()
        if (subFolder != null) {
            try {
                val fixedSubUrl = getSafeAbsoluteUrl(subFolder)
                val subDoc = client.newCall(GET(fixedSubUrl, headers)).execute().asJsoup()
                val subComicInfoLink = getValidChildElements(subDoc, requireText = false) { 
                    it.endsWith("comicinfo.xml") 
                }.firstOrNull()
                
                if (subComicInfoLink != null) {
                    return downloadUrlContent(getSafeAbsoluteUrl(subComicInfoLink))
                }
            } catch (e: Exception) { }
        }
        return ""
    }

    private fun downloadUrlContent(url: String): String {
        return try {
            client.newCall(GET(url, headers)).execute().body?.string() ?: ""
        } catch (e: Exception) { "" }
    }

    private fun extractXmlTag(xml: String, tag: String): String? {
        val regex = Regex(
            "<(?:\\w+:)?$tag(?:\\s+[^>]*)?>(.*?)</(?:\\w+:)?$tag>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return regex.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parseComicInfo(xml: String, anime: SAnime) {
        val series = extractXmlTag(xml, "Series")
        val summary = extractXmlTag(xml, "Summary")
            ?.replace("&amp;", "&")
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.replace("&quot;", "\"")
            ?.replace("&apos;", "'")

        val writer = extractXmlTag(xml, "Writer")
        val penciller = extractXmlTag(xml, "Penciller")
        val publisher = extractXmlTag(xml, "Publisher")
        val statusStr = extractXmlTag(xml, "PublishingStatusTachiyomi") ?: extractXmlTag(xml, "Status")
        val genre = extractXmlTag(xml, "Genre")
        val categories = extractXmlTag(xml, "Categories")

        if (!series.isNullOrBlank()) {
            anime.title = series
        }
        if (!summary.isNullOrBlank()) {
            anime.description = summary
        }

        val authorsList = listOfNotNull(writer, penciller, publisher).distinct()
        anime.author = authorsList.joinToString(" | ").takeIf { it.isNotBlank() } ?: "Unknown"
        anime.artist = penciller ?: writer ?: "Unknown"

        val allGenres = listOfNotNull(genre, categories)
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (allGenres.isNotEmpty()) {
            anime.genre = allGenres.joinToString(", ")
        }

        anime.status = when (statusStr?.lowercase(Locale.ROOT)) {
            "ongoing", "1" -> SAnime.ONGOING
            "completed", "end", "2" -> SAnime.COMPLETED
            "cancelled", "abandoned", "6" -> SAnime.CANCELLED
            "hiatus" -> SAnime.ON_HIATUS
            else -> SAnime.UNKNOWN
        }
    }

    // ─── Browse / Popular Anime ──────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/#page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val fragment = response.request.url.fragment ?: ""
        val currentPage = fragment.substringAfter("page=").toIntOrNull() ?: 1
        val itemsPerPage = 24

        val document = response.asJsoup()
        val allElements = getValidChildElements(document) { it.endsWith("/") }

        val startIndex = (currentPage - 1) * itemsPerPage
        val endIndex = minOf(startIndex + itemsPerPage, allElements.size)

        if (startIndex >= allElements.size) {
            return AnimesPage(emptyList(), false)
        }

        val pagedElements = allElements.subList(startIndex, endIndex)
        val animes = pagedElements.map { element ->
            SAnime.create().apply {
                title = element.text().trim().removeSuffix("/")
                
                val fixedAbsUrl = getSafeAbsoluteUrl(element)
                val relPath = fixedAbsUrl.removePrefix(baseUrl)
                url = if (relPath.startsWith("/")) relPath else "/$relPath"
                
                val cleanAbsUrl = fixedAbsUrl.removeSuffix("/")
                thumbnail_url = "$cleanAbsUrl/${defaultCoverName.trim()}"
            }
        }

        val hasNextPage = endIndex < allElements.size
        return AnimesPage(animes, hasNextPage)
    }

    // ─── Search Anime ────────────────────────────────────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/#query=${query.lowercase(Locale.ROOT)}&page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val fragment = response.request.url.fragment ?: ""
        val query = fragment.substringAfter("query=").substringBefore("&page=").lowercase(Locale.ROOT)
        val currentPage = fragment.substringAfter("page=").toIntOrNull() ?: 1
        val itemsPerPage = 24

        val document = response.asJsoup()
        val filteredElements = getValidChildElements(document) { it.endsWith("/") }
            .filter { it.text().lowercase(Locale.ROOT).contains(query) }

        val startIndex = (currentPage - 1) * itemsPerPage
        val endIndex = minOf(startIndex + itemsPerPage, filteredElements.size)

        if (startIndex >= filteredElements.size) {
            return AnimesPage(emptyList(), false)
        }

        val pagedElements = filteredElements.subList(startIndex, endIndex)
        val animes = pagedElements.map { element ->
            SAnime.create().apply {
                title = element.text().trim().removeSuffix("/")
                
                val fixedAbsUrl = getSafeAbsoluteUrl(element)
                val relPath = fixedAbsUrl.removePrefix(baseUrl)
                url = if (relPath.startsWith("/")) relPath else "/$relPath"
                
                val cleanAbsUrl = fixedAbsUrl.removeSuffix("/")
                thumbnail_url = "$cleanAbsUrl/${defaultCoverName.trim()}"
            }
        }

        val hasNextPage = endIndex < filteredElements.size
        return AnimesPage(animes, hasNextPage)
    }

    // ─── Anime Details ───────────────────────────────────────────────────────

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val imageLinks = getValidChildElements(document, requireText = false) { href ->
            listOf(".jpg", ".jpeg", ".png", ".webp", ".avif").any { href.endsWith(it) }
        }

        val coverElement = imageLinks.find { 
            val href = it.attr("href").lowercase(Locale.ROOT)
            href.contains("cover") || href.contains("poster") || href.contains("thumb")
        } ?: imageLinks.firstOrNull()

        val anime = SAnime.create().apply {
            description = "Folder anime lokal dari Server LocalStream (WebDAV/HTTP)."
            status = SAnime.UNKNOWN
            if (coverElement != null) {
                thumbnail_url = getSafeAbsoluteUrl(coverElement)
            }
            initialized = true
        }

        val comicInfoXml = getComicInfoXml(document)
        if (comicInfoXml.isNotBlank()) {
            parseComicInfo(comicInfoXml, anime)
        }

        return anime
    }

    // ─── Episode List ────────────────────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        
        val episodeElements = getValidChildElements(document) { href ->
            val isVideo = listOf(".mp4", ".mkv", ".webm", ".avi").any { href.endsWith(it) }
            href.endsWith("/") || isVideo
        }

        val comparator = naturalSortComparator()
        val sortedElements = episodeElements.sortedWith { a, b -> 
            comparator.compare(a.text(), b.text()) 
        }

        return sortedElements.mapIndexed { index, element ->
            SEpisode.create().apply {
                name = element.text().trim().removeSuffix("/")
                
                val fixedAbsUrl = getSafeAbsoluteUrl(element)
                val relPath = fixedAbsUrl.removePrefix(baseUrl)
                url = if (relPath.startsWith("/")) relPath else "/$relPath"
                
                episode_number = (index + 1).toFloat()
            }
        }.reversed()
    }

    // ─── Video List ──────────────────────────────────────────────────────────

    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val rawUrl = response.request.url.toString()
        val decodedUrl = Uri.decode(rawUrl)
        val fixedUrl = Uri.encode(decodedUrl, "/:")
        
        val isDirectVideo = decodedUrl.lowercase(Locale.ROOT).run {
            endsWith(".mp4") || endsWith(".mkv") || endsWith(".webm") || endsWith(".avi")
        }
        
        if (isDirectVideo) {
            return listOf(Video(fixedUrl, "Original Quality (Lokal)", fixedUrl))
        }

        val document = response.asJsoup()
        val videoElements = getValidChildElements(document) { href ->
            listOf(".mp4", ".mkv", ".webm", ".avi").any { href.endsWith(it) }
        }

        val comparator = naturalSortComparator()
        val sortedVideos = videoElements.sortedWith { a, b -> 
            comparator.compare(a.text(), b.text()) 
        }

        return sortedVideos.map { element ->
            val validUrl = getSafeAbsoluteUrl(element)
            Video(validUrl, element.text().trim(), validUrl)
        }
    }

    // ─── Unused Abstract Methods ─────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")
    
    companion object {
        private const val BASE_URL_PREF = "BASE_URL_PREF_LOCALSTREAM_ANIME"
        private const val COVER_NAME_PREF = "COVER_NAME_PREF_LOCALSTREAM_ANIME"
    }
}
