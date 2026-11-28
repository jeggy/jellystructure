package dev.jellystructure.ravilo.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** DEB-5837, FR-4: the film and live players keep their own spinners; every other busy mark is WaitIndicator. */
class WaitIndicatorGuardTest {
    private val allowed = setOf("LiveTvPlayerScreen.kt", "PlayerHandsetChrome.kt", "WaitIndicator.kt")

    @Test
    fun noCircularProgressIndicatorOutsideThePlayers() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "ravilo-ui/src/commonMain") }.first { it.isDirectory }
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .filter { "CircularProgressIndicator(" in it.readText() }
            .map { it.name }.toList()
        assertTrue(offenders.isEmpty(), "CircularProgressIndicator( outside the players: $offenders")
    }
}
