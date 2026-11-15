package de.kjell.zencompanion.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.EssentialsColumns
import de.kjell.zencompanion.data.ToolbarPlacement
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.util.Haptics

/** Placeholder fill: the app's ink at low opacity, so it reads as "content goes here". */
private const val SkeletonAlpha = 0.09f

/** Six tiles, laid out like the real grid for the configured column count. */
private const val SkeletonEssentialsCount = 6

/** The demo entry gesture is the same as the old landing screen's. */
private const val DemoTaps = 5
private const val DemoTapWindowMs = 2_000L

/**
 * The home screen without an account: the same layout and toolbar placement as
 * the spaces screen, six essentials and four tab rows as a wireframe, and the
 * sign-in prompt below them. No space switcher: it needs an account.
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
        // reaches the prompt.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(4.dp))
            SkeletonEssentials(columns = essentialsColumns)
            Spacer(Modifier.height(12.dp))
            SkeletonTabs()
            Spacer(Modifier.height(28.dp))
            SignInPrompt(
                sessionNotice = sessionNotice,
                onSignIn = onSignIn,
                onOpenHelp = onOpenHelp,
                onEnterDemo = onEnterDemo,
            )
            Spacer(Modifier.height(18.dp))
        }

        if (toolbarPlacement == ToolbarPlacement.BOTTOM) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding()) {
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
        // A short last row stretches, like the real grid's.
        SkeletonEssentialsCount.let { count ->
            (0 until count).chunked(columnCount.coerceAtLeast(1))
        }.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(row.size) {
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
        SkeletonTabRow(titleWidth = 0.52f)
        SkeletonTabRow(titleWidth = 0.34f)
        SkeletonTabRow(titleWidth = 0.60f)
        SkeletonTabRow(titleWidth = 0.42f, folder = true)
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

/**
 * The prompt under the skeleton rows: one line of title, a short pill button
 * and the help link, centred and each narrower than the last, so the three
 * read as one quiet step down. The title keeps the five-tap demo entry.
 */
@Composable
private fun SignInPrompt(
    @StringRes sessionNotice: Int?,
    onSignIn: () -> Unit,
    onOpenHelp: () -> Unit,
    onEnterDemo: () -> Unit,
) {
    val context = LocalContext.current
    val zen = LocalZenColors.current
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.signed_out_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = zen.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .semantics { heading() }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { registerDemoTap() },
                ),
        )

        if (sessionNotice != null) {
            Text(
                text = stringResource(sessionNotice),
                style = MaterialTheme.typography.bodySmall,
                color = zen.ink.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Button(
            onClick = onSignIn,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = zen.ink, contentColor = zen.paper),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 11.dp),
            modifier = Modifier
                .padding(top = 16.dp)
                .heightIn(min = 44.dp),
        ) {
            Text(
                text = stringResource(R.string.signed_out_sign_in),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }

        TextButton(onClick = onOpenHelp, modifier = Modifier.padding(top = 2.dp)) {
            Text(
                text = stringResource(R.string.signin_help_link),
                style = MaterialTheme.typography.labelMedium,
                color = zen.ink.copy(alpha = 0.5f),
            )
        }
    }
}
