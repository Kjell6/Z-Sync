package de.kjell.zencompanion.ui.screens

import androidx.compose.ui.graphics.Color
import de.kjell.zencompanion.data.SearchEngines
import java.net.URI

/**
 * Resolves raw user address/search input to a fully-qualified URL.
 */
fun formatBrowserInput(query: String): String {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return ""

    val hasScheme = runCatching {
        val uri = URI(trimmed)
        !uri.scheme.isNullOrEmpty()
    }.getOrDefault(false) || trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)

    if (hasScheme) {
        return trimmed
    }
    if (trimmed.contains('.') && !trimmed.contains(' ')) {
        return "https://$trimmed"
    }
    return SearchEngines.current.formatQuery(trimmed)
}

/**
 * Parses CSS hex, rgb(), and rgba() color strings extracted from the DOM into a Compose Color.
 */
fun parseCssColor(css: String?): Color? {
    if (css == null) return null
    val clean = css.replace("\"", "").replace("'", "").replace("\\", "").trim()
    if (clean.isEmpty() || clean == "null" || clean == "undefined" || clean == "transparent") return null

    return runCatching {
        if (clean.startsWith("#")) {
            val hex = clean.removePrefix("#")
            when (hex.length) {
                3 -> {
                    val r = hex[0].digitToInt(16) * 17
                    val g = hex[1].digitToInt(16) * 17
                    val b = hex[2].digitToInt(16) * 17
                    Color(red = r / 255f, green = g / 255f, blue = b / 255f)
                }
                6 -> {
                    val r = hex.substring(0, 2).toInt(16)
                    val g = hex.substring(2, 4).toInt(16)
                    val b = hex.substring(4, 6).toInt(16)
                    Color(red = r / 255f, green = g / 255f, blue = b / 255f)
                }
                8 -> {
                    val r = hex.substring(0, 2).toInt(16)
                    val g = hex.substring(2, 4).toInt(16)
                    val b = hex.substring(4, 6).toInt(16)
                    val a = hex.substring(6, 8).toInt(16) / 255f
                    Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = a)
                }
                else -> null
            }
        } else if (clean.startsWith("rgb", ignoreCase = true)) {
            val numbers = clean.substringAfter("(").substringBefore(")").split(",")
            if (numbers.size >= 3) {
                val r = numbers[0].trim().toFloatOrNull()?.toInt() ?: 0
                val g = numbers[1].trim().toFloatOrNull()?.toInt() ?: 0
                val b = numbers[2].trim().toFloatOrNull()?.toInt() ?: 0
                val a = if (numbers.size >= 4) {
                    numbers[3].trim().toFloatOrNull() ?: 1f
                } else 1f
                if (a <= 0.05f) null else Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = a)
            } else null
        } else {
            null
        }
    }.getOrNull()
}
