package de.kjell.zencompanion.ui.sheets

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.BuildConfig
import de.kjell.zencompanion.R
import de.kjell.zencompanion.ui.screens.Legal
import de.kjell.zencompanion.ui.theme.ZenListGroup
import de.kjell.zencompanion.ui.theme.ZenListItem
import de.kjell.zencompanion.ui.theme.ZenListValue

/** "Feedback": the two links that leave the app. */
@Composable
internal fun SettingsFeedbackSection() {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionLabel(stringResource(R.string.settings_feedback_section))

        ZenListGroup {
            LinkRow(
                label = stringResource(R.string.legal_feedback),
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(Legal.feedbackURL)),
                        )
                    }
                },
            )

            LinkRow(
                label = stringResource(R.string.legal_write_review),
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(Legal.writeReviewURL)),
                        )
                    }
                },
            )
        }
    }
}

/** "About": release notes and the privacy policy. */
@Composable
internal fun SettingsAboutSection(onOpenWhatsNew: () -> Unit) {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionLabel(stringResource(R.string.settings_about_section))

        ZenListGroup {
            // What's New — reopens the release notes for the current version.
            ZenListItem(
                headline = stringResource(R.string.settings_whats_new),
                onClick = onOpenWhatsNew,
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )

            LinkRow(
                label = stringResource(R.string.legal_privacy),
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(Legal.publicPolicyURL)),
                        )
                    }
                },
            )

            // App Version
            ZenListItem(
                headline = stringResource(R.string.settings_version),
                trailingContent = { ZenListValue(BuildConfig.VERSION_NAME) },
            )
        }
    }
}

/**
 * Disclaimer and icon attribution. The disclaimer leads: it is the legal
 * notice, so it sits above the attribution and reads one step larger.
 */
@Composable
internal fun SettingsFooter() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.home_disclaimer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.home_license_attribution),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

/** A row that opens a link outside the app. */
@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    ZenListItem(
        headline = label,
        onClick = onClick,
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}
