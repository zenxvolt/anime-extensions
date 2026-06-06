package eu.kanade.tachiyomi.animeextension.all.localstream

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
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LocalStream : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    // Identitas Extension
    override val name = "LocalStream"
    override val lang = "all"
    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF, "http://127.0.0.1:8080")?.removeSuffix("/") ?: "http://127.0.0.1:8080"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "Server URL (Round Sync / Termux)"
            summary = "Masukkan URL lokal dari Rclone Serve."
            setDefaultValue("http://127.0.0.1:8080")
        }
        screen.addPreference(baseUrlPref)
    }

    // ─── Browse / Popular (Root Folder) ──────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("a[href]")
            // PERBAIKAN: Memblokir tautan mundur (..) dengan ketat
            .filter { !it.attr("href").contains("..") && it.attr("href").endsWith("/") }
            .map { element ->
                SAnime.create().apply {
                    title = element.text().removeSuffix("/")
                    val absUrl = element.absUrl("href")
                    var relUrl = absUrl.removePrefix(baseUrl)
                    if (!relUrl.startsWith("/")) relUrl = "/$relUrl"
                    url = relUrl
                    thumbnail_url = null
                }
            }
        return AnimesPage(animes, false)
    }

    // ─── Search ──────────────────────────────────────────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/#query=${query.lowercase()}", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val query = response.request.url.fragment?.lowercase() ?: ""
        
        val animes = document.select("a[href]")
            // PERBAIKAN: Memblokir tautan mundur (..) dengan ketat
            .filter { !it.attr("href").contains("..") && it.attr("href").endsWith("/") }
            .filter { it.text().lowercase().contains(query) }
            .map { element ->
                SAnime.create().apply {
                    title = element.text().removeSuffix("/")
                    val absUrl = element.absUrl("href")
                    var relUrl = absUrl.removePrefix(baseUrl)
                    if (!relUrl.startsWith("/")) relUrl = "/$relUrl"
                    url = relUrl
                }
            }
        return AnimesPage(animes, false)
    }

    // ─── Anime Details ───────────────────────────────────────────────────────

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            description = "Folder lokal dari Server LocalStream (WebDAV/HTTP)."
            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    // ─── Episode List (Video Files) ──────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select("a[href]")
            .filter { element ->
                val href = element.attr("href").lowercase()
                // PERBAIKAN: Memastikan file video tidak memuat navigasi mundur
                !href.contains("..") && (href.endsWith(".mp4") || href.endsWith(".mkv") || href.endsWith(".avi"))
            }
            .mapIndexed { index, element ->
                SEpisode.create().apply {
                    name = element.text()
                    val absUrl = element.absUrl("href")
                    var relUrl = absUrl.removePrefix(baseUrl)
                    if (!relUrl.startsWith("/")) relUrl = "/$relUrl"
                    url = relUrl
                    episode_number = (index + 1).toFloat()
                }
            }.reversed()
    }

    // ─── Video Stream ────────────────────────────────────────────────────────

    override fun fetchVideoList(episode: SEpisode): rx.Observable<List<Video>> {
        val videoUrl = baseUrl + episode.url
        val video = Video(videoUrl, "Direct Stream (Local)", videoUrl)
        return rx.Observable.just(listOf(video))
    }

    // ─── Abstract Methods (Wajib dioverride tapi tidak dipakai) ──────────────
    override fun popularAnimeSelector(): String = ""
    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create()
    override fun popularAnimeNextPageSelector(): String? = null
    override fun searchAnimeSelector(): String = ""
    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create()
    override fun searchAnimeNextPageSelector(): String? = null
    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create()
    override fun episodeListSelector(): String = ""
    override fun videoListParse(response: Response): List<Video> = emptyList()
    override fun videoListSelector(): String = ""
    override fun videoFromElement(element: Element): Video = Video("", "", "")
    override fun videoUrlParse(document: Document): String = ""
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesSelector(): String = ""
    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create()
    override fun latestUpdatesNextPageSelector(): String? = null

    companion object {
        // Menggunakan key khusus agar tidak bentrok dengan ekstensi lain
        private const val BASE_URL_PREF = "BASE_URL_PREF_LOCALSTREAM"
    }
}
