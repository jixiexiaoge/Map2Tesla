package com.jixiexiaoge.drivingassist

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ProjectionSponsorshipUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("custom_projection_apps", Context.MODE_PRIVATE)
    private val slotKeys = (0 until 6).flatMap { listOf("slot_${it}_package", "slot_${it}_label") }
    private lateinit var previousSlots: Map<String, String?>

    @Before
    fun keepOriginalCustomApps() {
        previousSlots = slotKeys.associateWith { preferences.getString(it, null) }
        preferences.edit().apply {
            slotKeys.forEach(::remove)
        }.commit()
    }

    @After
    fun restoreOriginalCustomApps() {
        preferences.edit().apply {
            previousSlots.forEach { (key, value) ->
                if (value == null) remove(key) else putString(key, value)
            }
        }.commit()
    }

    @Test
    fun sixEmptySlotsCanSelectAPreviouslyPinnedMap() {
        // Given
        compose.setContent {
            MaterialTheme {
                ProjectionPage(0, false, {}, {})
            }
        }

        // When / Then
        (1..6).forEach { compose.onNodeWithText("自定义 $it").assertExists() }
        compose.onNodeWithText("自定义 7").assertDoesNotExist()
        compose.onNodeWithText("高德车机版").assertDoesNotExist()
        compose.onNodeWithText("腾讯车机版").assertDoesNotExist()
        compose.onNodeWithText("百度车机版").assertDoesNotExist()
        compose.onNodeWithText("免费开始投屏").assertIsNotEnabled()
        compose.onNodeWithText("自定义 1").performScrollTo().performClick()
        compose.onNodeWithText("com.autonavi.amapauto").performScrollTo().performClick()
        compose.onNodeWithText("免费开始投屏").assertIsEnabled()
        compose.onNodeWithText("高德快捷导航").assertExists()
        assertEquals("com.autonavi.amapauto", preferences.getString("slot_0_package", null))
    }

    @Test
    fun allFourAmountsOfferTheSameQrWithoutClaimingPaymentSuccess() {
        // Given
        compose.setContent {
            MaterialTheme { SponsorshipPage() }
        }

        // When / Then
        for (amount in listOf(5, 15, 25, 55)) {
            compose.onNodeWithText("自愿赞助 · ¥$amount").performScrollTo().performClick()
            compose.onNodeWithText("自愿赞助 ¥$amount").assertIsDisplayed()
            compose.onNodeWithContentDescription("与 CP搭子相同的微信收款码").assertIsDisplayed()
            compose.onNodeWithText("微信赞助：截图后用微信扫一扫的相册识别，或使用另一台手机扫码。请核对收款方，并手动输入 $amount 元。")
                .assertExists()
            compose.onNodeWithText("赞助已确认，感谢你支持特投屏持续开发！").assertDoesNotExist()
            compose.onNodeWithText("关闭").performClick()
        }
    }
}
