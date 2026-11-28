package dev.jellystructure.ravilo.ui.focus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.components.tileA11yLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** DEB-5838 (FR-4) — `dpadFocusable` tells a screen reader what the item is and lets it press it. */
@OptIn(ExperimentalTestApi::class)
class DpadSemanticsTest {
    @Test fun `an item with onSelect is a button and its click action runs the callback`() = runComposeUiTest {
        var selected = 0
        setContent { Box(Modifier.testTag("t").size(40.dp).dpadFocusable(onSelect = { selected++ })) { Text("Stand-in Title") } }
        val node = onNodeWithTag("t").fetchSemanticsNode()
        assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        assertNotNull(node.config.getOrNull(SemanticsActions.OnClick))
        onNodeWithTag("t").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, selected)
    }

    @Test fun `a click runs onTap when it differs from onSelect, and a caller may pass its role`() = runComposeUiTest {
        var tapped = 0; var selected = 0
        setContent { Box(Modifier.testTag("t").size(40.dp).dpadFocusable(onSelect = { selected++ }, onTap = { tapped++ }, role = Role.Tab)) }
        assertEquals(Role.Tab, onNodeWithTag("t").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        onNodeWithTag("t").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, tapped); assertEquals(0, selected)
    }

    @Test fun `an item that cannot be pressed is not announced as a button`() = runComposeUiTest {
        setContent { Box(Modifier.testTag("t").size(40.dp).dpadFocusable(onLeft = {})) }
        val config = onNodeWithTag("t").fetchSemanticsNode().config
        assertNull(config.getOrNull(SemanticsProperties.Role))
        assertFalse(config.contains(SemanticsActions.OnClick))
    }

    @Test fun `a tile is named by its title, its detail and Watched`() {
        assertEquals("Stand-in Title", tileA11yLabel("Stand-in Title", null, null))
        assertEquals("Stand-in Title, New", tileA11yLabel("Stand-in Title", "New", null))
        assertEquals("Stand-in Title, Watched", tileA11yLabel("Stand-in Title", null, "Watched"))
    }
}
