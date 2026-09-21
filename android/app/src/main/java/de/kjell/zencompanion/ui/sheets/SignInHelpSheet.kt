package de.kjell.zencompanion.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.ui.theme.LocalZenColors

/**
 * Port of the iOS `SignInHelpSheet`. Unlike iOS, Android does not hide the
 * Apple / Google sign-in options, so the "other sign-in options are not shown"
 * block has no Android equivalent; the Hide My Email block is Apple-specific.
 */
@Composable
fun SignInHelpSheet(
    onOpenMozilla: () -> Unit,
    onDone: () -> Unit,
) {
    ZenSheet(
        onDismiss = onDone,
        skipPartiallyExpanded = true,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 28.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.signin_help_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDone, modifier = Modifier.padding(end = 4.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.common_done),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HelpBlock(body = stringResource(R.string.signin_help_why))
            HelpBlock(
                title = stringResource(R.string.signin_help_passkey_title),
                body = stringResource(R.string.signin_help_passkey),
            )

            TextButton(onClick = onOpenMozilla) {
                Text(
                    text = stringResource(R.string.signin_help_open_mozilla),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

@Composable
private fun HelpBlock(title: String? = null, body: String) {
    val ink = LocalZenColors.current.ink
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = ink,
            )
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
            color = ink.copy(alpha = 0.8f),
        )
    }
}
