package de.kjell.zencompanion.ui.screens

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.liftCard
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Covers the web view when a page can't load. The address bar and the
 * footer stay, so a typo can be fixed in place. Offline waits for the
 * network to come back and then retries once by itself.
 */
@Composable
internal fun BrowserErrorPanel(
    error: BrowserPageError,
    host: String,
    isLoading: Boolean,
    onRetry: () -> Unit,
    onSearch: () -> Unit,
    onOpenExternally: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zen = LocalZenColors.current
    if (error == BrowserPageError.OFFLINE) RetryWhenOnline(onRetry)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(zen.paper),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(start = 32.dp, end = 32.dp, top = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .liftCard(RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = error.icon(),
                    contentDescription = null,
                    tint = if (error == BrowserPageError.INSECURE) MaterialTheme.colorScheme.error else zen.coral,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = error.title(host),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = zen.ink,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = error.message(host),
                style = MaterialTheme.typography.bodyMedium,
                color = zen.ink60,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            when (error) {
                BrowserPageError.OFFLINE ->
                    PrimaryButton(stringResource(R.string.browser_error_try_again), isLoading, onRetry)
                BrowserPageError.HOST_NOT_FOUND -> {
                    PrimaryButton(stringResource(R.string.browser_error_search_for, host), isLoading, onSearch)
                    SecondaryButton(stringResource(R.string.browser_error_try_again), onRetry)
                }
                BrowserPageError.NOT_RESPONDING -> {
                    PrimaryButton(stringResource(R.string.browser_error_try_again), isLoading, onRetry)
                    SecondaryButton(stringResource(R.string.browser_open_in_browser), onOpenExternally)
                }
                BrowserPageError.INSECURE ->
                    PrimaryButton(stringResource(R.string.browser_error_go_back), isLoading = false, onLeave)
                BrowserPageError.CRASHED ->
                    PrimaryButton(stringResource(R.string.browser_error_reload), isLoading, onRetry)
            }
            if (error == BrowserPageError.OFFLINE) {
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = zen.ink60,
                    )
                    Text(
                        text = stringResource(R.string.browser_error_offline_waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = zen.ink60,
                    )
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, isLoading: Boolean, onClick: () -> Unit) {
    val zen = LocalZenColors.current
    TextButton(
        onClick = onClick,
        enabled = !isLoading,
        shape = CircleShape,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .background(zen.coral, CircleShape),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (isLoading) Color.Transparent else zen.paper,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = zen.paper,
                )
            }
        }
    }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    val zen = LocalZenColors.current
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(
            text = label,
            color = zen.coral,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

private fun BrowserPageError.icon() = when (this) {
    BrowserPageError.OFFLINE -> Icons.Outlined.WifiOff
    BrowserPageError.HOST_NOT_FOUND -> Icons.Outlined.Language
    BrowserPageError.NOT_RESPONDING -> Icons.Outlined.Schedule
    BrowserPageError.INSECURE -> Icons.Outlined.GppMaybe
    BrowserPageError.CRASHED -> Icons.Outlined.ErrorOutline
}

@Composable
private fun BrowserPageError.title(host: String) = when (this) {
    BrowserPageError.OFFLINE -> stringResource(R.string.browser_error_offline_title)
    BrowserPageError.HOST_NOT_FOUND -> stringResource(R.string.browser_error_host_not_found_title, host)
    BrowserPageError.NOT_RESPONDING -> stringResource(R.string.browser_error_not_responding_title, host)
    BrowserPageError.INSECURE -> stringResource(R.string.browser_error_insecure_title)
    BrowserPageError.CRASHED -> stringResource(R.string.browser_error_crashed_title)
}

@Composable
private fun BrowserPageError.message(host: String) = when (this) {
    BrowserPageError.OFFLINE -> stringResource(R.string.browser_error_offline_message)
    BrowserPageError.HOST_NOT_FOUND -> stringResource(R.string.browser_error_host_not_found_message)
    BrowserPageError.NOT_RESPONDING -> stringResource(R.string.browser_error_not_responding_message)
    BrowserPageError.INSECURE -> stringResource(R.string.browser_error_insecure_message, host)
    BrowserPageError.CRASHED -> stringResource(R.string.browser_error_crashed_message)
}

/** True when the device has a network that reached the internet. */
internal fun hasValidatedNetwork(manager: ConnectivityManager?): Boolean {
    val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * Calls [onOnline] once the network comes back after having been down. A
 * network that already reads as working waits for a real change instead of
 * retrying in a loop.
 */
@Composable
private fun RetryWhenOnline(onOnline: () -> Unit) {
    val context = LocalContext.current
    val retry by rememberUpdatedState(onOnline)
    DisposableEffect(Unit) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val main = Handler(Looper.getMainLooper())
        val wasDown = AtomicBoolean(!hasValidatedNetwork(manager))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                wasDown.set(true)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    wasDown.getAndSet(false)
                ) {
                    main.post { retry() }
                }
            }
        }
        manager?.registerDefaultNetworkCallback(callback)
        onDispose { manager?.unregisterNetworkCallback(callback) }
    }
}
