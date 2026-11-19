package de.kjell.zencompanion.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.components.ZenSpaceGradientBackground
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenColors
import de.kjell.zencompanion.ui.theme.ZenLayout
import de.kjell.zencompanion.ui.theme.ZenTheme
import de.kjell.zencompanion.ui.theme.ZenPrimaryButton
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 3

/**
 * The first-run onboarding: three pages, one headline each (the spaces,
 * saving from other apps, the browser). Skip at the top, the dots and one
 * button at the bottom. Mirrors iOS `OnboardingView`.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val zen = LocalZenColors.current
    val pager = rememberPagerState { PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val isLastPage = pager.currentPage == PAGE_COUNT - 1
    val skipAlpha by animateFloatAsState(if (isLastPage) 0f else 1f, label = "skip")

    // Back steps through the pages; on the first one it leaves the app as usual.
    BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }

    val wash = zen.coral.copy(alpha = if (zen.isDark) 0.16f else 0.09f).compositeOver(zen.paper)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to wash, 0.5f to zen.paper))
            // Nothing below takes a touch while the onboarding is up.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .wrapContentWidth()
                .widthIn(max = ZenLayout.MaxContentWidth),
        ) {
            // Skip floats over the pages, so a card's shadow can reach up under
            // it instead of being cut at the pages' top edge.
            Box(Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(48.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clearAndSetSemantics {},
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        when (page) {
                            0 -> SpacesIllustration(zen)
                            1 -> ShareIllustration(zen)
                            else -> BrowserIllustration()
                        }
                    }
                    Text(
                        stringResource(
                            when (page) {
                                0 -> R.string.onboarding_spaces_title
                                1 -> R.string.onboarding_share_title
                                else -> R.string.onboarding_browser_title
                            },
                        ),
                        color = zen.ink,
                        fontSize = 30.sp,
                        lineHeight = 35.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .padding(top = 20.dp)
                            .semantics { heading() },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.End) {
                Row(
                    Modifier
                        .alpha(skipAlpha)
                        .clickable(enabled = !isLastPage, onClick = onFinish)
                        .padding(horizontal = 20.dp)
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.onboarding_skip),
                        color = zen.ink60,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = zen.ink60,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            }

            val pageLabel = stringResource(R.string.onboarding_page, pager.currentPage + 1, PAGE_COUNT)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 22.dp, bottom = 28.dp)
                    .semantics { contentDescription = pageLabel },
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                repeat(PAGE_COUNT) { index ->
                    val selected = index == pager.currentPage
                    val width by animateDpAsState(if (selected) 18.dp else 6.dp, label = "dot")
                    Box(
                        Modifier
                            .width(width)
                            .height(6.dp)
                            .background(zen.ink.copy(alpha = if (selected) 1f else 0.22f), CircleShape),
                    )
                }
            }

            ZenPrimaryButton(
                text = stringResource(if (isLastPage) R.string.onboarding_start else R.string.onboarding_continue),
                onClick = {
                    if (isLastPage) {
                        onFinish()
                    } else {
                        scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }
                },
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp),
            )
        }
    }
}

// region Illustrations

/** The made-up café guide's favicon: a cup on a coffee-brown tile. */
@Composable
private fun CafeFavicon(size: Dp) {
    Box(
        Modifier.size(size).background(Color(0xFF8C5C3D), RoundedCornerShape(size * 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.zen_cafe),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(size * 0.58f),
        )
    }
}

/** Made-up space themes, so the drawn screens carry colour like a themed space. */
private val SpacesTheme = ZenSpaces.ZenSpaceTheme.fromGradientColors(listOf("#A9C9B8", "#E8D5BF"), opacity = 0.45)
private val WorkTheme = ZenSpaces.ZenSpaceTheme.fromGradientColors(listOf("#B7C7D9", "#E6D3C4"), opacity = 0.45)

/** A card drawn on a space theme, with the app's own space backdrop. */
@Composable
private fun ThemedCard(
    zen: ZenColors,
    theme: ZenSpaces.ZenSpaceTheme,
    radius: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .shadow(24.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.18f))
            .clip(shape)
            .border(0.6.dp, Color.Black.copy(alpha = 0.06f), shape),
    ) {
        ZenSpaceGradientBackground(theme = theme, darkenDots = zen.isDark, modifier = Modifier.matchParentSize())
        content()
    }
}

private fun Modifier.liftTile(zen: ZenColors, radius: Dp = 12.dp): Modifier =
    background(zen.lift, RoundedCornerShape(radius))

private val Blue = Color(0xFF3B82F6)

private val SpacesCardWidth = 292.dp
private val SpacesCardHeight = 452.dp

/** Room around a card so a fade or a scale never cuts its shadow. */
private val ShadowRoom = 28.dp

/**
 * The spaces screen in small, built like the real one: essentials, a space
 * with its tabs, and at the bottom the space switcher and the action bar. It
 * shrinks to fit a short screen instead of being cut off.
 */
@Composable
private fun SpacesIllustration(zen: ZenColors) {
    // Centred in the space above the headline; scaled about its centre, so a
    // short screen shrinks it evenly toward the middle.
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val available = maxHeight - ShadowRoom * 2
        val scale = (available / SpacesCardHeight).coerceIn(0.5f, 1f)
        Box(
            Modifier
                .requiredSize(SpacesCardWidth, SpacesCardHeight)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            ThemedCard(zen, SpacesTheme, 34.dp, Modifier.fillMaxSize()) { SpacesCard(zen) }
        }
    }
}

@Composable
private fun SpacesCard(zen: ZenColors) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val essentials = listOf(
            R.drawable.onboarding_fav_discord,
            R.drawable.onboarding_fav_nytimes,
            R.drawable.onboarding_fav_chatgpt,
            R.drawable.onboarding_fav_reddit,
            R.drawable.onboarding_fav_github,
            R.drawable.onboarding_fav_youtube,
        )
        essentials.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { icon ->
                    Box(Modifier.weight(1f).height(46.dp).liftTile(zen), contentAlignment = Alignment.Center) {
                        Favicon(icon, 22.dp)
                    }
                }
            }
        }

        Row(
            Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                painterResource(R.drawable.zen_leaf),
                contentDescription = null,
                colorFilter = ColorFilter.tint(zen.coral),
                modifier = Modifier.size(20.dp),
            )
            Text("Personal", color = zen.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(start = 2.dp)) {
            TabRow(zen, R.drawable.onboarding_fav_chatgpt, "ChatGPT")
            TabRow(zen, R.drawable.onboarding_fav_youtube, "YouTube")
            TabRow(zen, R.drawable.onboarding_fav_googledocs, "Google Docs")
            Box(Modifier.fillMaxWidth().height(1.dp).background(zen.ink.copy(alpha = 0.15f)))
            TabRow(zen, R.drawable.zen_cafe, "Ten quiet cafés in Lisbon")
        }

        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
        ) {
            SpaceIcon(zen, R.drawable.zen_leaf, active = true)
            SpaceIcon(zen, R.drawable.zen_briefcase, active = false)
            SpaceIcon(zen, R.drawable.zen_music, active = false)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChromeTile(zen) { Icon(Icons.Filled.Archive, null, tint = zen.ink70, modifier = Modifier.size(17.dp)) }
            Row(
                Modifier.weight(1f).height(40.dp).liftTile(zen, 14.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Search, null, tint = zen.ink55, modifier = Modifier.size(16.dp))
                Text("Search…", color = zen.ink55, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            ChromeTile(zen) { Icon(Icons.Filled.Settings, null, tint = zen.ink70, modifier = Modifier.size(17.dp)) }
        }
    }
}

@Composable
private fun Favicon(icon: Int, size: Dp) {
    Image(
        painterResource(icon),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)),
    )
}

@Composable
private fun SpaceIcon(zen: ZenColors, icon: Int, active: Boolean) {
    Image(
        painterResource(icon),
        contentDescription = null,
        colorFilter = ColorFilter.tint(if (active) zen.coral else zen.ink.copy(alpha = 0.3f)),
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun ChromeTile(zen: ZenColors, content: @Composable () -> Unit) {
    Box(Modifier.size(40.dp).liftTile(zen, 14.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun TabRow(zen: ZenColors, icon: Int, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon == R.drawable.zen_cafe) CafeFavicon(18.dp) else Favicon(icon, 18.dp)
        Text(title, color = zen.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val ShareWidth = 330.dp
/** The share sheet picture's own aspect ratio (1440 × 768). */
private const val SHARE_SHEET_RATIO = 1440f / 768f

/**
 * Sharing: Z-Sync's own save screen fills the space, and the system share
 * sheet sits over its lower part like the dialog it was picked from. The
 * whole picture fades out at the bottom.
 */
@Composable
private fun ShareIllustration(zen: ZenColors) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .width(ShareWidth + ShadowRoom * 2)
                .heightIn(max = 600.dp + ShadowRoom)
                .fillMaxHeight()
                // The fade covers the shadow's room too, so the shadow is not cut.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        // Short, so only the empty edge under the app names fades.
                        Brush.verticalGradient(0.95f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                }
                .padding(start = ShadowRoom, end = ShadowRoom, top = ShadowRoom),
            contentAlignment = Alignment.BottomCenter,
        ) {
            ThemedCard(zen, WorkTheme, 40.dp, Modifier.fillMaxSize()) { SaveScreen(zen) }
            Image(
                painterResource(R.drawable.onboarding_share_sheet),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(SHARE_SHEET_RATIO)
                    .shadow(16.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), clip = false),
            )
        }
    }
}

/**
 * Z-Sync's save screen as the share screen draws it: the page's favicon,
 * title and address, and the space it goes into.
 */
@Composable
private fun SaveScreen(zen: ZenColors) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Near the top, so it keeps clear of the share sheet below.
        Spacer(Modifier.height(52.dp))
        CafeFavicon(64.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            "Ten quiet cafés in Lisbon",
            color = zen.ink,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text("https://lisbon-guide.com/quiet-cafes", color = zen.ink55, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Row(
            Modifier
                .padding(top = 22.dp)
                .background(zen.lift, CircleShape)
                .border(0.6.dp, Color.Black.copy(alpha = 0.12f), CircleShape)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(
                painterResource(R.drawable.zen_briefcase),
                contentDescription = null,
                colorFilter = ColorFilter.tint(zen.coral),
                modifier = Modifier.size(18.dp),
            )
            Icon(Icons.Filled.UnfoldMore, null, tint = zen.ink50, modifier = Modifier.size(15.dp))
            Text("Work", color = zen.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        // The lower part is under the share sheet.
        Spacer(Modifier.height(ShareWidth / SHARE_SHEET_RATIO * 0.9f))
    }
}

/** The page at the size of a phone screen; drawn smaller as a whole. */
private val BrowserPageWidth = 390.dp
private val BrowserPageHeight = 720.dp
private const val BROWSER_MAX_SCALE = 0.68f

/**
 * The browser, drawn like the other pages: the café page from the share page,
 * open with the browser's real bar (the same [MiniBrowserBarPicture] code as
 * the browser), and two more open pages behind it as cards. The page is laid
 * out at phone size and shrunk as a whole, so the bar keeps its proportions.
 */
@Composable
private fun BrowserIllustration() {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val scale = ((maxHeight - ShadowRoom * 2) / BrowserPageHeight).coerceIn(0.35f, BROWSER_MAX_SCALE)
        Box(
            Modifier
                .requiredSize(BrowserPageWidth + 200.dp, BrowserPageHeight)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            contentAlignment = Alignment.Center,
        ) {
            val shift = 58.dp / BROWSER_MAX_SCALE
            val drop = 14.dp / BROWSER_MAX_SCALE
            TabSwitcher(Modifier.offset(x = -shift, y = drop).rotate(-8f))
            YouTubePage(Modifier.offset(x = shift, y = drop).rotate(8f))
            BrowserPage()
        }
    }
}

private val BackPageWidth = BrowserPageWidth * 0.9f
private val BackPageHeight = BrowserPageHeight * 0.88f

/** A page behind the one in front: white like a website, rounded like a card. */
@Composable
private fun BackCard(modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(44.dp)
    Box(
        modifier
            .size(BackPageWidth, BackPageHeight)
            .shadow(24.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.18f))
            .clip(shape)
            .background(Color.White)
            .border(0.6.dp, Color.Black.copy(alpha = 0.06f), shape),
    ) { content() }
}

@Composable
private fun TextLines(widths: List<Float>, height: Dp = 10.dp, color: Color = Color(0xFFDEDEDE)) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        widths.forEach { share ->
            Box(Modifier.fillMaxWidth(share).height(height).background(color, CircleShape))
        }
    }
}

/**
 * The open pages as the browser's switcher shows them, scrolled all the way
 * back: the first page in focus, named above, and the later ones running off
 * to the right on top of it.
 */
@Composable
private fun TabSwitcher(modifier: Modifier) {
    val zen = LocalZenColors.current
    val cardWidth = BackPageWidth * 0.6875f
    // The deck's spacing with the first card in focus, moved left so the card
    // shows beside the page in front.
    val lefts = listOf(0.07f, 0.57f, 1.71f)
    BackCard(modifier) {
        Box(Modifier.fillMaxSize().background(zen.background)) {
            lefts.forEachIndexed { index, left ->
                Box(Modifier.offset(x = BackPageWidth * left, y = 120.dp)) {
                    DeckHeader(index, Modifier.offset(x = 20.dp, y = (-30).dp))
                    Box(
                        Modifier
                            .size(cardWidth, 400.dp)
                            .shadow(14.dp, RoundedCornerShape(26.dp))
                            .clip(RoundedCornerShape(26.dp))
                            .background(Color.White),
                    ) { DeckCard(index) }
                }
            }
        }
    }
}

@Composable
private fun DeckHeader(index: Int, modifier: Modifier) {
    val zen = LocalZenColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (index) {
            0 -> Favicon(R.drawable.onboarding_fav_wikipedia, 22.dp)
            1 -> Favicon(R.drawable.onboarding_fav_youtube, 22.dp)
            else -> CafeFavicon(22.dp)
        }
        // Only the card in focus is named.
        if (index == 0) {
            Text(
                "Aurora – Wikipedia",
                color = zen.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
    }
}

/** A small picture of each open page. */
@Composable
private fun DeckCard(index: Int) {
    when (index) {
        0 -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Aurora", color = Color(0xFF141414), fontSize = 22.sp, fontFamily = FontFamily.Serif)
            Box(Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(4.dp)).background(AuroraBrush))
            TextLines(listOf(1f, 0.9f, 0.96f, 0.7f, 1f, 0.85f), height = 7.dp)
        }
        1 -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().height(135.dp).background(Brush.linearGradient(listOf(Color(0xFFF29E59), Color(0xFFC74761)))))
            Box(Modifier.padding(horizontal = 14.dp)) { TextLines(listOf(0.9f, 0.55f), height = 8.dp, color = Color(0xFFC7C7C7)) }
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(135.dp).background(Brush.linearGradient(listOf(Color(0xFF4085B8), Color(0xFF294D7A)))))
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(
                painterResource(R.drawable.onboarding_cafe),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(150.dp),
            )
            Text(
                "Ten quiet cafés in Lisbon",
                color = Color(0xFF1A1A1A),
                fontSize = 19.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            Box(Modifier.padding(horizontal = 14.dp)) { TextLines(listOf(1f, 0.92f, 0.97f, 0.6f), height = 7.dp) }
        }
    }
}

private val AuroraBrush = Brush.linearGradient(
    listOf(Color(0xFF0D1F38), Color(0xFF2EBF85), Color(0xFF734D9E)),
    start = androidx.compose.ui.geometry.Offset(0f, Float.POSITIVE_INFINITY),
    end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, 0f),
)

/** A video site: its header, a video with its title and channel, and the next. */
@Composable
private fun YouTubePage(modifier: Modifier) {
    val ink = Color(0xFF1A1A1A)
    BackCard(modifier) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Favicon(R.drawable.onboarding_fav_youtube, 30.dp)
                Text("YouTube", color = ink, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Search, null, tint = ink, modifier = Modifier.size(20.dp))
                Box(Modifier.size(26.dp).background(Color(0xFF8C6BD9), CircleShape))
            }
            Video(listOf(Color(0xFFF29E59), Color(0xFFC74761)), listOf(0.92f, 0.6f))
            Spacer(Modifier.height(18.dp))
            Video(listOf(Color(0xFF4085B8), Color(0xFF294D7A)), listOf(0.85f, 0.5f))
        }
    }
}

@Composable
private fun Video(colors: List<Color>, title: List<Float>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.fillMaxWidth().height(190.dp).background(Brush.linearGradient(colors)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(56.dp, 40.dp).background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(colors[1].copy(alpha = 0.8f), CircleShape))
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                TextLines(title, height = 11.dp, color = Color(0xFFC7C7C7))
                Box(Modifier.width(140.dp).height(9.dp).background(Color(0xFFE6E6E6), CircleShape))
            }
        }
    }
}

/** The café page with the browser's bar at its bottom. */
@Composable
private fun BrowserPage() {
    val shape = RoundedCornerShape(50.dp)
    // A white page: the bar follows the page, so it is light here whatever
    // the system theme, as in the browser.
    ZenTheme(darkTheme = false) {
        Column(
            Modifier
                .size(BrowserPageWidth, BrowserPageHeight)
                .shadow(34.dp, shape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.22f))
                .clip(shape)
                .background(Color.White)
                .border(0.6.dp, Color.Black.copy(alpha = 0.06f), shape),
        ) {
            Image(
                painterResource(R.drawable.onboarding_cafe),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(250.dp),
            )
            Column(
                Modifier.weight(1f).padding(start = 24.dp, end = 24.dp, top = 22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("GUIDE", color = Color(0xFF737373), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
                Text(
                    "Ten quiet cafés in Lisbon",
                    color = Color(0xFF1A1A1A),
                    fontSize = 31.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                )
                listOf(1f, 0.95f, 0.98f, 0.9f, 0.97f, 0.6f).forEach { width ->
                    Box(Modifier.fillMaxWidth(width).height(10.dp).background(Color(0xFFDDDDDD), CircleShape))
                }
            }
            MiniBrowserBarPicture(
                url = "https://lisbon-guide.com/quiet-cafes",
                title = "Ten quiet cafés in Lisbon",
                pageCount = 3,
                barColor = Color.White.nudgedForBar(),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

// endregion
