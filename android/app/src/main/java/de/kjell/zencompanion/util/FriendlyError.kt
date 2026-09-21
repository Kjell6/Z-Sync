package de.kjell.zencompanion.util

import androidx.annotation.StringRes
import de.kjell.zencompanion.R
import de.kjell.zencompanion.sync.SyncError
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Port of `Shared/FriendlyError.swift`: maps raw errors to one-line,
 * user-appropriate messages. The UI never shows HTTP bodies.
 */
object FriendlyError {
    @StringRes
    fun messageRes(error: Throwable): Int = when (error) {
        is SyncError.NotSignedIn -> R.string.error_not_signed_in
        is SyncError.TotpRequired -> R.string.error_totp
        is SyncError.StorageUnavailable -> R.string.error_storage_unavailable
        is SyncError.Auth -> R.string.error_auth
        is SyncError.Crypto -> R.string.error_crypto
        is SyncError.Conflict -> R.string.error_conflict
        is SyncError.Network -> if (isOffline(error)) R.string.error_offline else R.string.error_network
        else -> if (isOffline(error)) R.string.error_offline else R.string.error_generic
    }

    fun isOffline(error: Throwable): Boolean = when (error) {
        is UnknownHostException,
        is ConnectException,
        is SocketTimeoutException,
        is SocketException,
        is SSLException,
        -> true
        is IOException -> error.message?.let { msg ->
            listOf(
                "offline", "network", "connection", "timeout", "timed out",
                "econn", "etimedout", "ehostunreach", "enetunreach",
            ).any { msg.contains(it, ignoreCase = true) }
        } ?: false
        else -> false
    }
}
