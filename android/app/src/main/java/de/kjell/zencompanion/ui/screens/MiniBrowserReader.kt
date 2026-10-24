package de.kjell.zencompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.ui.browser.ReaderArticle
import de.kjell.zencompanion.ui.browser.ReaderBlock
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenType

/**
 * The page as plain text, over the web pane and under the bar (port of iOS
 * `ReaderView`). The page stays loaded underneath, so closing it is instant.
 */
@Composable
internal fun MiniBrowserReader(
    article: ReaderArticle,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalZenColors.current
    val blocks = rememberReaderBlocks(article)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.paper),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "reader-header") {
            ReaderHeader(article = article, onClose = onClose)
        }
        itemsIndexed(blocks, key = { index, _ -> "reader-$index" }) { _, block ->
            ReaderBlockView(block)
        }
        item(key = "reader-footer") {
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ReaderHeader(article: ReaderArticle, onClose: () -> Unit) {
    val colors = LocalZenColors.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = article.site.ifEmpty { article.title },
                style = ZenType.rounded(12, FontWeight.SemiBold),
                color = colors.ink.copy(alpha = 0.45f),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .background(colors.lift, CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.common_done),
                    tint = colors.ink.copy(alpha = 0.6f),
                )
            }
        }
        if (article.title.isNotEmpty()) {
            Text(
                text = article.title,
                style = ZenType.rounded(26, FontWeight.Bold),
                color = colors.ink,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (article.byline.isNotEmpty()) {
            Text(
                text = article.byline,
                style = ZenType.rounded(13, FontWeight.Medium),
                color = colors.ink.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ReaderBlockView(block: ReaderBlock) {
    val colors = LocalZenColors.current
    when (block.kind) {
        "h1", "h2" -> Text(
            text = block.text,
            style = ZenType.rounded(20, FontWeight.SemiBold),
            color = colors.ink,
        )

        "h3", "h4", "h5", "h6" -> Text(
            text = block.text,
            style = ZenType.rounded(17, FontWeight.SemiBold),
            color = colors.ink,
        )

        "blockquote" -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(2.dp)
                    .height(24.dp)
                    .background(colors.coral.copy(alpha = 0.5f)),
            )
            Text(
                text = block.text,
                style = ZenType.rounded(16, FontWeight.Normal).copy(fontStyle = FontStyle.Italic),
                color = colors.ink.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 14.dp),
            )
        }

        "pre" -> Text(
            text = block.text,
            style = ZenType.rounded(13, FontWeight.Normal).copy(fontFamily = FontFamily.Monospace),
            color = colors.ink.copy(alpha = 0.85f),
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.lift, RoundedCornerShape(10.dp))
                .padding(12.dp),
        )

        "li" -> Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "•",
                style = ZenType.rounded(15, FontWeight.Bold),
                color = colors.coral.copy(alpha = 0.85f),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = block.text,
                style = ZenType.rounded(17, FontWeight.Normal),
                color = colors.ink.copy(alpha = 0.9f),
            )
        }

        else -> Text(
            text = block.text,
            style = ZenType.rounded(17, FontWeight.Normal).copy(lineHeight = 25.sp),
            color = colors.ink.copy(alpha = 0.9f),
        )
    }
}

/**
 * Drops a leading heading that just repeats the title, so the title is not
 * printed twice (same rule as the iOS reader).
 */
@Composable
private fun rememberReaderBlocks(article: ReaderArticle): List<ReaderBlock> {
    var items = article.blocks
    while (items.isNotEmpty()) {
        val first = items.first()
        if (first.kind != "h1" && first.kind != "h2") break
        if (!first.text.equals(article.title, ignoreCase = true)) break
        items = items.drop(1)
    }
    return items
}
