package de.kjell.zencompanion

import de.kjell.zencompanion.data.EssentialsColumns
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The essentials-per-row choice: the adaptive default keeps the pre-setting
 * behavior, a fixed choice caps the row at its number (and never exceeds the
 * item count, so a short row fills the width instead of leaving a gap).
 */
class EssentialsColumnsTests {
    @Test
    fun automaticMatchesTheAdaptiveDefault() {
        val expected = mapOf(
            1 to 1,
            2 to 2,
            3 to 3,
            4 to 4,
            5 to 3,
            6 to 3,
            7 to 4,
            8 to 4,
            12 to 4,
        )
        expected.forEach { (count, columns) ->
            assertEquals(
                "automatic with $count essentials",
                columns,
                EssentialsColumns.AUTOMATIC.columnCountFor(count),
            )
        }
    }

    @Test
    fun fixedChoiceCapsAtTheItemCount() {
        assertEquals(3, EssentialsColumns.FIVE.columnCountFor(3))
        assertEquals(5, EssentialsColumns.FIVE.columnCountFor(8))
        assertEquals(2, EssentialsColumns.TWO.columnCountFor(5))
        assertEquals(1, EssentialsColumns.ONE.columnCountFor(9))
    }

    @Test
    fun everyChoiceUsesAtLeastOneColumn() {
        EssentialsColumns.entries.forEach { choice ->
            assertEquals(1, choice.columnCountFor(0))
        }
    }

    @Test
    fun storageRoundTripsAndFallsBackToAutomatic() {
        EssentialsColumns.entries.forEach { choice ->
            assertEquals(choice, EssentialsColumns.fromStorage(choice.storageValue))
        }
        assertEquals(EssentialsColumns.AUTOMATIC, EssentialsColumns.fromStorage(null))
        assertEquals(EssentialsColumns.AUTOMATIC, EssentialsColumns.fromStorage("eleven"))
    }
}
