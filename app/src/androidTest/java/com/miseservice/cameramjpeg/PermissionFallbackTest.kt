package com.miseservice.cameramjpeg

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PermissionFallbackTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun permissionFallback_clicksInvokeBothCallbacks() {
        var retryClicks = 0
        var settingsClicks = 0

        composeRule.setContent {
            PermissionFallback(
                onRequestPermissions = { retryClicks++ },
                onOpenAppSettings = { settingsClicks++ }
            )
        }

        composeRule.onNodeWithTag(PERMISSION_RETRY_BUTTON_TAG).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(PERMISSION_SETTINGS_BUTTON_TAG).assertIsDisplayed().performClick()

        assertEquals(1, retryClicks)
        assertEquals(1, settingsClicks)
    }
}

