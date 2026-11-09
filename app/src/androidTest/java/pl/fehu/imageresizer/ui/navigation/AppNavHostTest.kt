package pl.fehu.imageresizer.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.fehu.imageresizer.R
import pl.fehu.imageresizer.ui.theme.BatchResizerTheme

/** Smoke test for the real navigation graph (application-plan.md M5): Home -> Options -> back. */
@RunWith(AndroidJUnit4::class)
class AppNavHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun navigatesToOptionsAndBack() {
        composeRule.setContent { BatchResizerTheme { AppNavHost() } }

        composeRule.onNodeWithText(context.getString(R.string.home_add_images)).assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.home_options_card_title)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.options_section_resize_mode)).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(context.getString(R.string.action_navigate_back)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.home_add_images)).assertIsDisplayed()
    }
}
