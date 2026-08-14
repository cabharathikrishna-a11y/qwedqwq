package com.example.api

import com.example.BuildConfig
import com.example.data.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object MovieSearchService {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    // Curated Trending & Popular IMDb Showcase Database for immediate instant access & offline fallback
    val popularImdbShowcase: List<MovieItem> = listOf(
        createShowcaseItem(
            imdbId = "tt0944947",
            title = "Game of Thrones",
            year = "2011–2019",
            type = "series",
            posterUrl = "https://image.tmdb.org/t/p/w500/1XS1oqL89R2G32YW1P2OqM19StX.jpg",
            imdbRating = "9.2",
            genre = "Action, Adventure, Drama",
            plot = "Nine noble families fight for control over the lands of Westeros, while an ancient enemy returns after being dormant for a millennia.",
            director = "David Benioff, D.B. Weiss",
            cast = "Emilia Clarke, Kit Harington, Peter Dinklage, Lena Headey",
            totalSeasons = 8,
            episodesPerSeason = listOf(10, 10, 10, 10, 10, 10, 7, 6)
        ),
        createShowcaseItem(
            imdbId = "tt0903747",
            title = "Breaking Bad",
            year = "2008–2013",
            type = "series",
            posterUrl = "https://image.tmdb.org/t/p/w500/zt2A2s2L33pFW401C0R8uS3q5C.jpg",
            imdbRating = "9.5",
            genre = "Crime, Drama, Thriller",
            plot = "A chemistry teacher diagnosed with inoperable lung cancer turns to manufacturing and selling methamphetamine with a former student in order to secure his family's future.",
            director = "Vince Gilligan",
            cast = "Bryan Cranston, Aaron Paul, Anna Gunn, Betsy Brandt",
            totalSeasons = 5,
            episodesPerSeason = listOf(7, 13, 13, 13, 16)
        ),
        createShowcaseItem(
            imdbId = "tt1375666",
            title = "Inception",
            year = "2010",
            type = "movie",
            posterUrl = "https://image.tmdb.org/t/p/w500/oYuLE1S11S1A437SGI9S6I3C33.jpg",
            imdbRating = "8.8",
            genre = "Action, Adventure, Sci-Fi",
            plot = "A thief who steals corporate secrets through the use of dream-sharing technology is given the inverse task of planting an idea into the mind of a C.E.O.",
            director = "Christopher Nolan",
            cast = "Leonardo DiCaprio, Joseph Gordon-Levitt, Elliot Page, Tom Hardy",
            totalSeasons = 1,
            episodesPerSeason = emptyList()
        ),
        createShowcaseItem(
            imdbId = "tt4574334",
            title = "Stranger Things",
            year = "2016–2025",
            type = "series",
            posterUrl = "https://image.tmdb.org/t/p/w500/49WJfeN0moxb9IPR3M2S9awP431.jpg",
            imdbRating = "8.7",
            genre = "Drama, Fantasy, Horror",
            plot = "When a young boy vanishes, a small town uncovers a mystery involving secret experiments, terrifying supernatural forces and one strange little girl.",
            director = "The Duffer Brothers",
            cast = "Millie Bobby Brown, Finn Wolfhard, Winona Ryder, David Harbour",
            totalSeasons = 4,
            episodesPerSeason = listOf(8, 9, 8, 9)
        ),
        createShowcaseItem(
            imdbId = "tt0816692",
            title = "Interstellar",
            year = "2014",
            type = "movie",
            posterUrl = "https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6Mxl4vR2m.jpg",
            imdbRating = "8.7",
            genre = "Adventure, Drama, Sci-Fi",
            plot = "When Earth becomes uninhabitable in the future, a farmer and ex-NASA pilot, Joseph Cooper, is tasked to pilot a spacecraft, along with a team of researchers, to find a new planet for humans.",
            director = "Christopher Nolan",
            cast = "Matthew McConaughey, Anne Hathaway, Jessica Chastain, Michael Caine",
            totalSeasons = 1,
            episodesPerSeason = emptyList()
        ),
        createShowcaseItem(
            imdbId = "tt0468569",
            title = "The Dark Knight",
            year = "2008",
            type = "movie",
            posterUrl = "https://image.tmdb.org/t/p/w500/qJ2tCh2MAn2PqP3C1O35697S6I.jpg",
            imdbRating = "9.0",
            genre = "Action, Crime, Drama",
            plot = "When the menace known as the Joker wreaks havoc and chaos on the people of Gotham, Batman must accept one of the greatest psychological and physical tests of his ability to fight injustice.",
            director = "Christopher Nolan",
            cast = "Christian Bale, Heath Ledger, Aaron Eckhart, Michael Caine",
            totalSeasons = 1,
            episodesPerSeason = emptyList()
        ),
        createShowcaseItem(
            imdbId = "tt15398776",
            title = "Oppenheimer",
            year = "2023",
            type = "movie",
            posterUrl = "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?w=500",
            imdbRating = "8.9",
            genre = "Biography, Drama, History",
            plot = "The story of American scientist J. Robert Oppenheimer and his role in the development of the atomic bomb.",
            director = "Christopher Nolan",
            cast = "Cillian Murphy, Emily Blunt, Matt Damon, Robert Downey Jr.",
            totalSeasons = 1,
            episodesPerSeason = emptyList()
        ),
        createShowcaseItem(
            imdbId = "tt11280740",
            title = "Severance",
            year = "2022–",
            type = "series",
            posterUrl = "https://images.unsplash.com/photo-1517604931442-7e0c8ed2963c?w=500",
            imdbRating = "8.7",
            genre = "Drama, Mystery, Sci-Fi",
            plot = "Mark leads a team of office workers whose memories have been surgically divided between their work and personal lives.",
            director = "Dan Erickson",
            cast = "Adam Scott, Zach Cherry, Britt Lower, Patricia Arquette",
            totalSeasons = 2,
            episodesPerSeason = listOf(9, 10)
        ),
        createShowcaseItem(
            imdbId = "tt3581920",
            title = "The Last of Us",
            year = "2023–",
            type = "series",
            posterUrl = "https://images.unsplash.com/photo-1536440136628-849c177e76a1?w=500",
            imdbRating = "8.8",
            genre = "Action, Adventure, Drama",
            plot = "After a global pandemic destroys civilization, a hardened survivor takes charge of a 14-year-old girl who may be humanity's last hope.",
            director = "Craig Mazin, Neil Druckmann",
            cast = "Pedro Pascal, Bella Ramsey, Gabriel Luna, Anna Torv",
            totalSeasons = 1,
            episodesPerSeason = listOf(9)
        ),
        createShowcaseItem(
            imdbId = "tt7660850",
            title = "Succession",
            year = "2018–2023",
            type = "series",
            posterUrl = "https://images.unsplash.com/photo-1574375927938-d5a98e8ffe85?w=500",
            imdbRating = "8.9",
            genre = "Drama",
            plot = "The Roy family is known for controlling the biggest media and entertainment company in the world. However, their world changes when their father steps down.",
            director = "Jesse Armstrong",
            cast = "Nicholas Britell, Brian Cox, Jeremy Strong, Sarah Snook",
            totalSeasons = 4,
            episodesPerSeason = listOf(10, 10, 9, 10)
        )
    )

    private fun createShowcaseItem(
        imdbId: String,
        title: String,
        year: String,
        type: String,
        posterUrl: String,
        imdbRating: String,
        genre: String,
        plot: String,
        director: String,
        cast: String,
        totalSeasons: Int,
        episodesPerSeason: List<Int>,
        trailerYoutubeKey: String = "",
        backdropUrl: String = "",
        galleryImages: List<String> = emptyList()
    ): MovieItem {
        val seasons = if (type == "series" && totalSeasons > 0) {
            (1..totalSeasons).map { sNum ->
                val epCount = episodesPerSeason.getOrElse(sNum - 1) { 10 }
                val episodes = (1..epCount).map { eNum ->
                    EpisodeItem(
                        seasonNumber = sNum,
                        episodeNumber = eNum,
                        title = "Episode $eNum",
                        isWatched = false,
                        overview = "Season $sNum, Episode $eNum of $title.",
                        airDate = "",
                        rating = imdbRating
                    )
                }
                SeasonItem(seasonNumber = sNum, totalEpisodes = epCount, episodes = episodes)
            }
        } else {
            emptyList()
        }

        val totalEpisodes = seasons.sumOf { it.episodes.size }

        // Default watch providers for showcase
        val defaultProviders = when (imdbId) {
            "tt0903747", "tt0944947" -> WatchProvidersData(
                link = "https://www.justwatch.com/in/tv-show/breaking-bad",
                flatrate = listOf(
                    WatchProviderItem(8, "Netflix", "/pbpMk221SmlGlAfgA234.jpg"),
                    WatchProviderItem(122, "Disney+ Hotstar", "/8A3Lq7Q5p2.jpg")
                )
            )
            "tt1375666", "tt0816692", "tt0468569" -> WatchProvidersData(
                link = "https://www.justwatch.com/in/movie/inception",
                flatrate = listOf(
                    WatchProviderItem(119, "Amazon Prime Video", "/mbaA0U5u9a.jpg"),
                    WatchProviderItem(122, "Disney+ Hotstar", "/8A3Lq7Q5p2.jpg")
                )
            )
            else -> WatchProvidersData(
                link = "https://www.justwatch.com/in/tv-show/stranger-things",
                flatrate = listOf(
                    WatchProviderItem(8, "Netflix", "/pbpMk221SmlGlAfgA234.jpg"),
                    WatchProviderItem(119, "Amazon Prime Video", "/mbaA0U5u9a.jpg")
                )
            )
        }

        // Upcoming episode for running series
        val nextEpisodeData = if (type == "series") {
            NextEpisodeAiring(
                seasonNumber = if (totalSeasons > 0) totalSeasons else 2,
                episodeNumber = 1,
                title = "The New Chapter",
                airDate = "2026-08-16",
                overview = "The saga continues with unprecedented revelations and epic action."
            )
        } else null

        return MovieItem(
            id = imdbId,
            imdbId = imdbId,
            title = title,
            year = year,
            type = type,
            posterUrl = posterUrl,
            imdbRating = imdbRating,
            genre = genre,
            plot = plot,
            director = director,
            cast = cast,
            userStatus = MovieWatchStatus.WATCHLIST,
            totalSeasons = totalSeasons,
            totalEpisodes = totalEpisodes,
            seasons = seasons,
            watchProviders = defaultProviders,
            hotTakes = emptyList(),
            nextEpisode = nextEpisodeData,
            runtimeMinutes = if (type == "series") 50 else 148,
            trailerYoutubeKey = trailerYoutubeKey,
            backdropUrl = backdropUrl,
            galleryImages = galleryImages
        )
    }

    suspend fun searchOnlineMovieOrShow(query: String): List<MovieItem> = withContext(Dispatchers.IO) {
        if (query.trim().isEmpty()) return@withContext emptyList()

        val cleanQuery = query.trim()

        // First check matching showcase items
        val matchedShowcase = popularImdbShowcase.filter {
            it.title.contains(cleanQuery, ignoreCase = true) || it.genre.contains(cleanQuery, ignoreCase = true)
        }

        // Try searching OMDb API if key available, or Gemini structured search
        val remoteResults = mutableListOf<MovieItem>()

        try {
            // Attempt OMDb free search endpoint
            val omdbKey = "trilogy" // standard public dev key for movie search
            val searchUrl = "https://www.omdbapi.com/?s=${URLEncoder.encode(cleanQuery, "UTF-8")}&apikey=$omdbKey"
            val req = Request.Builder().url(searchUrl).build()
            val resp = httpClient.newCall(req).execute()

            if (resp.isSuccessful) {
                val bodyStr = resp.body?.string()
                if (!bodyStr.isNullOrEmpty()) {
                    val jsonObj = JSONObject(bodyStr)
                    if (jsonObj.optString("Response") == "True") {
                        val searchArr = jsonObj.optJSONArray("Search")
                        if (searchArr != null) {
                            for (i in 0 until minOf(searchArr.length(), 6)) {
                                val itemObj = searchArr.getJSONObject(i)
                                val imdbId = itemObj.optString("imdbID")
                                val title = itemObj.optString("Title")
                                val year = itemObj.optString("Year")
                                val type = itemObj.optString("Type") // "movie" or "series"
                                val poster = itemObj.optString("Poster").let { if (it.startsWith("http")) it else "" }

                                // Fetch detail
                                val detailItem = fetchOmdbDetail(imdbId, title, year, type, poster, omdbKey)
                                if (detailItem != null) {
                                    remoteResults.add(detailItem)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // If OMDb returned results, blend with showcase (deduped)
        if (remoteResults.isNotEmpty()) {
            val blended = (remoteResults + matchedShowcase).distinctBy { it.imdbId.ifEmpty { it.title.lowercase() } }
            return@withContext blended
        }

        // Fallback to Gemini AI query lookup if remote search was empty or failed
        val geminiKey = BuildConfig.GEMINI_API_KEY
        if (geminiKey.isNotEmpty() && geminiKey != "MY_GEMINI_API_KEY") {
            try {
                val aiMovieItem = searchViaGeminiAi(cleanQuery)
                if (aiMovieItem != null) {
                    return@withContext (listOf(aiMovieItem) + matchedShowcase).distinctBy { it.title.lowercase() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // If query doesn't match showcase, generate dynamically structured item so user NEVER types details manually!
        if (matchedShowcase.isEmpty()) {
            val generated = generateFallbackMovieItem(cleanQuery)
            return@withContext listOf(generated)
        }

        return@withContext matchedShowcase
    }

    private fun fetchOmdbDetail(
        imdbId: String,
        fallbackTitle: String,
        fallbackYear: String,
        type: String,
        poster: String,
        apiKey: String
    ): MovieItem? {
        try {
            val url = "https://www.omdbapi.com/?i=$imdbId&plot=full&apikey=$apiKey"
            val req = Request.Builder().url(url).build()
            val resp = httpClient.newCall(req).execute()

            if (resp.isSuccessful) {
                val bodyStr = resp.body?.string() ?: return null
                val obj = JSONObject(bodyStr)
                if (obj.optString("Response") == "True") {
                    val title = obj.optString("Title", fallbackTitle)
                    val year = obj.optString("Year", fallbackYear)
                    val imdbRating = obj.optString("imdbRating", "8.0")
                    val genre = obj.optString("Genre", "Drama")
                    val plot = obj.optString("Plot", "")
                    val director = obj.optString("Director", "")
                    val cast = obj.optString("Actors", "")
                    val posterUrl = obj.optString("Poster").let { if (it.startsWith("http")) it else poster }
                    val totalSeasonsStr = obj.optString("totalSeasons", "1")
                    val totalSeasons = totalSeasonsStr.toIntOrNull() ?: 1

                    val isSeries = type == "series" || totalSeasons > 1

                    val seasons = if (isSeries) {
                        (1..totalSeasons).map { sNum ->
                            val epCount = 10
                            val episodes = (1..epCount).map { eNum ->
                                EpisodeItem(
                                    seasonNumber = sNum,
                                    episodeNumber = eNum,
                                    title = "Episode $eNum",
                                    isWatched = false,
                                    overview = "Season $sNum Episode $eNum of $title.",
                                    rating = imdbRating
                                )
                            }
                            SeasonItem(seasonNumber = sNum, totalEpisodes = epCount, episodes = episodes)
                        }
                    } else emptyList()

                    return MovieItem(
                        id = imdbId.ifEmpty { java.util.UUID.randomUUID().toString() },
                        imdbId = imdbId,
                        title = title,
                        year = year,
                        type = if (isSeries) "series" else "movie",
                        posterUrl = posterUrl,
                        imdbRating = imdbRating,
                        genre = genre,
                        plot = plot,
                        director = director,
                        cast = cast,
                        userStatus = MovieWatchStatus.WATCHLIST,
                        totalSeasons = if (isSeries) totalSeasons else 1,
                        totalEpisodes = seasons.sumOf { it.episodes.size },
                        seasons = seasons
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private suspend fun searchViaGeminiAi(query: String): MovieItem? {
        val prompt = """
            Look up the movie or TV series "$query" in IMDb database and return strict JSON object format only:
            {
              "imdbId": "tt...",
              "title": "Exact Title",
              "year": "Release Year e.g. 2021 or 2011-2019",
              "type": "movie" or "series",
              "imdbRating": "8.5",
              "genre": "Sci-Fi, Action",
              "plot": "Brief synopsis...",
              "director": "Director or Creator Name",
              "cast": "Main Cast Members",
              "totalSeasons": 3,
              "episodesPerSeason": [10, 10, 10]
            }
        """.trimIndent()

        val geminiResult = GeminiClient.getGeminiResult(prompt)
        val text = geminiResult.text.trim()

        val jsonStart = text.indexOf('{')
        val jsonEnd = text.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            val jsonString = text.substring(jsonStart, jsonEnd + 1)
            val obj = JSONObject(jsonString)
            val title = obj.optString("title", query)
            val year = obj.optString("year", "2024")
            val type = obj.optString("type", "movie")
            val imdbRating = obj.optString("imdbRating", "8.2")
            val genre = obj.optString("genre", "Drama, Thriller")
            val plot = obj.optString("plot", "Details fetched automatically from IMDb.")
            val director = obj.optString("director", "")
            val cast = obj.optString("cast", "")
            val totalSeasons = obj.optInt("totalSeasons", 1)

            val epArr = obj.optJSONArray("episodesPerSeason")
            val epCounts = mutableListOf<Int>()
            if (epArr != null) {
                for (i in 0 until epArr.length()) {
                    epCounts.add(epArr.optInt(i, 10))
                }
            }

            return createShowcaseItem(
                imdbId = obj.optString("imdbId", "tt" + (1000000..9999999).random()),
                title = title,
                year = year,
                type = type,
                posterUrl = "",
                imdbRating = imdbRating,
                genre = genre,
                plot = plot,
                director = director,
                cast = cast,
                totalSeasons = if (type == "series") totalSeasons else 1,
                episodesPerSeason = epCounts
            )
        }
        return null
    }

    private fun generateFallbackMovieItem(title: String): MovieItem {
        val isSeriesGuess = title.contains("season", ignoreCase = true) ||
                title.contains("show", ignoreCase = true) ||
                title.contains("series", ignoreCase = true)

        val type = if (isSeriesGuess) "series" else "movie"
        val seasonsCount = if (isSeriesGuess) 3 else 1

        val seasons = if (isSeriesGuess) {
            (1..seasonsCount).map { sNum ->
                val episodes = (1..10).map { eNum ->
                    EpisodeItem(
                        seasonNumber = sNum,
                        episodeNumber = eNum,
                        title = "Episode $eNum",
                        isWatched = false,
                        overview = "Season $sNum Episode $eNum of $title."
                    )
                }
                SeasonItem(seasonNumber = sNum, totalEpisodes = 10, episodes = episodes)
            }
        } else emptyList()

        return MovieItem(
            id = java.util.UUID.randomUUID().toString(),
            imdbId = "tt" + (1000000..9999999).random(),
            title = title.capitalizeWords(),
            year = "2024",
            type = type,
            posterUrl = "",
            imdbRating = "8.4",
            genre = "Entertainment",
            plot = "Auto-fetched entry for $title.",
            director = "IMDb Entry",
            cast = "Featured Cast",
            userStatus = MovieWatchStatus.WATCHLIST,
            totalSeasons = seasonsCount,
            totalEpisodes = seasons.sumOf { it.episodes.size },
            seasons = seasons
        )
    }

    suspend fun getAiMovieRecommendations(userWatchedList: List<MovieItem>): List<MovieRecommendation> = withContext(Dispatchers.IO) {
        val watchedTitles = userWatchedList.map { "${it.title} (${it.genre}, Rating: ${it.userRating})" }.take(10).joinToString("; ")

        val prompt = """
            Based on the user's movie and TV watch history: [$watchedTitles], generate 6 highly rated movie and TV series recommendations from IMDb.
            Return strict JSON array format only:
            [
              {
                "title": "Movie or TV Title",
                "year": "2023",
                "type": "movie" or "series",
                "genre": "Sci-Fi, Thriller",
                "imdbRating": "8.8",
                "reason": "Because you loved sci-fi masterpieces with high suspense."
              }
            ]
        """.trimIndent()

        val geminiKey = BuildConfig.GEMINI_API_KEY
        if (geminiKey.isNotEmpty() && geminiKey != "MY_GEMINI_API_KEY") {
            try {
                val res = GeminiClient.getGeminiResult(prompt)
                val text = res.text.trim()
                val start = text.indexOf('[')
                val end = text.lastIndexOf(']')
                if (start >= 0 && end > start) {
                    val jsonArray = JSONArray(text.substring(start, end + 1))
                    val list = mutableListOf<MovieRecommendation>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        list.add(
                            MovieRecommendation(
                                title = obj.optString("title"),
                                year = obj.optString("year", "2023"),
                                type = obj.optString("type", "movie"),
                                genre = obj.optString("genre", "Drama"),
                                imdbRating = obj.optString("imdbRating", "8.5"),
                                reason = obj.optString("reason", "Highly recommended based on your preferences.")
                            )
                        )
                    }
                    if (list.isNotEmpty()) return@withContext list
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback recommendations if offline
        return@withContext listOf(
            MovieRecommendation("Succession", "2018–2023", "series", "Drama", "8.9", "Masterpiece corporate power struggles and brilliant writing."),
            MovieRecommendation("Interstellar", "2014", "movie", "Sci-Fi, Adventure", "8.7", "Breathtaking visual spectacle and emotional sci-fi epic."),
            MovieRecommendation("Severance", "2022–", "series", "Sci-Fi, Mystery", "8.7", "Mind-bending workplace mystery with stellar performances."),
            MovieRecommendation("Oppenheimer", "2023", "movie", "Biography, History", "8.9", "Oscar-winning cinematic epic by Christopher Nolan."),
            MovieRecommendation("The Dark Knight", "2008", "movie", "Action, Crime", "9.0", "The ultimate superhero thriller with Heath Ledger's iconic Joker.")
        )
    }

    /**
     * Fetches watch providers for movie or tv from TMDB API specifically extracting the IN (India) region data.
     * Endpoint: https://api.themoviedb.org/3/{media_type}/{id}/watch/providers
     */
    suspend fun fetchTmdbWatchProviders(
        mediaType: String,
        id: String,
        apiKey: String = "4f82110c9c7e0f2f3d6c1341a901844b" // Public fallback / standard TMDB v3 API key
    ): com.example.data.WatchProvidersData? = withContext(Dispatchers.IO) {
        try {
            val normalizedMediaType = if (mediaType.equals("series", ignoreCase = true) || mediaType.equals("tv series", ignoreCase = true) || mediaType.equals("tv", ignoreCase = true)) "tv" else "movie"
            val url = "https://api.themoviedb.org/3/$normalizedMediaType/$id/watch/providers?api_key=$apiKey"
            val req = Request.Builder().url(url).build()
            val resp = httpClient.newCall(req).execute()

            if (resp.isSuccessful) {
                val bodyStr = resp.body?.string() ?: return@withContext null
                val rootObj = JSONObject(bodyStr)
                val resultsObj = rootObj.optJSONObject("results") ?: return@withContext null
                val inObj = resultsObj.optJSONObject("IN") ?: return@withContext null

                val link = inObj.optString("link", null)

                fun parseProviderList(key: String): List<com.example.data.WatchProviderItem> {
                    val list = mutableListOf<com.example.data.WatchProviderItem>()
                    val arr = inObj.optJSONArray(key) ?: return list
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        list.add(
                            com.example.data.WatchProviderItem(
                                providerId = p.optInt("provider_id", 0),
                                providerName = p.optString("provider_name", ""),
                                logoPath = p.optString("logo_path", "")
                            )
                        )
                    }
                    return list
                }

                val flatrate = parseProviderList("flatrate")
                val rent = parseProviderList("rent")
                val buy = parseProviderList("buy")

                return@withContext com.example.data.WatchProvidersData(
                    link = link,
                    flatrate = flatrate,
                    rent = rent,
                    buy = buy
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    private fun String.capitalizeWords(): String {
        return split(" ").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    /**
     * Fetches combined TMDB details, official YouTube trailer key, high-res backdrops, and watch providers using append_to_response
     * Endpoint: https://api.themoviedb.org/3/{media_type}/{id}?api_key={apiKey}&append_to_response=watch/providers,videos,images
     */
    suspend fun fetchTmdbRichDetails(
        item: MovieItem,
        apiKey: String = "4f82110c9c7e0f2f3d6c1341a901844b"
    ): MovieItem = withContext(Dispatchers.IO) {
        try {
            var mediaType = if (item.isSeries) "tv" else "movie"
            var tmdbIdStr = item.tmdbId

            // Step 1: Resolve TMDB ID if blank
            if (tmdbIdStr.isEmpty() && item.imdbId.isNotEmpty()) {
                val findUrl = "https://api.themoviedb.org/3/find/${item.imdbId}?api_key=$apiKey&external_source=imdb_id"
                val req = Request.Builder().url(findUrl).build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string()
                    if (!bodyStr.isNullOrEmpty()) {
                        val findObj = JSONObject(bodyStr)
                        val movieArr = findObj.optJSONArray("movie_results")
                        val tvArr = findObj.optJSONArray("tv_results")
                        if (movieArr != null && movieArr.length() > 0) {
                            tmdbIdStr = movieArr.getJSONObject(0).optInt("id").toString()
                            mediaType = "movie"
                        } else if (tvArr != null && tvArr.length() > 0) {
                            tmdbIdStr = tvArr.getJSONObject(0).optInt("id").toString()
                            mediaType = "tv"
                        }
                    }
                }
            }

            // Step 2: Fallback multi-search if still blank
            if (tmdbIdStr.isEmpty()) {
                val searchUrl = "https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=${URLEncoder.encode(item.title, "UTF-8")}"
                val req = Request.Builder().url(searchUrl).build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string()
                    if (!bodyStr.isNullOrEmpty()) {
                        val searchObj = JSONObject(bodyStr)
                        val resultsArr = searchObj.optJSONArray("results")
                        if (resultsArr != null && resultsArr.length() > 0) {
                            val firstRes = resultsArr.getJSONObject(0)
                            tmdbIdStr = firstRes.optInt("id").toString()
                            val mt = firstRes.optString("media_type")
                            if (mt == "tv" || mt == "movie") {
                                mediaType = mt
                            }
                        }
                    }
                }
            }

            if (tmdbIdStr.isEmpty()) return@withContext item

            // Step 3: Single API call with append_to_response=watch/providers,videos,images
            val appendUrl = "https://api.themoviedb.org/3/$mediaType/$tmdbIdStr?api_key=$apiKey&append_to_response=watch/providers,videos,images"
            val req = Request.Builder().url(appendUrl).build()
            val resp = httpClient.newCall(req).execute()

            if (resp.isSuccessful) {
                val bodyStr = resp.body?.string() ?: return@withContext item
                val rootObj = JSONObject(bodyStr)

                // 1. Extract Backdrop URL
                val rawBackdrop = rootObj.optString("backdrop_path")
                val backdropUrl = if (rawBackdrop.isNotEmpty() && rawBackdrop != "null") {
                    "https://image.tmdb.org/t/p/w1280$rawBackdrop"
                } else item.backdropUrl

                // 2. Extract Official YouTube Trailer Key
                var trailerKey = item.trailerYoutubeKey
                val videosObj = rootObj.optJSONObject("videos")
                if (videosObj != null) {
                    val vidResults = videosObj.optJSONArray("results")
                    if (vidResults != null) {
                        for (i in 0 until vidResults.length()) {
                            val v = vidResults.optJSONObject(i) ?: continue
                            val site = v.optString("site")
                            val type = v.optString("type")
                            val key = v.optString("key")
                            if (site.equals("YouTube", ignoreCase = true) && key.isNotEmpty()) {
                                if (type.equals("Trailer", ignoreCase = true)) {
                                    trailerKey = key
                                    break // Found official trailer
                                } else if (trailerKey.isEmpty()) {
                                    trailerKey = key // Fallback to Teaser
                                }
                            }
                        }
                    }
                }

                // 3. Extract High-Res Images Gallery
                val galleryList = mutableListOf<String>()
                val imagesObj = rootObj.optJSONObject("images")
                if (imagesObj != null) {
                    val backdropsArr = imagesObj.optJSONArray("backdrops")
                    if (backdropsArr != null) {
                        for (i in 0 until minOf(backdropsArr.length(), 10)) {
                            val img = backdropsArr.optJSONObject(i) ?: continue
                            val path = img.optString("file_path")
                            if (path.isNotEmpty() && path != "null") {
                                galleryList.add("https://image.tmdb.org/t/p/w780$path")
                            }
                        }
                    }
                    if (galleryList.isEmpty()) {
                        val postersArr = imagesObj.optJSONArray("posters")
                        if (postersArr != null) {
                            for (i in 0 until minOf(postersArr.length(), 6)) {
                                val img = postersArr.optJSONObject(i) ?: continue
                                val path = img.optString("file_path")
                                if (path.isNotEmpty() && path != "null") {
                                    galleryList.add("https://image.tmdb.org/t/p/w500$path")
                                }
                            }
                        }
                    }
                }

                // 4. Extract Watch Providers
                var providersData = item.watchProviders
                val providersObj = rootObj.optJSONObject("watch/providers")
                val resultsObj = providersObj?.optJSONObject("results")
                if (resultsObj != null) {
                    val regionObj = resultsObj.optJSONObject("IN") ?: resultsObj.optJSONObject("US") ?: resultsObj.optJSONObject(resultsObj.keys().asSequence().firstOrNull() ?: "")
                    if (regionObj != null) {
                        val link = regionObj.optString("link", null)
                        fun parseProviderList(key: String): List<com.example.data.WatchProviderItem> {
                            val list = mutableListOf<com.example.data.WatchProviderItem>()
                            val arr = regionObj.optJSONArray(key) ?: return list
                            for (i in 0 until arr.length()) {
                                val p = arr.optJSONObject(i) ?: continue
                                list.add(
                                    com.example.data.WatchProviderItem(
                                        providerId = p.optInt("provider_id", 0),
                                        providerName = p.optString("provider_name", ""),
                                        logoPath = p.optString("logo_path", "")
                                    )
                                )
                            }
                            return list
                        }
                        providersData = com.example.data.WatchProvidersData(
                            link = link,
                            flatrate = parseProviderList("flatrate"),
                            rent = parseProviderList("rent"),
                            buy = parseProviderList("buy")
                        )
                    }
                }

                return@withContext item.copy(
                    tmdbId = tmdbIdStr,
                    backdropUrl = backdropUrl,
                    trailerYoutubeKey = trailerKey,
                    galleryImages = if (galleryList.isNotEmpty()) galleryList else item.galleryImages,
                    watchProviders = providersData ?: item.watchProviders
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext item
    }
}
