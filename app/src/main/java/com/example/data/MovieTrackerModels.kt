package com.example.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

enum class MovieWatchStatus {
    @Json(name = "WATCHING") WATCHING,
    @Json(name = "WATCHLIST") WATCHLIST,
    @Json(name = "COMPLETED") COMPLETED,
    @Json(name = "DROPPED") DROPPED
}

enum class ListVisibility {
    @Json(name = "PUBLIC") PUBLIC,
    @Json(name = "PRIVATE") PRIVATE
}

@JsonClass(generateAdapter = true)
data class UserHotTake(
    @Json(name = "id") val id: String = java.util.UUID.randomUUID().toString(),
    @Json(name = "userId") val userId: String = "user_me",
    @Json(name = "userName") val userName: String = "You",
    @Json(name = "avatarUrl") val avatarUrl: String = "",
    @Json(name = "rating") val rating: Float = 5.0f, // 1 to 5 stars
    @Json(name = "review") val review: String = "", // 1-sentence review
    @Json(name = "timestamp") val timestamp: Long = System.currentTimeMillis()
)

@JsonClass(generateAdapter = true)
data class NextEpisodeAiring(
    @Json(name = "seasonNumber") val seasonNumber: Int = 1,
    @Json(name = "episodeNumber") val episodeNumber: Int = 1,
    @Json(name = "title") val title: String = "",
    @Json(name = "airDate") val airDate: String = "", // e.g., "2026-08-16"
    @Json(name = "overview") val overview: String = ""
)

@JsonClass(generateAdapter = true)
data class GroupBingerStat(
    @Json(name = "userId") val userId: String = "user_1",
    @Json(name = "userName") val userName: String = "Subash",
    @Json(name = "avatarUrl") val avatarUrl: String = "",
    @Json(name = "hoursWatched") val hoursWatched: Float = 112f,
    @Json(name = "titlesCount") val titlesCount: Int = 24,
    @Json(name = "badge") val badge: String = "🥇 Binge King"
)

@JsonClass(generateAdapter = true)
data class UserProfile(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "avatarUrl") val avatarUrl: String
)

@JsonClass(generateAdapter = true)
data class WatchProviderItem(
    @Json(name = "provider_id") val providerId: Int = 0,
    @Json(name = "provider_name") val providerName: String = "",
    @Json(name = "logo_path") val logoPath: String = ""
) {
    val logoUrl: String
        get() = if (logoPath.isNotEmpty()) "https://image.tmdb.org/t/p/w92$logoPath" else ""
}

@JsonClass(generateAdapter = true)
data class WatchProvidersData(
    @Json(name = "link") val link: String? = null,
    @Json(name = "flatrate") val flatrate: List<WatchProviderItem> = emptyList(),
    @Json(name = "rent") val rent: List<WatchProviderItem> = emptyList(),
    @Json(name = "buy") val buy: List<WatchProviderItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class StreamingProvider(
    @Json(name = "providerName") val providerName: String,
    @Json(name = "logoUrl") val logoUrl: String
)

@JsonClass(generateAdapter = true)
data class EpisodeItem(
    @Json(name = "seasonNumber") val seasonNumber: Int = 1,
    @Json(name = "episodeNumber") val episodeNumber: Int = 1,
    @Json(name = "title") val title: String = "",
    @Json(name = "isWatched") val isWatched: Boolean = false,
    @Json(name = "overview") val overview: String = "",
    @Json(name = "airDate") val airDate: String = "",
    @Json(name = "rating") val rating: String = ""
)

@JsonClass(generateAdapter = true)
data class SeasonItem(
    @Json(name = "seasonNumber") val seasonNumber: Int = 1,
    @Json(name = "totalEpisodes") val totalEpisodes: Int = 0,
    @Json(name = "episodes") val episodes: List<EpisodeItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class MovieItem(
    @Json(name = "id") val id: String = java.util.UUID.randomUUID().toString(),
    @Json(name = "tmdbId") val tmdbId: String = "",
    @Json(name = "imdbId") val imdbId: String = "",
    @Json(name = "title") val title: String = "",
    @Json(name = "year") val year: String = "",
    @Json(name = "type") val type: String = "movie", // "movie" or "series"
    @Json(name = "posterUrl") val posterUrl: String = "",
    @Json(name = "imdbRating") val imdbRating: String = "",
    @Json(name = "rottenTomatoesScore") val rottenTomatoesScore: String = "",
    @Json(name = "genre") val genre: String = "",
    @Json(name = "plot") val plot: String = "",
    @Json(name = "director") val director: String = "",
    @Json(name = "cast") val cast: String = "",
    @Json(name = "userStatus") val userStatus: MovieWatchStatus = MovieWatchStatus.WATCHLIST,
    @Json(name = "visibility") val visibility: ListVisibility = ListVisibility.PUBLIC,
    @Json(name = "userId") val userId: String = "user_1",
    @Json(name = "userName") val userName: String = "Alex",
    @Json(name = "userRating") val userRating: Float = 0f, // 0.0 to 10.0
    @Json(name = "userNotes") val userNotes: String = "",
    @Json(name = "watchedDate") val watchedDate: Long = System.currentTimeMillis(),
    @Json(name = "totalSeasons") val totalSeasons: Int = 1,
    @Json(name = "totalEpisodes") val totalEpisodes: Int = 0,
    @Json(name = "watchedEpisodesCount") val watchedEpisodesCount: Int = 0,
    @Json(name = "seasons") val seasons: List<SeasonItem> = emptyList(),
    @Json(name = "streamingProviders") val streamingProviders: List<StreamingProvider> = emptyList(),
    @Json(name = "watchedByUsers") val watchedByUsers: List<UserProfile> = emptyList(),
    @Json(name = "watchProviders") val watchProviders: WatchProvidersData? = null,
    @Json(name = "runtimeMinutes") val runtimeMinutes: Int = 110,
    @Json(name = "hotTakes") val hotTakes: List<UserHotTake> = emptyList(),
    @Json(name = "nextEpisode") val nextEpisode: NextEpisodeAiring? = null,
    @Json(name = "trailerYoutubeKey") val trailerYoutubeKey: String = "",
    @Json(name = "backdropUrl") val backdropUrl: String = "",
    @Json(name = "galleryImages") val galleryImages: List<String> = emptyList()
) {
    val isSeries: Boolean
        get() = type.lowercase() == "series" || type.lowercase() == "tv series" || type.lowercase() == "tv" || totalSeasons > 1 || seasons.isNotEmpty()

    val totalEpisodesCalculated: Int
        get() = if (seasons.isNotEmpty()) seasons.sumOf { it.episodes.size } else totalEpisodes

    val watchedEpisodesCalculated: Int
        get() = if (seasons.isNotEmpty()) seasons.sumOf { s -> s.episodes.count { it.isWatched } } else watchedEpisodesCount

    val progressPercent: Float
        get() {
            if (!isSeries) return if (userStatus == MovieWatchStatus.COMPLETED) 1f else 0f
            val total = totalEpisodesCalculated
            if (total == 0) return 0f
            return (watchedEpisodesCalculated.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
}

@JsonClass(generateAdapter = true)
data class MovieRecommendation(
    @Json(name = "title") val title: String,
    @Json(name = "year") val year: String,
    @Json(name = "type") val type: String,
    @Json(name = "genre") val genre: String,
    @Json(name = "imdbRating") val imdbRating: String,
    @Json(name = "reason") val reason: String,
    @Json(name = "posterUrl") val posterUrl: String = "",
    @Json(name = "tmdbId") val tmdbId: String = ""
)

