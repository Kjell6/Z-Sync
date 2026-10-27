package de.kjell.zencompanion.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.BlockCategory
import de.kjell.zencompanion.data.ContentBlocking
import de.kjell.zencompanion.data.ContentBlockingSettings
import de.kjell.zencompanion.ui.theme.LiftCard
import de.kjell.zencompanion.ui.theme.LocalZenColors

internal val BlockCategory.icon: ImageVector
    get() = when (this) {
        BlockCategory.ADS -> Icons.Outlined.PanTool
        BlockCategory.TRACKERS -> Icons.Outlined.VisibilityOff
        BlockCategory.COOKIE_BANNERS -> Icons.Outlined.Cookie
    }

internal val BlockCategory.title: Int
    get() = when (this) {
        BlockCategory.ADS -> R.string.blocking_ads
        BlockCategory.TRACKERS -> R.string.blocking_trackers
        BlockCategory.COOKIE_BANNERS -> R.string.blocking_cookie_banners
    }

/** The three switches, in the card style of the settings sheet. */
@Composable
private fun BlockingSwitches(
    isOn: (BlockCategory) -> Boolean,
    onChange: (BlockCategory, Boolean) -> Unit,
    isEnabled: (BlockCategory) -> Boolean = { true },
    showDetail: Boolean = true,
) {
    LiftCard(modifier = Modifier.fillMaxWidth()) {
        BlockCategory.entries.forEachIndexed { index, category ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
            val enabled = isEnabled(category)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onChange(category, !isOn(category)) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(category.title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val detail = when {
                        !enabled -> stringResource(R.string.blocking_off_in_settings)
                        showDetail && category == BlockCategory.COOKIE_BANNERS ->
                            stringResource(R.string.blocking_cookie_banners_detail)
                        else -> null
                    }
                    if (detail != null) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = isOn(category),
                    onCheckedChange = { onChange(category, it) },
                    enabled = enabled,
                )
            }
        }
    }
}

/** "Content blocking" in Settings: the switches for every site. */
@Composable
internal fun SettingsContentBlockingBody() {
    val settings by ContentBlocking.settings.collectAsState()
    val lastModified by ContentBlocking.lastModified.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BlockingSwitches(
            isOn = settings::isOn,
            onChange = { category, on -> ContentBlocking.update(settings.with(category, on)) },
        )
        val date = lastModified?.let(::listDate)
        Text(
            text = if (date != null) {
                stringResource(R.string.settings_content_blocking_caption, date)
            } else {
                stringResource(R.string.settings_content_blocking_caption_undated)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** `24 Oct 2026 21:50 UTC` from the list header becomes `24 Oct 2026`. */
internal fun listDate(lastModified: String): String? =
    lastModified.split(' ').filter { it.isNotEmpty() }.take(3).takeIf { it.size == 3 }?.joinToString(" ")

/**
 * The setup question: asked once, after sign-in or on the first launch with
 * content blocking. Every switch starts on; Continue saves them.
 */
@Composable
internal fun ContentBlockingSetupScreen() {
    var choice by remember { mutableStateOf(ContentBlockingSettings()) }
    val zen = LocalZenColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(zen.paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(28.dp))
            // No badge at the top: the title carries the screen, the same way
            // the What's New sheet opens on its title.
            Text(
                text = stringResource(R.string.blocking_setup_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.blocking_setup_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            BlockingSwitches(
                isOn = choice::isOn,
                onChange = { category, on -> choice = choice.with(category, on) },
            )
        }
        Button(
            onClick = { ContentBlocking.answerSetup(choice) },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(
                text = stringResource(R.string.blocking_setup_continue),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = stringResource(R.string.blocking_setup_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
