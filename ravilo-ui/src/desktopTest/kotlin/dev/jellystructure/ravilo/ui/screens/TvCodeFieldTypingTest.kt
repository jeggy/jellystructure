package dev.jellystructure.ravilo.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DEB-2233 (FR-6, DEB-2866) — the code field takes six characters typed one key at a time as well as pasted: the space the field
 * shows after the third is drawn, never typed into the text, so the caret cannot fall behind it.
 */
@OptIn(ExperimentalTestApi::class)
class TvCodeFieldTypingTest {
    private object NoApi : TvApproveApi {
        override suspend fun preview(code: String): String = "Living room TV"
        override suspend fun approve(code: String) {}
    }

    @Test fun `a code typed one key at a time stays in order`() = runComposeUiTest {
        val store = TvApproveStore(NoApi)
        setContent { TvApproveScreen(store, "A", linkCode = null, onBack = {}, onSwitch = null) }
        waitForIdle()
        for (c in "jr6xsl") {
            onNodeWithTag(TvApproveTags.FIELD).performTextInput(c.toString())
            waitForIdle()
        }
        assertEquals(TvApproveState.Entering("JR6XSL"), store.state.value)
    }

    @Test fun `a code pasted with a space and in lower case is one change`() = runComposeUiTest {
        val store = TvApproveStore(NoApi)
        setContent { TvApproveScreen(store, "A", linkCode = null, onBack = {}, onSwitch = null) }
        waitForIdle()
        onNodeWithTag(TvApproveTags.FIELD).performTextInput("k7p 4qx")
        waitForIdle()
        assertEquals(TvApproveState.Entering("K7P4QX"), store.state.value)
    }
}
