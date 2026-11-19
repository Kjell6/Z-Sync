package de.kjell.zencompanion.ui.sheets

import de.kjell.zencompanion.ui.theme.ZenLayout
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.BlockCategory
import de.kjell.zencompanion.data.ContentBlocking
import de.kjell.zencompanion.data.ContentBlockingSettings
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenListGroup
import de.kjell.zencompanion.ui.theme.ZenSwitchListItem

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

/** The three switches, in the grouped list style of the settings sheet. */
@Composable
private fun BlockingSwitches(
    isOn: (BlockCategory) -> Boolean,
    onChange: (BlockCategory, Boolean) -> Unit,
    isEnabled: (BlockCategory) -> Boolean = { true },
    showDetail: Boolean = true,
) {
    ZenListGroup {
        for (category in BlockCategory.entries) {
            val enabled = isEnabled(category)
            val detail = when {
                !enabled -> stringResource(R.string.blocking_off_in_settings)
                showDetail && category == BlockCategory.COOKIE_BANNERS ->
                    stringResource(R.string.blocking_cookie_banners_detail)
                else -> null
            }
            ZenSwitchListItem(
                headline = stringResource(category.title),
                supportingText = detail,
                icon = category.icon,
                checked = isOn(category),
                onCheckedChange = { onChange(category, it) },
                enabled = enabled,
            )
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
            .background(zen.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            // The same centred column as the home screen on a wide screen.
            .wrapContentWidth()
            .widthIn(max = ZenLayout.MaxContentWidth + 48.dp)
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
            shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(16.dp)),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(top = 16.dp),
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
