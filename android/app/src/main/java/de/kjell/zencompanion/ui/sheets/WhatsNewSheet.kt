package de.kjell.zencompanion.ui.sheets

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.WhatsNewRelease
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.liftCard

/**
 * Material 3 What's New sheet: one short, scrollable release note with a
 * brand panel of three fanned phone screenshots, a short row of feature pills
 * and a pinned Done button. All type is sized in sp so font scaling is respected; the pills
 * wrap onto more lines at large font scales and the content scrolls instead
 * of clipping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(onDismiss: () -> Unit) {
    val zen = LocalZenColors.current

    ZenSheet(
        onDismiss = onDismiss,
        skipPartiallyExpanded = true,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(
                        color = zen.ink.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    CloseButton(onDismiss)
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = stringResource(WhatsNewRelease.eyebrowRes),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                    color = zen.coral,
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = stringResource(WhatsNewRelease.headlineRes),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = zen.ink,
                    modifier = Modifier.semantics { heading() },
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = stringResource(WhatsNewRelease.subtitleRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = zen.ink.copy(alpha = 0.65f),
                )

                Spacer(Modifier.height(24.dp))

                Hero()

                Spacer(Modifier.height(24.dp))

                Features()

                Spacer(Modifier.height(24.dp))
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 4.dp, bottom = 20.dp)
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Text(
                    text = stringResource(R.string.whats_new_done),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    val zen = LocalZenColors.current
    IconButton(onClick = onClose) {
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = stringResource(R.string.common_close),
            tint = zen.ink.copy(alpha = 0.65f),
        )
    }
}

/**
 * The brand-coloured panel with three phone screenshots: one upright in the
 * middle and two behind it leaning outwards. The screenshots are decorative;
 * the panel carries one description for TalkBack. Android's elevation shadow
 * has no vertical-offset control, so it is the closest match to the design.
 */
@Composable
private fun Hero() {
    val zen = LocalZenColors.current
    val description = stringResource(WhatsNewRelease.heroDescriptionRes)
    val cards = WhatsNewRelease.heroCards

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(zen.coral)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        HeroCard(
            cards.leading,
            height = 216.dp,
            cornerRadius = 14.dp,
            modifier = Modifier.offset(x = (-104).dp, y = 10.dp).rotate(-9f),
        )
        HeroCard(
            cards.trailing,
            height = 216.dp,
            cornerRadius = 14.dp,
            modifier = Modifier.offset(x = 104.dp, y = 10.dp).rotate(9f),
        )
        HeroCard(cards.center, height = 252.dp, cornerRadius = 16.dp, elevation = 15.dp)
    }
}

/** One phone screenshot, shown at the screenshot's own aspect ratio. */
@Composable
private fun HeroCard(
    @DrawableRes image: Int,
    height: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    elevation: Dp = 8.dp,
) {
    val painter = painterResource(image)
    val size = painter.intrinsicSize
    val shape = RoundedCornerShape(cornerRadius)
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .height(height)
            .aspectRatio(size.width / size.height)
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.28f),
                spotColor = Color.Black.copy(alpha = 0.28f),
            )
            .clip(shape),
    )
}

/** A caption and the remaining features as small pills that wrap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Features() {
    val zen = LocalZenColors.current

    Text(
        text = stringResource(WhatsNewRelease.featuresHeadingRes),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        color = zen.ink.copy(alpha = 0.65f),
        modifier = Modifier.semantics { heading() },
    )

    Spacer(Modifier.height(10.dp))

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WhatsNewRelease.items.forEach { item ->
            Text(
                text = stringResource(item.titleRes),
                fontSize = 14.sp,
                color = zen.ink,
                modifier = Modifier
                    .liftCard(CircleShape)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}
