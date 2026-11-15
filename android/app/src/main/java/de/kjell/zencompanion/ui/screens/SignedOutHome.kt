package de.kjell.zencompanion.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.EssentialsColumns
import de.kjell.zencompanion.data.ToolbarPlacement
import de.kjell.zencompanion.ui.components.ZenMark
import de.kjell.zencompanion.ui.components.openURLExternally
import de.kjell.zencompanion.ui.theme.LiftCard
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.util.Haptics

/** Placeholder fill: the app's ink at low opacity, so it reads as "content goes here". */
private const val SkeletonAlpha = 0.09f

/** Enough essentials to give the configured column count two full rows. */
private const val SkeletonEssentialsCount = 8
private const val SkeletonEssentialsRows = 2

/** The demo entry gesture is the same as the old landing screen's. */
private const val DemoTaps = 5
private const val DemoTapWindowMs = 2_000L

/**
 * The home screen without an account: the same layout and toolbar placement as
 * the spaces screen, its account content as a wireframe, and the sign-in
 * prompt in the content area between the essentials and the tab rows.
 */
@Composable
internal fun SignedOutHome(
    essentialsColumns: EssentialsColumns,
    toolbarPlacement: ToolbarPlacement,
    actionBar: @Composable () -> Unit,
    @StringRes sessionNotice: Int?,
    onSignIn: () -> Unit,
    onOpenHelp: () -> Unit,
    onEnterDemo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        if (toolbarPlacement == ToolbarPlacement.TOP) {
            actionBar()
        }

        // Scrolls like the real content, so the largest font scale still
        // reaches the card and every row.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(4.dp))
            SkeletonEssentials(columns = essentialsColumns)
            Spacer(Modifier.height(20.dp))
            SignInPromptCard(
                sessionNotice = sessionNotice,
                onSignIn = onSignIn,
                onOpenHelp = onOpenHelp,
                onEnterDemo = onEnterDemo,
            )
            Spacer(Modifier.height(20.dp))
            SkeletonTabs()
            Spacer(Modifier.height(10.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SkeletonSpaceSwitcher()
            if (toolbarPlacement == ToolbarPlacement.BOTTOM) {
                actionBar()
            }
        }
    }
}

/** Rounded squares where the essentials grid is, in the configured shape. */
@Composable
private fun SkeletonEssentials(columns: EssentialsColumns) {
    val ink = LocalZenColors.current.ink
    val columnCount = columns.columnCountFor(SkeletonEssentialsCount)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(SkeletonEssentialsRows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(columnCount) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(ink.copy(alpha = SkeletonAlpha)),
                    )
                }
            }
        }
    }
}

/** Rows the size of real pinned tabs and folders, plus the normal-tab break. */
@Composable
private fun SkeletonTabs() {
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { },
    ) {
        SkeletonTabRow(titleWidth = 0.62f)
        SkeletonTabRow(titleWidth = 0.44f)
        SkeletonTabRow(titleWidth = 0.52f, folder = true)
        SkeletonTabRow(titleWidth = 0.36f)
        SkeletonTabDivider()
        SkeletonTabRow(titleWidth = 0.58f)
        SkeletonTabRow(titleWidth = 0.40f)
    }
}

/** One tab row: favicon slot, then a rounded bar where the title is. */
@Composable
private fun SkeletonTabRow(titleWidth: Float, folder: Boolean = false) {
    val ink = LocalZenColors.current.ink
    val fill = ink.copy(alpha = SkeletonAlpha)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (folder) {
            SkeletonFolderGlyph(fill)
        } else {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(fill),
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(titleWidth)
                .height(if (folder) 14.dp else 12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(fill),
        )
        Spacer(Modifier.weight(1f - titleWidth))
    }
}

/** A folder silhouette in the icon slot a folder row uses. */
@Composable
private fun SkeletonFolderGlyph(fill: Color) {
    Box(Modifier.size(28.dp)) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .size(width = 26.dp, height = 18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(fill),
        )
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(x = 3.dp, y = 3.dp)
                .size(width = 12.dp, height = 6.dp)
                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                .background(fill),
        )
    }
}

@Composable
private fun SkeletonTabDivider() {
    val ink = LocalZenColors.current.ink
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .height(1.dp)
            .background(ink.copy(alpha = 0.12f)),
    )
}

/** Where the space switcher is: two placeholder pills, not tappable. */
@Composable
private fun SkeletonSpaceSwitcher() {
    val ink = LocalZenColors.current.ink
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(ink.copy(alpha = 0.16f)),
        )
        Box(
            Modifier
                .size(width = 36.dp, height = 22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(ink.copy(alpha = SkeletonAlpha)),
        )
    }
}

/**
 * The card in the content area: what the account would fill this screen with,
 * the way to sign in, and the same five-tap demo entry the landing screen had.
 */
@Composable
private fun SignInPromptCard(
    @StringRes sessionNotice: Int?,
    onSignIn: () -> Unit,
    onOpenHelp: () -> Unit,
    onEnterDemo: () -> Unit,
) {
    val context = LocalContext.current
    var demoTapCount by remember { mutableIntStateOf(0) }
    var lastDemoTap by remember { mutableLongStateOf(0L) }

    fun registerDemoTap() {
        val now = System.currentTimeMillis()
        if (now - lastDemoTap > DemoTapWindowMs) demoTapCount = 0
        lastDemoTap = now
        demoTapCount += 1
        if (demoTapCount < DemoTaps) return
        demoTapCount = 0
        Haptics.perform(context, Haptics.Kind.CONFIRM)
        onEnterDemo()
    }

    val logoHint = stringResource(R.string.demo_logo_hint)
    val demoTap = Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        role = Role.Button,
        onClickLabel = logoHint,
        onClick = { registerDemoTap() },
    )

    LiftCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ZenMark(size = 44.dp, modifier = demoTap)

            Text(
                text = stringResource(R.string.signed_out_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = demoTap,
            )

            Text(
                text = stringResource(R.string.signed_out_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (sessionNotice != null) {
                Text(
                    text = stringResource(sessionNotice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }

            Button(
                onClick = onSignIn,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_sign_in),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }

            // Stacked rather than side by side: at the largest font scale two
            // links in one row would not fit the card.
            TextButton(onClick = onOpenHelp) {
                Text(
                    text = stringResource(R.string.signin_help_link),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { openURLExternally(context, Legal.publicPolicyURL) },
            ) {
                Text(
                    text = stringResource(R.string.legal_privacy),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
