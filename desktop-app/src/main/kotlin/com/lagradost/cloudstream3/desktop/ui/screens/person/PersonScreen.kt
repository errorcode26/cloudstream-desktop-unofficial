package com.lagradost.cloudstream3.desktop.ui.screens.person

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.ui.components.DesktopDrilldownScaffold
import com.lagradost.cloudstream3.desktop.ui.components.DesktopVerticalScrollbar
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.components.collectIsKeyboardFocusedAsState
import com.lagradost.cloudstream3.desktop.ui.components.defaultScrollbarStyle
import com.lagradost.cloudstream3.desktop.ui.components.desktopFocusRing
import com.lagradost.cloudstream3.desktop.ui.components.desktopInteractiveFocus
import com.lagradost.cloudstream3.desktop.ui.navigation.Config
import com.lagradost.cloudstream3.desktop.explore.models.ExploreItem
import com.lagradost.cloudstream3.desktop.explore.ui.ExploreProviderDialog
import com.lagradost.cloudstream3.desktop.ui.screens.person.model.FilmographyCategory
import com.lagradost.cloudstream3.desktop.ui.screens.person.model.PersonDetail
import com.lagradost.cloudstream3.desktop.ui.screens.person.model.PersonMediaCredit
import java.time.LocalDate
import java.time.Period

@Composable
fun PersonScreen(
    name: String,
    image: String? = null,
    tmdbId: Int? = null,
    onBack: () -> Unit,
    onNavigate: (Config) -> Unit,
    viewModel: PersonViewModel = remember { PersonViewModel() },
) {
    val uiState by viewModel.uiState.collectAsState()
    val theme = LocalDesktopTheme.current

    LaunchedEffect(name, tmdbId) {
        viewModel.loadPerson(name, tmdbId)
    }

    DesktopDrilldownScaffold(
        onBack = onBack,
        dialogs = {
            val selectedCredit = uiState.selectedCreditForMatch
            if (selectedCredit != null) {
                val exploreItem = remember(selectedCredit) {
                    ExploreItem(
                        id = selectedCredit.tmdbId.toString(),
                        type = if (selectedCredit.mediaType == TvType.TvSeries) "series" else "movie",
                        name = selectedCredit.title,
                        posterUrl = selectedCredit.posterUrl,
                        backgroundUrl = selectedCredit.backdropUrl,
                        releaseYear = selectedCredit.releaseYear,
                        description = selectedCredit.overview,
                        rating = selectedCredit.voteAverage,
                    )
                }
                ExploreProviderDialog(
                    item = exploreItem,
                    matches = uiState.providerMatches,
                    isSearching = uiState.isSearchingProviders,
                    characterOrJob = selectedCredit.characterOrJob,
                    onDismissRequest = { viewModel.onEvent(PersonUiEvent.CloseProviderPicker) },
                    onOpenDetails = { providerName, url, title ->
                        viewModel.onEvent(PersonUiEvent.CloseProviderPicker)
                        onNavigate(
                            Config.Details(
                                providerName = providerName,
                                url = url,
                                preloadedName = title,
                                preloadedPoster = selectedCredit.posterUrl,
                                preloadedBg = selectedCredit.backdropUrl,
                            ),
                        )
                    },
                    onOpenAddonMode = { item ->
                        viewModel.onEvent(PersonUiEvent.CloseProviderPicker)
                        onNavigate(
                            Config.Details(
                                providerName = "Stremio",
                                url = "stremio://${item.type}/${item.id}",
                                preloadedName = item.name,
                                preloadedPoster = item.posterUrl,
                                preloadedBg = item.backgroundUrl,
                            ),
                        )
                    },
                )
            }
        },
    ) {
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = theme.Accent,
                    modifier = Modifier.size(48.dp),
                )
            }
        } else if (uiState.error != null && uiState.personDetail == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = theme.TextMuted.copy(alpha = 0.5f),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = uiState.error ?: "No information available",
                    style = MaterialTheme.typography.titleMedium,
                    color = theme.TextMuted,
                )
                Spacer(modifier = Modifier.height(16.dp))
                com.lagradost.cloudstream3.desktop.ui.components.DesktopButton(
                    onClick = { viewModel.onEvent(PersonUiEvent.Retry) },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.Accent),
                ) {
                    Text("Retry", color = Color.White)
                }
            }
        } else {
            val detail = uiState.personDetail ?: return@DesktopDrilldownScaffold
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    if (maxWidth >= 900.dp) {
                        // Desktop Split-View: Generous horizontal space with natural flow & boundary bar
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(28.dp),
                        ) {
                            // Left Sidebar: Centered portrait, justified bio, generous breathing space
                            PersonHeroSidebar(
                                detail = detail,
                                fallbackImage = image,
                                isScrollable = true,
                                modifier = Modifier
                                    .width(415.dp)
                                    .fillMaxHeight(),
                            )

                            // Slim Vertical Boundary Bar
                            VerticalDivider(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(vertical = 12.dp),
                                color = Color.White.copy(alpha = 0.08f),
                                thickness = 1.dp,
                            )

                            // Right Area: Filmography Header & Grid
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            ) {
                                FilmographySection(
                                    uiState = uiState,
                                    onSelectCategory = { viewModel.onEvent(PersonUiEvent.SelectCategory(it)) },
                                    onCreditClick = { viewModel.onEvent(PersonUiEvent.SelectCreditForMatch(it)) },
                                )
                            }
                        }
                    } else {
                        // Compact Vertical Scroll
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(28.dp),
                        ) {
                            PersonHeroSidebar(
                                detail = detail,
                                fallbackImage = image,
                                isScrollable = false,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            FilmographySection(
                                uiState = uiState,
                                onSelectCategory = { viewModel.onEvent(PersonUiEvent.SelectCategory(it)) },
                                onCreditClick = { viewModel.onEvent(PersonUiEvent.SelectCreditForMatch(it)) },
                            )
                        }
                    }
                }
            }
        }
    }

@Composable
private fun PersonHeroSidebar(
    detail: PersonDetail,
    fallbackImage: String?,
    isScrollable: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val theme = LocalDesktopTheme.current

    val scrollbarInteractionSource = remember { MutableInteractionSource() }
    val isScrollbarHovered by scrollbarInteractionSource.collectIsHoveredAsState()
    val isScrolling = scrollState.isScrollInProgress
    var isScrollbarVisible by remember { mutableStateOf(false) }

    LaunchedEffect(isScrolling, isScrollbarHovered) {
        if (isScrolling || isScrollbarHovered) {
            isScrollbarVisible = true
        } else {
            kotlinx.coroutines.delay(1000)
            isScrollbarVisible = false
        }
    }

    val scrollbarAlpha by animateFloatAsState(
        targetValue = if (isScrollbarVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "person_scrollbar_alpha",
    )

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isScrollable) Modifier.verticalScroll(scrollState) else Modifier)
                .padding(end = if (isScrollable) 14.dp else 0.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // High-res Portrait (Centered with Soft Border & subtle elevation)
            val profileShapes = com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopProfileShapes.current
            Surface(
                shape = profileShapes.hero,
                color = Color(0xFF141416),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                modifier = Modifier
                    .width(240.dp)
                    .aspectRatio(profileShapes.portraitAspectRatio),
            ) {
                val photoUrl = detail.profileUrl ?: fallbackImage
                if (!photoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = photoUrl,
                        contentDescription = detail.name,
                        contentScale = ContentScale.Crop,
                        alignment = profileShapes.portraitAlignment,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(76.dp),
                            tint = theme.TextMuted.copy(alpha = 0.4f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Name (Centered)
            Text(
                text = detail.name,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = theme.TextPrimary,
                textAlign = TextAlign.Center,
            )

            // Slim Horizontal Boundary Bar
            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White.copy(alpha = 0.08f),
                thickness = 1.dp,
            )
            Spacer(modifier = Modifier.height(20.dp))

            // Content Block: Personal Info Specs
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = "Personal Info",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = theme.TextPrimary,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!detail.birthday.isNullOrBlank()) {
                        DetailRow(label = "Born", value = detail.birthday)
                    }

                    val age = calculateAge(detail.birthday, detail.deathday)
                    if (age != null) {
                        val isDeceased = !detail.deathday.isNullOrBlank()
                        val ageLabel = if (isDeceased) "Age at Death" else "Age"
                        val ageValue = "$age years old"
                        DetailRow(label = ageLabel, value = ageValue)
                    }

                    if (!detail.deathday.isNullOrBlank()) {
                        DetailRow(label = "Died", value = detail.deathday)
                    }

                    if (!detail.placeOfBirth.isNullOrBlank()) {
                        DetailRow(label = "Birthplace", value = detail.placeOfBirth)
                    }

                    DetailRow(
                        label = "Credits",
                        value = "${detail.movieCredits.size} Movies • ${detail.tvCredits.size} TV Shows",
                    )
                }
            }

            // Biography Section (Clean left-aligned typography with slim boundary bar)
            if (!detail.biography.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White.copy(alpha = 0.08f),
                    thickness = 1.dp,
                )
                Spacer(modifier = Modifier.height(20.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = "Biography",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = theme.TextPrimary,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val cleanBio = remember(detail.biography) {
                        detail.biography.replace("\r\n", "\n").trim()
                    }

                    Text(
                        text = cleanBio,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = 22.sp,
                            letterSpacing = 0.15.sp,
                        ),
                        color = theme.TextMuted,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }

        // Dedicated Slim Desktop Scrollbar for the sidebar (Auto-hides when idle)
        if (isScrollable) {
            DesktopVerticalScrollbar(
                scrollState = scrollState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 6.dp)
                    .graphicsLayer { alpha = scrollbarAlpha }
                    .hoverable(scrollbarInteractionSource),
                style = defaultScrollbarStyle().copy(
                    thickness = 5.dp,
                    shape = RoundedCornerShape(2.5.dp),
                    unhoverColor = Color.White.copy(alpha = 0.35f),
                    hoverColor = theme.Accent,
                ),
            )
        }
    }
}

private fun calculateAge(birthDateStr: String?, deathDateStr: String? = null): String? {
    if (birthDateStr.isNullOrBlank()) return null
    return try {
        val birthDate = LocalDate.parse(birthDateStr.trim())
        val endDate = if (!deathDateStr.isNullOrBlank()) {
            LocalDate.parse(deathDateStr.trim())
        } else {
            LocalDate.now()
        }
        val years = Period.between(birthDate, endDate).years
        if (years >= 0) years.toString() else null
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val theme = LocalDesktopTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = theme.TextMuted.copy(alpha = 0.65f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal),
            color = theme.TextPrimary,
        )
    }
}

@Composable
private fun FilmographySection(
    uiState: PersonUiState,
    onSelectCategory: (FilmographyCategory) -> Unit,
    onCreditClick: (PersonMediaCredit) -> Unit,
) {
    val detail = uiState.personDetail ?: return
    val theme = LocalDesktopTheme.current

    Column(modifier = Modifier.fillMaxSize()) {
        // Section Header Row: Title & count on left, Category Filter Tabs on right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Filmography",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = theme.TextPrimary,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White.copy(alpha = 0.08f),
                ) {
                    Text(
                        text = "${detail.allCredits.size} titles",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = theme.TextMuted,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            // Category Filter Tabs
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryTabPill(
                    title = "All",
                    count = detail.allCredits.size,
                    isSelected = uiState.selectedCategory == FilmographyCategory.ALL,
                    onClick = { onSelectCategory(FilmographyCategory.ALL) },
                )
                CategoryTabPill(
                    title = "Movies",
                    count = detail.movieCredits.size,
                    isSelected = uiState.selectedCategory == FilmographyCategory.MOVIES,
                    onClick = { onSelectCategory(FilmographyCategory.MOVIES) },
                )
                CategoryTabPill(
                    title = "TV Series",
                    count = detail.tvCredits.size,
                    isSelected = uiState.selectedCategory == FilmographyCategory.TV_SHOWS,
                    onClick = { onSelectCategory(FilmographyCategory.TV_SHOWS) },
                )
            }
        }

        // Animated Filmography Grid
        AnimatedContent(
            targetState = uiState.selectedCategory,
            transitionSpec = {
                (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.98f))
                    .togetherWith(fadeOut(tween(120)))
            },
            label = "FilmographyCategoryTransition",
            modifier = Modifier.fillMaxSize(),
        ) { targetCategory ->
            val displayedCredits = when (targetCategory) {
                FilmographyCategory.ALL -> detail.allCredits
                FilmographyCategory.MOVIES -> detail.movieCredits
                FilmographyCategory.TV_SHOWS -> detail.tvCredits
            }

            if (displayedCredits.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No credits found in this category",
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.TextMuted,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(displayedCredits, key = { "${it.mediaType}-${it.tmdbId}" }) { credit ->
                        FilmographyCreditCard(
                            credit = credit,
                            onClick = { onCreditClick(credit) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTabPill(
    title: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalDesktopTheme.current

    val pillBg by animateColorAsState(
        targetValue = if (isSelected) theme.Accent else Color.White.copy(alpha = 0.05f),
        animationSpec = tween(180),
        label = "pillBg",
    )
    val pillBorder by animateColorAsState(
        targetValue = if (isSelected) theme.Accent else Color.White.copy(alpha = 0.08f),
        animationSpec = tween(180),
        label = "pillBorder",
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else theme.TextPrimary,
        animationSpec = tween(180),
        label = "textColor",
    )

    val chipInteraction = remember { MutableInteractionSource() }
    val pillShape = com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes.current.compact
    Surface(
        onClick = onClick,
        interactionSource = chipInteraction,
        shape = pillShape,
        color = pillBg,
        border = BorderStroke(1.dp, pillBorder),
        modifier = Modifier.desktopInteractiveFocus(interactionSource = chipInteraction, shape = pillShape),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
                color = textColor,
            )
            Surface(
                shape = CircleShape,
                color = if (isSelected) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (isSelected) Color.White else theme.TextMuted,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun FilmographyCreditCard(
    credit: PersonMediaCredit,
    onClick: () -> Unit,
) {
    val theme = LocalDesktopTheme.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsKeyboardFocusedAsState()
    val isActiveVisual = isHovered || isFocused
    val cardShape = RoundedCornerShape(12.dp)

    val scale by animateFloatAsState(
        targetValue = if (isActiveVisual) 1.03f else 1.0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "creditCardScale",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .desktopInteractiveFocus(interactionSource = interactionSource, shape = cardShape, showRing = false)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
    ) {
        // Poster Box
        Surface(
            shape = cardShape,
            color = Color(0xFF141416),
            border = BorderStroke(
                1.dp,
                if (isActiveVisual) theme.Accent.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.08f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .desktopFocusRing(isFocused = isFocused, shape = cardShape),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (!credit.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = credit.posterUrl,
                        contentDescription = credit.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF181818)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (credit.mediaType == TvType.TvSeries) Icons.Default.Tv else Icons.Default.Movie,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }

                // Top Media Type / Year Badge
                if (!credit.releaseYear.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.75f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                    ) {
                        Text(
                            text = credit.releaseYear,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                // Rating Badge
                if (credit.voteAverage != null && credit.voteAverage > 0) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.75f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = Color(0xFFFFC107),
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = String.format(java.util.Locale.US, "%.1f", credit.voteAverage),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Title
        Text(
            text = credit.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (isHovered) theme.Accent else theme.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Character / Role
        if (!credit.characterOrJob.isNullOrBlank()) {
            Text(
                text = "as ${credit.characterOrJob}",
                style = MaterialTheme.typography.bodySmall,
                color = theme.TextMuted.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
