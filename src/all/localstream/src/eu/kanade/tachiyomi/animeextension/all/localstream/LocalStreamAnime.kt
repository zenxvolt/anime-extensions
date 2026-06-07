package eu.kanade.tachiyomi.animeextension.all.localstream

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LocalStreamAnime : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "LocalStream Anime"
    override val lang = "all"
    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF, "http://127.0.0.1:8080")?.removeSuffix("/") ?: "http://127.0.0.1:8080"

    private val defaultCoverName: String
        get() = preferences.getString(COVER_NAME_PREF, "cover.jpg") ?: "cover.jpg"

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
            summary = "Harus SAMA PERSIS dengan nama file di dalam folder (case-sensitive)."
            setDefaultValue("cover.jpg")
        }
        screen.addPreference(coverNamePref)
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

    // ─── Browse / Popular Anime ──────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/#page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val fragment = response.request.url.fragment ?: ""
        val currentPage = fragment.substringAfter("page=").toIntOrNull() ?: 1
        val itemsPerPage = 24

        val document = response.asJsoup()
        val allElements = document.select("a[href]")
            .filter { !it.attr("href").contains("..") && it.attr("href").endsWith("/") }

        val startIndex = (currentPage - 1) * itemsPerPage
        val endIndex = minOf(startIndex + itemsPerPage, allElements.size)

        if (startIndex >= allElements.size) {
            return AnimesPage(emptyList(), false)
        }

        val pagedElements = allElements.subList(startIndex, endIndex)
        val animes = pagedElements.map { element ->
            SAnime.create().apply {
                title = element.text().removeSuffix("/")
                val absUrl = element.absUrl("href")
                val fixedAbsUrl = absUrl.replace("+", "%2B").replace(" ", "%20")
                
                var relUrl = fixedAbsUrl.removePrefix(baseUrl)
                if (!relUrl.startsWith("/")) relUrl = "/$relUrl"
                url = relUrl
                
                val cleanAbsUrl = fixedAbsUrl.removeSuffix("/")
                thumbnail_url = "$cleanAbsUrl/${defaultCoverName.trim()}"
            }
        }

        val hasNextPage = endIndex < allElements.size
        return AnimesPage(animes, hasNextPage)
    }

    // ─── Search Anime ────────────────────────────────────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/#query=${query.lowercase()}&page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val fragment = response.request.url.fragment ?: ""
        val query = fragment.substringAfter("query=").substringBefore("&page=").lowercase()
        val currentPage = fragment.substringAfter("page=").toIntOrNull() ?: 1
        val itemsPerPage = 24

        val document = response.asJsoup()
        val filteredElements = document.select("a[href]")
            .filter { !it.attr("href").contains("..") && it.attr("href").endsWith("/") }
            .filter { it.text().lowercase().contains(query) }

        val startIndex = (currentPage - 1) * itemsPerPage
        val endIndex = minOf(startIndex + itemsPerPage, filteredElements.size)

        if (startIndex >= filteredElements.size) {
            return AnimesPage(emptyList(), false)
        }

        val pagedElements = filteredElements.subList(startIndex, endIndex)
        val animes = pagedElements.map { element ->
            SAnime.create().apply {
                title = element.text().removeSuffix("/")
                val absUrl = element.absUrl("href")
                val fixedAbsUrl = absUrl.replace("+", "%2B").replace(" ", "%20")
                
                var relUrl = fixedAbsUrl.removePrefix(baseUrl)
                if (!relUrl.startsWith("/")) relUrl = "/$relUrl"
                url = relUrl
                
                val cleanAbsUrl = fixedAbsUrl.removeSuffix("/")
                thumbnail_url = "$cleanAbsUrl/${defaultCoverName.trim()}"
            }
        }

        val hasNextPage = endIndex < filteredElements.size
        return AnimesPage(animes, hasNextPage)
    }

    // ─── Anime Details ───────────────────────────────────────────────────────

    override fun animeDetailsParse(document: Document): SAnime {
        val links = document.select("a[href]")
        val imageLinks = links.filter { 
            val href = it.attr("href").lowercase()
            !href.contains("..") && (href.endsWith(".jpg") || href.endsWith(".jpeg") || 
                                     href.endsWith(".png") || href.endsWith(".webp"))
        }

        val coverElement = imageLinks.find { 
            val href = it.attr("href").lowercase()
            href.contains("cover") || href.contains("poster") || href.contains("thumb")
        } ?: imageLinks.firstOrNull()

        return SAnime.create().apply {
            description = "Folder anime lokal dari Server LocalStream (WebDAV/HTTP)."
            status = SAnime.UNKNOWN
            if (coverElement != null) {
                val absUrl = coverElement.absUrl("href")
                thumbnail_url = absUrl.replace("+", "%2B").replace(" ", "%20")
            }
            initialized = true
        }
    }

    // ─── Episode List ────────────────────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        
        val episodeElements = document.select("a[href]")
            .filter { element ->
                val href = element.attr("href").lowercase()
                val isVideo = href.endsWith(".mp4") || href.endsWith(".mkv") || 
                              href.endsWith(".webm") || href.endsWith(".avi")
                !href.contains("..") && (href.endsWith("/") || isVideo)
            }

        val comparator = naturalSortComparator()
        val sortedElements = episodeElements.sortedWith { a, b -> 
            comparator.compare(a.text(), b.text()) 
        }

        return sortedElements.mapIndexed { index, element ->
            SEpisode.create().apply {
                name = element.text().removeSuffix("/")
                val absUrl = element.absUrl("href")
                
                val fixedAbsUrl = absUrl.replace("+", "%2B").replace(" ", "%20")
                var relUrl = fixedAbsUrl.removePrefix(baseUrl)
                if (!relUrl.startsWith("/")) relUrl = "/$relUrl"
                url = relUrl
                
                episode_number = (index + 1).toFloat()
            }
        }.reversed()
    }

    // ─── Video List (Page List) ──────────────────────────────────────────────

    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        val fixedUrl = url.replace("+", "%2B").replace(" ", "%20")
        
        val isDirectVideo = url.lowercase().run {
            endsWith(".mp4") || endsWith(".mkv") || endsWith(".webm") || endsWith(".avi")
        }
        if (isDirectVideo) {
            return listOf(Video(fixedUrl, "Original Quality (Lokal)", fixedUrl))
        }

        val document = response.asJsoup()
        val videoElements = document.select("a[href]")
            .filter { element ->
                val href = element.attr("href").lowercase()
                val isVideo = href.endsWith(".mp4") || href.endsWith(".mkv") || 
                              href.endsWith(".webm") || href.endsWith(".avi")
                !href.contains("..") && isVideo
            }

        val comparator = naturalSortComparator()
        val sortedVideos = videoElements.sortedWith { a, b -> 
            comparator.compare(a.text(), b.text()) 
        }

        return sortedVideos.map { element ->
            val absUrl = element.absUrl("href")
            val validUrl = absUrl.replace("+", "%2B").replace(" ", "%20")
            Video(validUrl, element.text(), validUrl)
        }
    }

    // ─── Abstract Methods ────────────────────────────────────────────────────
    override fun popularAnimeSelector(): String = ""
    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create()
    override fun popularAnimeNextPageSelector(): String? = null
    override fun searchAnimeSelector(): String = ""
    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create()
    override fun searchAnimeNextPageSelector(): String? = null
    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create()
    override fun episodeListSelector(): String = ""
    override fun videoUrlParse(document: Document): String = ""
    override fun videoListParse(document: Document): List<Video> = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesSelector(): String = ""
    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create()
    override fun latestUpdatesNextPageSelector(): String? = null

    companion object {
        private const val BASE_URL_PREF = "BASE_URL_PREF_LOCALSTREAM_ANIME"
        private const val COVER_NAME_PREF = "COVER_NAME_PREF_LOCALSTREAM_ANIME"
    }
}
