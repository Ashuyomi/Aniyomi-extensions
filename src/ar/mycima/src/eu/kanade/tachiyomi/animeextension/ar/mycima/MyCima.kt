package eu.kanade.tachiyomi.animeextension.ar.mycima

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class MyCima : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "MY Cima"

    override val baseUrl = "https://weciima.autos"

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== popular ==============================

    override fun popularAnimeSelector(): String = "div.Grid--WecimaPosts div.GridItem div.Thumb--GridItem"

    override fun popularAnimeNextPageSelector(): String = "ul.page-numbers li a.next"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/seriestv/top/?page_number=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a").attr("title")
        anime.thumbnail_url =
            element.select("a > span.BG--GridItem")
                .attr("data-lazy-style")
                .substringAfter("-image:url(")
                .substringBefore(");")
        return anime
    }

    // ============================== episodes ==============================

    override fun episodeListSelector() = "div.Episodes--Seasons--Episodes a"

    private fun seasonsNextPageSelector(seasonNumber: Int) = "div.List--Seasons--Episodes > a:nth-child($seasonNumber)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        var seasonNumber = 1
        fun addEpisodes(document: Document) {
            if (document.select(episodeListSelector()).isNullOrEmpty()) {
                if (!document.select("mycima singlerelated.hasdivider ${popularAnimeSelector()}").isNullOrEmpty()) {
                    document.select("mycima singlerelated.hasdivider ${popularAnimeSelector()}").map { episodes.add(newEpisodeFromElement(it, "mSeries")) }
                } else
                    episodes.add(newEpisodeFromElement(document.select("div.Poster--Single-begin > a").first(), "movie"))
            } else {
                document.select(episodeListSelector()).map { episodes.add(newEpisodeFromElement(it)) }
                document.select(seasonsNextPageSelector(seasonNumber)).firstOrNull()?.let {
                    seasonNumber++
                    addEpisodes(
                        client.newCall(GET(it.attr("abs:href"), headers)).execute().asJsoup()
                    )
                }
            }
        }
        addEpisodes(response.asJsoup())
        return episodes
    }

    private fun newEpisodeFromElement(element: Element, type: String = "series"): SEpisode {
        val episode = SEpisode.create()
        val epNum = getNumberFromEpsString(element.text())
        episode.setUrlWithoutDomain(if (type == "mSeries") element.select("a").attr("href") else element.attr("abs:href"))
        if (type == "series")
            episode.episode_number = when {
                (epNum.isNotEmpty()) -> epNum.toFloat()
                else -> 1F
            }
        episode.name = when (type) {
            "movie" -> "????????????"
            "mSeries" -> element.select("a").attr("title")
            else -> element.ownerDocument().select("div.List--Seasons--Episodes a.selected").text() + element.text()
        }
        return episode
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // ============================== video urls ==============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.selectFirst("iframe").attr("data-lazy-src")
        val referer = response.request.url.encodedPath
        val newHeaderList = mutableMapOf(Pair("referer", baseUrl + referer))
        headers.forEach { newHeaderList[it.first] = it.second }
        val iframeResponse = client.newCall(GET(iframe, newHeaderList.toHeaders()))
            .execute().asJsoup()
        return videosFromElement(iframeResponse.selectFirst(videoListSelector()))
    }

    override fun videoListSelector() = "body"

    private fun videosFromElement(element: Element): List<Video> {
        val videoList = mutableListOf<Video>()
        val script = element.select("script")
            .firstOrNull { it.data().contains("player.qualityselector({") }
        if (script != null) {
            val scriptV = element.select("script:containsData(source)")
            val data = element.data().substringAfter("sources: [").substringBefore("],")
            val sources = data.split("format: '").drop(1)
            for (source in sources) {
                val src = source.substringAfter("src: \"").substringBefore("\"")
                val quality = source.substringBefore("'") // .substringAfter("format: '")
                val video = Video(src, quality, src)
                videoList.add(video)
            }
            return videoList
        }
        val sourceTag = element.ownerDocument().select("source").firstOrNull()!!
        return listOf(Video(sourceTag.attr("src"), "Default", sourceTag.attr("src")))
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // ============================== search ==============================

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a > strong").text()
        anime.thumbnail_url = element.select("a > span.BG--GridItem").attr("data-lazy-style").substringAfter("-image:url(").substringBefore(");")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.page-numbers li a.next"

    override fun searchAnimeSelector(): String = "div.Grid--WecimaPosts div.GridItem div.Thumb--GridItem"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is SearchCategoryList -> {
                        val catQ = getSearchCategoryList()[filter.state].query
                        val catUrl = "$baseUrl/search/$query/$catQ$page"
                        return GET(catUrl, headers)
                    }
                    else -> {}
                }
            }
        } else {
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is CategoryList -> {
                        if (filter.state > 0) {
                            val catQ = getCategoryList()[filter.state].query
                            val catUrl = "$baseUrl/category/$catQ/page/$page"
                            return GET(catUrl, headers)
                        }
                    }
                    else -> {}
                }
            }
            throw Exception("Choose a Filters")
        }
        return GET(baseUrl, headers)
    }

    // ============================== details ==============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.Title--Content--Single-begin > h1").text()
        anime.genre = document.select("li:contains(??????????????) > p > a, li:contains(??????????) > p > a").joinToString(", ") { it.text() }
        anime.description = document.select("div.AsideContext > div.StoryMovieContent, div.PostItemContent").text()
        anime.author = document.select("li:contains(?????????? ??????????????) > p > a").joinToString(", ") { it.text() }
        // add alternative name to anime description
        document.select("li:contains( ??????????????) > p, li:contains(??????????) > p").text()?.let {
            if (it.isEmpty().not()) {
                anime.description += when {
                    anime.description!!.isEmpty() -> "Alternative Name: $it"
                    else -> "\n\nAlternativ Name: $it"
                }
            }
        }
        return anime
    }

    // ============================== latest ==============================

    override fun latestUpdatesSelector(): String = "div.Grid--WecimaPosts div.GridItem div.Thumb--GridItem"

    override fun latestUpdatesNextPageSelector(): String = "ul.page-numbers li a.next"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a > strong").text()
        anime.thumbnail_url = element.select("a > span").attr("data-lazy-style").substringAfter("-image:url(").substringBefore(");")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page")

    // ============================== filters ==============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("???????????? ??????????"),
        SearchCategoryList(searchCategoryNames),
        AnimeFilter.Separator(),
        AnimeFilter.Header("?????????? ???????????? (???????? ?????? ?????? ?????? ?????????? ????????)"),
        CategoryList(categoryNames)
    )

    private class SearchCategoryList(categories: Array<String>) : AnimeFilter.Select<String>("?????? ????", categories)
    private class CategoryList(categories: Array<String>) : AnimeFilter.Select<String>("???????? ??????", categories)
    private data class CatUnit(val name: String, val query: String)
    private val searchCategoryNames = getSearchCategoryList().map {
        it.name
    }.toTypedArray()
    private val categoryNames = getCategoryList().map {
        it.name
    }.toTypedArray()

    private fun getSearchCategoryList() = listOf(
        CatUnit("????????", "/page/"),
        CatUnit("??????????", "list/series/?page_number="),
        CatUnit("????????", "list/anime/?page_number="),
        CatUnit("????????????", "list/tv/?page_number=")
    )
    private fun getCategoryList() = listOf(
        CatUnit("????????", ""),
        CatUnit("???????? ??????????????", "??????????"),
        CatUnit("?????????? ??????????", "??????????/10-movies-english-??????????-??????????/"),
        CatUnit("?????????? ????????", "??????????/6-arabic-movies-??????????-????????/"),
        CatUnit("?????????? ????????", "??????????/??????????-????????-indian-movies/"),
        CatUnit("?????????? ????????", "??????????/??????????-????????-turkish-films/"),
        CatUnit("?????????? ??????????????", "??????????/??????????-??????????????-documentary-films/"),
        CatUnit("?????????? ????????", "??????????-??????????/"),
        CatUnit("?????????? ??????????", "??????????/10-movies-english-??????????-??????????/??????????-??????????????-??????????????-full-pack/"),
        CatUnit("??????????????", "??????????????"),
        CatUnit("?????????????? ??????????", "??????????????/5-series-english-??????????????-??????????/"),
        CatUnit("?????????????? ????????", "??????????????/13-??????????????-??????????-arabic-series/"),
        CatUnit("?????????????? ????????", "??????????????/9-series-indian-??????????????-??????????/"),
        CatUnit("?????????????? ??????????", "??????????????/??????????????-????????????/"),
        CatUnit("?????????????? ????????", "??????????????/8-??????????????-??????????-turkish-series/"),
        CatUnit("?????????????? ??????????????", "??????????????/??????????????-??????????????-documentary-series/"),
        CatUnit("?????????????? ????????", "??????????????-??????????/"),
    )

    // preferred quality settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
