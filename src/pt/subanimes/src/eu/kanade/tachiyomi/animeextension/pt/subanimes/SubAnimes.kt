package eu.kanade.tachiyomi.animeextension.pt.subanimes

import eu.kanade.tachiyomi.animeextension.pt.subanimes.dto.AnimeDataDto
import eu.kanade.tachiyomi.animeextension.pt.subanimes.dto.SearchResultDto
import eu.kanade.tachiyomi.animeextension.pt.subanimes.extractors.SubAnimesExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit
import kotlin.Exception

class SubAnimes : ParsedAnimeHttpSource() {

    override val name = "SubAnimes"

    override val baseUrl = "https://subanimes.cc"
    private val API_URL = "$baseUrl/wp-admin/admin-ajax.php"

    override val lang = "pt-BR"

    override val supportsLatest = true

    // Sometimes the site is slow.
    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(1, TimeUnit.MINUTES)
        .readTimeout(1, TimeUnit.MINUTES)
        .writeTimeout(1, TimeUnit.MINUTES)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div#hype div.aniItem > a"
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)
    override fun popularAnimeNextPageSelector() = null // disable it
    override fun popularAnimeFromElement(element: Element): SAnime =
        latestUpdatesFromElement(element)

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div#episodios div.animeVideosItem > a"
    private fun episodeListNextPageSelector() = latestUpdatesNextPageSelector()

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        return client.newCall(episodeListRequest(anime))
            .asObservableSuccess()
            .map { response ->
                val realDoc = getRealDoc(response.asJsoup())
                episodeListParse(realDoc).reversed()
            }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return episodeListParse(response.asJsoup())
    }

    private fun episodeListParse(doc: Document): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        val eps = doc.select(episodeListSelector()).map(::episodeFromElement)
        episodeList.addAll(eps)
        val nextPageElement = doc.selectFirst(episodeListNextPageSelector())
        if (nextPageElement != null) {
            val nextUrl = nextPageElement.attr("href")
            val res = client.newCall(GET(nextUrl)).execute()
            episodeList.addAll(episodeListParse(res))
        }
        return episodeList
    }

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            val title = element.attr("title")
            name = title
            episode_number = runCatching {
                title.trim().substringAfterLast(" ").toFloat()
            }.getOrDefault(0F)
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()

        val players = doc.select("section.playerTabs > div.playerTab").map {
            val url = it.attr("data-player-url")
            Pair(it.text(), url)
        }.ifEmpty {
            val defaultPlayer = doc.selectFirst("div.playerBoxInfra > iframe")
            listOf(Pair("Default", defaultPlayer.attr("src")))
        }

        val videos = players.flatMap { (playerName, url) ->
            SubAnimesExtractor(client).videoListFromUrl(url, playerName, headers)
        }
        return videos.reversed()
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)
    override fun searchAnimeSelector() = "div.aniItem > a"
    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/page/$page/?s=$query")

    override fun getFilterList(): AnimeFilterList = SBFilters.filterList

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val slug = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$slug", headers))
                .asObservableSuccess()
                .map { searchAnimeBySlugParse(it, slug) }
        } else {
            val params = SBFilters.getSearchParameters(filters)

            if (params == SBFilters.FilterSearchParams()) // no filters
                return super.fetchSearchAnime(page, query, filters)

            client.newCall(searchAnimeRequest(page, query, params))
                .asObservableSuccess()
                .map { searchAnimeParse(it, page) }
        }
    }

    private fun searchAnimeBySlugParse(response: Response, slug: String): AnimesPage {
        val details = animeDetailsParse(response)
        details.url = "/anime/$slug"
        return AnimesPage(listOf(details), false)
    }

    private fun searchAnimeRequest(page: Int, query: String, filters: SBFilters.FilterSearchParams): Request {
        val body = FormBody.Builder().apply {
            add("action", "anime_search")
            add("posts_per_page", "12")
            if (filters.adult)
                add("age", "yes")
            else
                add("age", "no")
            add("format", filters.format)
            add("name", query)
            add("paged", "$page")
            add("status", filters.status)
            add("audio_type", filters.type)
            filters.genres.forEach { add("genres[]", it) }
        }.build()

        return POST(API_URL, body = body)
    }

    private fun searchAnimeParse(response: Response, page: Int): AnimesPage {
        val searchData = runCatching {
            response.parseAs<SearchResultDto>()
        }.getOrDefault(SearchResultDto())

        return if (searchData.errors != 0) {
            AnimesPage(emptyList<SAnime>(), false)
        } else {
            val animes = searchData.animes.map(::searchAnimeParseFromObject)
            val hasNextPage = searchData.pages > page
            AnimesPage(animes, hasNextPage)
        }
    }

    private fun searchAnimeParseFromObject(anime: AnimeDataDto): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(anime.info.url)
            title = anime.info.title
            thumbnail_url = anime.thumbnail.url
        }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealDoc(document)
        return SAnime.create().apply {
            val div = doc.selectFirst("div.leftAnime")
            thumbnail_url = div.selectFirst("img").attr("src")
            title = doc.selectFirst("section.page_title").text()
            status = parseStatus(div.selectFirst("div.anime_status"))

            val container = doc.selectFirst("div.sinopse_container")
            genre = container.select("div.genders_container > span")
                .joinToString(", ") { it.text() }

            var desc = container.selectFirst("div.sinopse_content").text()
            desc += "\n\n" + div.select("div.animeInfosItemSingle").joinToString("\n") {
                val key = it.selectFirst("b").text()
                val value = it.selectFirst("span").text()
                "$key: $value"
            }
            description = desc
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = "div.paginacao > a.next"
    override fun latestUpdatesSelector() = "div.epiItem > div.epiImg > a"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.attr("title")
            thumbnail_url = element.selectFirst("img").attr("src")
        }
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/lista-de-episodios/page/$page/")

    // ============================= Utilities ==============================

    private fun getRealDoc(doc: Document): Document {
        val controls = doc.selectFirst("div.episodioControles")
        if (controls != null) {
            val newUrl = controls.select("a").get(1)!!.attr("href")
            val res = client.newCall(GET(newUrl)).execute()
            return res.asJsoup()
        } else {
            return doc
        }
    }

    private fun parseStatus(element: Element?): Int {
        return when (element?.text()?.trim()) {
            "Em Lan??amento" -> SAnime.ONGOING
            else -> SAnime.COMPLETED
        }
    }

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = body?.string().orEmpty()
        return json.decodeFromString(responseBody)
    }

    companion object {
        const val PREFIX_SEARCH = "slug:"
    }
}
