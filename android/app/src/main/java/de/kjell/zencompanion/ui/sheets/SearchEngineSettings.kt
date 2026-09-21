package de.kjell.zencompanion.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.SearchEngineTemplate
import de.kjell.zencompanion.data.SearchEngineValidation

/**
 * Own page for adding a user-defined search engine, reached from the picker's
 * "Add custom engine…" action. A custom engine is just a URL template with a
 * `{query}` placeholder; pasting a real search link derives it. [onSubmit]
 * returns null on success, otherwise the reason the draft was rejected.
 */
@Composable
internal fun SettingsAddSearchEngineScreen(
    onSubmit: (name: String, template: String) -> SearchEngineValidation?,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
    var pastedLink by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<SearchEngineValidation?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.settings_search_engine_add_title),
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

        // Shortcut path: derive the template from a real search link.
        OutlinedTextField(
            value = pastedLink,
            onValueChange = { value ->
                pastedLink = value
                val derived = SearchEngineTemplate.derive(value)
                if (derived != null) {
                    template = derived
                    if (name.isBlank()) name = SearchEngineTemplate.suggestedName(derived).orEmpty()
                    error = null
                }
            },
            label = { Text(stringResource(R.string.settings_search_engine_paste_link)) },
            supportingText = { Text(stringResource(R.string.settings_search_engine_paste_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OrSeparator(stringResource(R.string.settings_search_engine_or_manual))

        // Manual path. Also the destination of the shortcut above.
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                error = null
            },
            label = { Text(stringResource(R.string.settings_search_engine_name)) },
            placeholder = { Text(stringResource(R.string.settings_search_engine_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = template,
            onValueChange = {
                template = it
                error = null
            },
            label = { Text(stringResource(R.string.settings_search_engine_url)) },
            placeholder = { Text("https://example.com/search?q={query}") },
            supportingText = { Text(stringResource(R.string.settings_search_engine_url_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let { violation ->
            Text(
                text = stringResource(violation.messageRes()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = {
                val result = onSubmit(name, template)
                if (result == null) onBack() else error = result
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_search_engine_add))
        }
    }
}

/** "or enter it manually" — the shortcut above and the fields below are two
 *  ways to do the same thing, not two steps. */
@Composable
private fun OrSeparator(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

private fun SearchEngineValidation.messageRes(): Int = when (this) {
    SearchEngineValidation.EMPTY_NAME -> R.string.settings_search_engine_error_empty_name
    SearchEngineValidation.INVALID_URL -> R.string.settings_search_engine_error_invalid_url
    SearchEngineValidation.MISSING_PLACEHOLDER -> R.string.settings_search_engine_error_missing_placeholder
}
