package de.kjell.zencompanion.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.ui.theme.LocalZenColors

/**
 * Port of `SyncSetupSheet` (iOS): step-by-step explanation of the two
 * requirements for pinned tabs and essentials to show up — the same Mozilla
 * account in Zen Browser, and the "Sync your sidebar across devices" switch.
 */
@Composable
fun SyncSetupSheet(
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = LocalZenColors.current

    ZenSheet(onDismiss = onDismiss, skipPartiallyExpanded = true) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.paper)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.sync_setup_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.common_done),
                        tint = colors.ink,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Text(
                    text = stringResource(R.string.sync_setup_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink.copy(alpha = 0.7f),
                )

                SyncSetupStep(
                    number = 1,
                    title = stringResource(R.string.sync_setup_step1_title),
                    body = stringResource(R.string.sync_setup_step1_body),
                )
                SyncSetupStep(
                    number = 2,
                    title = stringResource(R.string.sync_setup_step2_title),
                    body = stringResource(R.string.sync_setup_step2_body),
                )
                SyncSetupStep(
                    number = 3,
                    title = stringResource(R.string.sync_setup_step3_title),
                    body = stringResource(R.string.sync_setup_step3_body),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = colors.ink.copy(alpha = 0.4f),
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = stringResource(R.string.sync_setup_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.ink.copy(alpha = 0.55f),
                    )
                }

                Button(
                    onClick = onRefresh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.sync_setup_refresh),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SyncSetupStep(number: Int, title: String, body: String) {
    val colors = LocalZenColors.current

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(colors.coral),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.paper,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink.copy(alpha = 0.7f),
            )
        }
    }
}
