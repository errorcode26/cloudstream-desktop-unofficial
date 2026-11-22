package com.lagradost.cloudstream3.syncproviders.providers.anilist

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class AniListStatusType(var value: Int) {
    Watching(0),
    Completed(1),
    Paused(2),
    Dropped(3),
    Planning(4),
    ReWatching(5),
    None(-1);

    companion object {
        fun fromInt(inp: Int): AniListStatusType {
            return when (inp) {
                -1 -> None
                0 -> Watching
                1 -> Completed
                2 -> Paused
                3 -> Dropped
                4 -> Planning
                5 -> ReWatching
                else -> None
            }
        }
    }
}

@Serializable
data class Variables(
    @JsonProperty("search") @SerialName("search") val search: String,
    @JsonProperty("page") @SerialName("page") val page: Int,
    @JsonProperty("type") @SerialName("type") val type: String,
)

@Serializable
data class MediaRecommendation(
    @JsonProperty("id") @SerialName("id") val id: Int,
    @JsonProperty("title") @SerialName("title") val title: Title?,
    @JsonProperty("idMal") @SerialName("idMal") val idMal: Int?,
    @JsonProperty("coverImage") @SerialName("coverImage") val coverImage: CoverImage?,
    @JsonProperty("averageScore") @SerialName("averageScore") val averageScore: Int?,
)

@Serializable
data class FullAnilistList(
    @JsonProperty("data") @SerialName("data") val data: Data?,
)

@Serializable
data class CompletedAt(
    @JsonProperty("year") @SerialName("year") val year: Int,
    @JsonProperty("month") @SerialName("month") val month: Int,
    @JsonProperty("day") @SerialName("day") val day: Int,
)

@Serializable
data class StartedAt(
    @JsonProperty("year") @SerialName("year") val year: String?,
    @JsonProperty("month") @SerialName("month") val month: String?,
    @JsonProperty("day") @SerialName("day") val day: String?,
)

@Serializable
data class Title(
    @JsonProperty("english") @SerialName("english") val english: String?,
    @JsonProperty("romaji") @SerialName("romaji") val romaji: String?,
)

@Serializable
data class CoverImage(
    @JsonProperty("medium") @SerialName("medium") val medium: String?,
    @JsonProperty("large") @SerialName("large") val large: String?,
    @JsonProperty("extraLarge") @SerialName("extraLarge") val extraLarge: String?,
)

@Serializable
data class Media(
    @JsonProperty("id") @SerialName("id") val id: Int,
    @JsonProperty("idMal") @SerialName("idMal") val idMal: Int?,
    @JsonProperty("season") @SerialName("season") val season: String?,
    @JsonProperty("seasonYear") @SerialName("seasonYear") val seasonYear: Int,
    @JsonProperty("format") @SerialName("format") val format: String?,
    @JsonProperty("episodes") @SerialName("episodes") val episodes: Int,
    @JsonProperty("title") @SerialName("title") val title: Title,
    @JsonProperty("description") @SerialName("description") val description: String?,
    @JsonProperty("coverImage") @SerialName("coverImage") val coverImage: CoverImage,
    @JsonProperty("synonyms") @SerialName("synonyms") val synonyms: List<String>,
    @JsonProperty("nextAiringEpisode") @SerialName("nextAiringEpisode") val nextAiringEpisode: SeasonNextAiringEpisode?,
)

@Serializable
data class Entries(
    @JsonProperty("status") @SerialName("status") val status: String?,
    @JsonProperty("completedAt") @SerialName("completedAt") val completedAt: CompletedAt,
    @JsonProperty("startedAt") @SerialName("startedAt") val startedAt: StartedAt,
    @JsonProperty("updatedAt") @SerialName("updatedAt") val updatedAt: Int,
    @JsonProperty("progress") @SerialName("progress") val progress: Int,
    @JsonProperty("score") @SerialName("score") val score: Int,
    @JsonProperty("private") @SerialName("private") val private: Boolean,
    @JsonProperty("media") @SerialName("media") val media: Media,
) {
    fun toLibraryItem(): SyncAPI.LibraryItem {
        return SyncAPI.LibraryItem(
            name = this.media.title.english ?: this.media.title.romaji
                ?: this.media.synonyms.firstOrNull()
                ?: "",
            url = "https://anilist.co/anime/${this.media.id}/",
            syncId = this.media.id.toString(),
            episodesCompleted = this.progress,
            episodesTotal = this.media.episodes,
            personalRating = Score.from100(this.score),
            lastUpdatedUnixTime = this.updatedAt.toLong(),
            apiName = "AniList",
            type = TvType.Anime,
            posterUrl = this.media.coverImage.extraLarge ?: this.media.coverImage.large
                ?: this.media.coverImage.medium,
            posterHeaders = null,
            quality = null,
            releaseDate = null,
            id = null,
            plot = this.media.description,
        )
    }
}

@Serializable
data class Lists(
    @JsonProperty("status") @SerialName("status") val status: String?,
    @JsonProperty("entries") @SerialName("entries") val entries: List<Entries>,
)

@Serializable
data class MediaListCollection(
    @JsonProperty("lists") @SerialName("lists") val lists: List<Lists>,
)

@Serializable
data class Data(
    @JsonProperty("MediaListCollection") @SerialName("MediaListCollection") val mediaListCollection: MediaListCollection,
)

@Serializable
data class MediaListItemRoot(
    @JsonProperty("data") @SerialName("data") val data: MediaListItem? = null,
)

@Serializable
data class MediaListItem(
    @JsonProperty("MediaList") @SerialName("MediaList") val mediaList: MediaListId? = null,
)

@Serializable
data class MediaListId(
    @JsonProperty("id") @SerialName("id") val id: Long? = null,
)

@Serializable
data class SeasonResponse(
    @JsonProperty("data") @SerialName("data") val data: SeasonData,
)

@Serializable
data class SeasonData(
    @JsonProperty("Media") @SerialName("Media") val media: SeasonMedia,
)

@Serializable
data class RecommendedMedia(
    @JsonProperty("id") @SerialName("id") val id: Int?,
    @JsonProperty("title") @SerialName("title") val title: MediaTitle?,
    @JsonProperty("coverImage") @SerialName("coverImage") val coverImage: MediaCoverImage?,
)

@Serializable
data class CharacterMedia(
    @JsonProperty("id") @SerialName("id") val id: Int?,
    @JsonProperty("title") @SerialName("title") val title: MediaTitle?,
    @JsonProperty("coverImage") @SerialName("coverImage") val coverImage: MediaCoverImage?,
)

@Serializable
data class SeasonMedia(
    @JsonProperty("id") @SerialName("id") val id: Int?,
    @JsonProperty("title") @SerialName("title") val title: MediaTitle?,
    @JsonProperty("idMal") @SerialName("idMal") val idMal: Int?,
    @JsonProperty("format") @SerialName("format") val format: String?,
    @JsonProperty("nextAiringEpisode") @SerialName("nextAiringEpisode") val nextAiringEpisode: SeasonNextAiringEpisode?,
    @JsonProperty("relations") @SerialName("relations") val relations: SeasonEdges?,
    @JsonProperty("coverImage") @SerialName("coverImage") val coverImage: MediaCoverImage?,
    @JsonProperty("duration") @SerialName("duration") val duration: Int?,
    @JsonProperty("episodes") @SerialName("episodes") val episodes: Int?,
    @JsonProperty("genres") @SerialName("genres") val genres: List<String>?,
    @JsonProperty("synonyms") @SerialName("synonyms") val synonyms: List<String>?,
    @JsonProperty("averageScore") @SerialName("averageScore") val averageScore: Int?,
    @JsonProperty("isAdult") @SerialName("isAdult") val isAdult: Boolean?,
    @JsonProperty("trailer") @SerialName("trailer") val trailer: MediaTrailer?,
    @JsonProperty("description") @SerialName("description") val description: String?,
    @JsonProperty("characters") @SerialName("characters") val characters: CharacterConnection?,
    @JsonProperty("recommendations") @SerialName("recommendations") val recommendations: RecommendationConnection?,
)

@Serializable
data class RecommendationConnection(
    @JsonProperty("edges") @SerialName("edges") val edges: List<RecommendationEdge> = emptyList(),
    @JsonProperty("nodes") @SerialName("nodes") val nodes: List<Recommendation> = emptyList(),
)

@Serializable
data class RecommendationEdge(
    @JsonProperty("node") @SerialName("node") val node: Recommendation,
)

@Serializable
data class Recommendation(
    @JsonProperty("mediaRecommendation") @SerialName("mediaRecommendation") val mediaRecommendation: RecommendedMedia?,
)

@Serializable
data class CharacterName(
    @JsonProperty("name") @SerialName("name") val first: String?,
    @JsonProperty("middle") @SerialName("middle") val middle: String?,
    @JsonProperty("last") @SerialName("last") val last: String?,
    @JsonProperty("full") @SerialName("full") val full: String?,
    @JsonProperty("native") @SerialName("native") val native: String?,
    @JsonProperty("alternative") @SerialName("alternative") val alternative: List<String>?,
    @JsonProperty("alternativeSpoiler") @SerialName("alternativeSpoiler") val alternativeSpoiler: List<String>?,
    @JsonProperty("userPreferred") @SerialName("userPreferred") val userPreferred: String?,
)

@Serializable
data class CharacterImage(
    @JsonProperty("large") @SerialName("large") val large: String?,
    @JsonProperty("medium") @SerialName("medium") val medium: String?,
)

@Serializable
data class Character(
    @JsonProperty("name") @SerialName("name") val name: CharacterName?,
    @JsonProperty("age") @SerialName("age") val age: String?,
    @JsonProperty("image") @SerialName("image") val image: CharacterImage?,
)

@Serializable
data class CharacterEdge(
    @JsonProperty("id") @SerialName("id") val id: Int?,
    @JsonProperty("role") @SerialName("role") val role: String?,
    @JsonProperty("name") @SerialName("name") val name: String?,
    @JsonProperty("voiceActors") @SerialName("voiceActors") val voiceActors: List<Staff>?,
    @JsonProperty("favouriteOrder") @SerialName("favouriteOrder") val favouriteOrder: Int?,
    @JsonProperty("media") @SerialName("media") val media: List<CharacterMedia>?,
    @JsonProperty("node") @SerialName("node") val node: Character?,
)

@Serializable
data class StaffImage(
    @JsonProperty("large") @SerialName("large") val large: String?,
    @JsonProperty("medium") @SerialName("medium") val medium: String?,
)

@Serializable
data class StaffName(
    @JsonProperty("name") @SerialName("name") val first: String?,
    @JsonProperty("middle") @SerialName("middle") val middle: String?,
    @JsonProperty("last") @SerialName("last") val last: String?,
    @JsonProperty("full") @SerialName("full") val full: String?,
    @JsonProperty("native") @SerialName("native") val native: String?,
    @JsonProperty("alternative") @SerialName("alternative") val alternative: List<String>?,
    @JsonProperty("userPreferred") @SerialName("userPreferred") val userPreferred: String?,
)

@Serializable
data class Staff(
    @JsonProperty("image") @SerialName("image") val image: StaffImage?,
    @JsonProperty("name") @SerialName("name") val name: StaffName?,
    @JsonProperty("age") @SerialName("age") val age: Int?,
)

@Serializable
data class CharacterConnection(
    @JsonProperty("edges") @SerialName("edges") val edges: List<CharacterEdge>?,
    @JsonProperty("nodes") @SerialName("nodes") val nodes: List<Character>?,
)

@Serializable
data class MediaTrailer(
    @JsonProperty("id") @SerialName("id") val id: String?,
    @JsonProperty("site") @SerialName("site") val site: String?,
    @JsonProperty("thumbnail") @SerialName("thumbnail") val thumbnail: String?,
)

@Serializable
data class MediaCoverImage(
    @JsonProperty("extraLarge") @SerialName("extraLarge") val extraLarge: String?,
    @JsonProperty("large") @SerialName("large") val large: String?,
    @JsonProperty("medium") @SerialName("medium") val medium: String?,
    @JsonProperty("color") @SerialName("color") val color: String?,
)

@Serializable
data class SeasonNextAiringEpisode(
    @JsonProperty("episode") @SerialName("episode") val episode: Int?,
    @JsonProperty("timeUntilAiring") @SerialName("timeUntilAiring") val timeUntilAiring: Int?,
)

@Serializable
data class SeasonEdges(
    @JsonProperty("edges") @SerialName("edges") val edges: List<SeasonEdge>?,
)

@Serializable
data class SeasonEdge(
    @JsonProperty("id") @SerialName("id") val id: Int?,
    @JsonProperty("relationType") @SerialName("relationType") val relationType: String?,
    @JsonProperty("node") @SerialName("node") val node: SeasonNode?,
)

@Serializable
data class AniListFavoritesMediaConnection(
    @JsonProperty("nodes") @SerialName("nodes") val nodes: List<LikeNode>,
)

@Serializable
data class AniListFavourites(
    @JsonProperty("anime") @SerialName("anime") val anime: AniListFavoritesMediaConnection,
)

@Serializable
data class MediaTitle(
    @JsonProperty("romaji") @SerialName("romaji") val romaji: String?,
    @JsonProperty("english") @SerialName("english") val english: String?,
    @JsonProperty("native") @SerialName("native") val native: String?,
    @JsonProperty("userPreferred") @SerialName("userPreferred") val userPreferred: String?,
)

@Serializable
data class SeasonNode(
    @JsonProperty("id") @SerialName("id") val id: Int,
    @JsonProperty("format") @SerialName("format") val format: String?,
    @JsonProperty("title") @SerialName("title") val title: Title?,
    @JsonProperty("idMal") @SerialName("idMal") val idMal: Int?,
    @JsonProperty("coverImage") @SerialName("coverImage") val coverImage: CoverImage?,
    @JsonProperty("averageScore") @SerialName("averageScore") val averageScore: Int?,
)

@Serializable
data class AniListAvatar(
    @JsonProperty("large") @SerialName("large") val large: String?,
)

@Serializable
data class AniListViewer(
    @JsonProperty("id") @SerialName("id") val id: Int,
    @JsonProperty("name") @SerialName("name") val name: String,
    @JsonProperty("avatar") @SerialName("avatar") val avatar: AniListAvatar?,
    @JsonProperty("favourites") @SerialName("favourites") val favourites: AniListFavourites?,
)

@Serializable
data class AniListData(
    @JsonProperty("Viewer") @SerialName("Viewer") val viewer: AniListViewer?,
)

@Serializable
data class AniListRoot(
    @JsonProperty("data") @SerialName("data") val data: AniListData?,
)

@Serializable
data class AniListUser(
    @JsonProperty("id") @SerialName("id") val id: Int,
    @JsonProperty("name") @SerialName("name") val name: String,
    @JsonProperty("picture") @SerialName("picture") val picture: String?,
)

@Serializable
data class LikeNode(
    @JsonProperty("id") @SerialName("id") val id: Int?,
)

@Serializable
data class LikePageInfo(
    @JsonProperty("total") @SerialName("total") val total: Int?,
    @JsonProperty("currentPage") @SerialName("currentPage") val currentPage: Int?,
    @JsonProperty("lastPage") @SerialName("lastPage") val lastPage: Int?,
    @JsonProperty("perPage") @SerialName("perPage") val perPage: Int?,
    @JsonProperty("hasNextPage") @SerialName("hasNextPage") val hasNextPage: Boolean?,
)

@Serializable
data class LikeAnime(
    @JsonProperty("nodes") @SerialName("nodes") val nodes: List<LikeNode>?,
    @JsonProperty("pageInfo") @SerialName("pageInfo") val pageInfo: LikePageInfo?,
)

@Serializable
data class LikeFavourites(
    @JsonProperty("anime") @SerialName("anime") val anime: LikeAnime?,
)

@Serializable
data class LikeViewer(
    @JsonProperty("favourites") @SerialName("favourites") val favourites: LikeFavourites?,
)

@Serializable
data class LikeData(
    @JsonProperty("Viewer") @SerialName("Viewer") val viewer: LikeViewer?,
)

@Serializable
data class LikeRoot(
    @JsonProperty("data") @SerialName("data") val data: LikeData?,
)

@Serializable
data class AniListTitleHolder(
    @JsonProperty("title") @SerialName("title") val title: Title?,
    @JsonProperty("isFavourite") @SerialName("isFavourite") val isFavourite: Boolean?,
    @JsonProperty("id") @SerialName("id") val id: Int?,
    @JsonProperty("progress") @SerialName("progress") val progress: Int?,
    @JsonProperty("episodes") @SerialName("episodes") val episodes: Int?,
    @JsonProperty("score") @SerialName("score") val score: Int?,
    @JsonProperty("type") @SerialName("type") val type: AniListStatusType?,
)

@Serializable
data class GetDataMediaListEntry(
    @JsonProperty("progress") @SerialName("progress") val progress: Int?,
    @JsonProperty("status") @SerialName("status") val status: String?,
    @JsonProperty("score") @SerialName("score") val score: Int?,
)

@Serializable
data class Nodes(
    @JsonProperty("id") @SerialName("id") val id: Int?,
    @JsonProperty("mediaRecommendation") @SerialName("mediaRecommendation") val mediaRecommendation: MediaRecommendation?,
)

@Serializable
data class GetDataMedia(
    @JsonProperty("isFavourite") @SerialName("isFavourite") val isFavourite: Boolean?,
    @JsonProperty("episodes") @SerialName("episodes") val episodes: Int?,
    @JsonProperty("title") @SerialName("title") val title: Title?,
    @JsonProperty("mediaListEntry") @SerialName("mediaListEntry") val mediaListEntry: GetDataMediaListEntry?,
)

@Serializable
data class Recommendations(
    @JsonProperty("nodes") @SerialName("nodes") val nodes: List<Nodes>?,
)

@Serializable
data class GetDataData(
    @JsonProperty("Media") @SerialName("Media") val media: GetDataMedia?,
)

@Serializable
data class GetDataRoot(
    @JsonProperty("data") @SerialName("data") val data: GetDataData?,
)

@Serializable
data class GetSearchTitle(
    @JsonProperty("romaji") @SerialName("romaji") val romaji: String?,
)

@Serializable
data class TrailerObject(
    @JsonProperty("id") @SerialName("id") val id: String?,
    @JsonProperty("thumbnail") @SerialName("thumbnail") val thumbnail: String?,
    @JsonProperty("site") @SerialName("site") val site: String?,
)

@Serializable
data class GetSearchMedia(
    @JsonProperty("id") @SerialName("id") val id: Int,
    @JsonProperty("idMal") @SerialName("idMal") val idMal: Int?,
    @JsonProperty("seasonYear") @SerialName("seasonYear") val seasonYear: Int,
    @JsonProperty("title") @SerialName("title") val title: GetSearchTitle,
    @JsonProperty("startDate") @SerialName("startDate") val startDate: StartedAt,
    @JsonProperty("averageScore") @SerialName("averageScore") val averageScore: Int?,
    @JsonProperty("meanScore") @SerialName("meanScore") val meanScore: Int?,
    @JsonProperty("bannerImage") @SerialName("bannerImage") val bannerImage: String?,
    @JsonProperty("trailer") @SerialName("trailer") val trailer: TrailerObject?,
    @JsonProperty("nextAiringEpisode") @SerialName("nextAiringEpisode") val nextAiringEpisode: SeasonNextAiringEpisode?,
    @JsonProperty("recommendations") @SerialName("recommendations") val recommendations: Recommendations?,
    @JsonProperty("relations") @SerialName("relations") val relations: SeasonEdges?,
)

@Serializable
data class GetSearchPage(
    @JsonProperty("Page") @SerialName("Page") val page: GetSearchData?,
)

@Serializable
data class GetSearchData(
    @JsonProperty("media") @SerialName("media") val media: List<GetSearchMedia>?,
)

@Serializable
data class GetSearchRoot(
    @JsonProperty("data") @SerialName("data") val data: GetSearchPage?,
)
