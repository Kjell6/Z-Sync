package de.kjell.zencompanion.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.ui.components.ZenMark
import de.kjell.zencompanion.util.Haptics

/** `Legal` constants for support & privacy. */
object Legal {
    const val supportEmail = "Support@Kjell.cc"
    const val publicPolicyURL =
        "https://github.com/Kjell6/Z-Sync/blob/main/docs/privacy.md"
    const val supportURL = "mailto:$supportEmail"
    const val feedbackURL = "https://github.com/Kjell6/Z-Sync/issues"
    const val writeReviewURL =
        "https://play.google.com/store/apps/details?id=de.kjell.zencompanion&showAllReviews=true"
}

/**
 * Native Material 3 Sign-In Landing Screen.
 */
@Composable
fun SignInLandingView(
    onOpenSignIn: () -> Unit,
    onOpenHelp: () -> Unit = {},
    onEnterDemo: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var demoTapCount by remember { mutableIntStateOf(0) }
    var lastDemoTap by remember { mutableLongStateOf(0L) }

    fun registerDemoTap() {
        val now = System.currentTimeMillis()
        if (now - lastDemoTap > 2_000L) demoTapCount = 0
        lastDemoTap = now
        demoTapCount += 1
        if (demoTapCount < 5) return
        demoTapCount = 0
        Haptics.perform(context, Haptics.Kind.CONFIRM)
        onEnterDemo()
    }

    val logoHint = stringResource(R.string.demo_logo_hint)

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.weight(1f))

        ZenMark(
            size = 72.dp,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = logoHint,
                onClick = { registerDemoTap() },
            ),
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = logoHint,
                onClick = { registerDemoTap() },
            ),
        )

        Text(
            text = stringResource(R.string.home_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onOpenSignIn,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(
                text = stringResource(R.string.home_sign_in),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        TextButton(onClick = onOpenHelp) {
            Text(
                text = stringResource(R.string.signin_help_link),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = stringResource(R.string.home_disclaimer),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )

        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Legal.publicPolicyURL)))
                }
            },
        ) {
            Text(
                text = stringResource(R.string.legal_privacy),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
