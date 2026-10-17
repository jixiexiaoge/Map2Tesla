package com.navipilot.amap2tesla.sponsorship

import com.google.gson.JsonParser
import com.navipilot.amap2tesla.ProjectionState
import com.navipilot.airplay.AirPlayProjectionState
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorshipTest {
    @Test
    fun projectionStartsAndResetsWithoutTrialMode() {
        // Given / When
        ProjectionState.reset()
        AirPlayProjectionState.reset()

        // Then
        assertFalse(ProjectionState.trialMode)
        assertFalse(AirPlayProjectionState.trialMode)
    }

    @Test
    fun optionsContainOnlyVoluntarySponsorshipAmounts() {
        // Given
        val response = JsonParser.parseString(
            """{"options":[{"code":"sponsor_coffee","name":"Coffee","price_cents":500}]}""",
        ).asJsonObject

        // When
        val options = parseSponsorshipOptions(response)

        // Then
        assertEquals(listOf(SponsorshipOption("sponsor_coffee", "Coffee", 500)), options)
    }

    @Test(expected = IOException::class)
    fun oldMembershipPlansCannotBePresentedAsSponsorships() {
        // Given / When / Then
        parseSponsorshipOptions(JsonParser.parseString(
            """{"options":[{"code":"lifetime","name":"Membership","price_cents":9900}]}""",
        ).asJsonObject)
    }

    @Test(expected = IOException::class)
    fun missingCatalogIsAnErrorRatherThanAnEmptySuccess() {
        // Given / When / Then
        parseSponsorshipOptions(JsonParser.parseString("{}").asJsonObject)
    }

    @Test(expected = IOException::class)
    fun nonPositiveSponsorshipAmountIsRejected() {
        // Given / When / Then
        parseSponsorshipOptions(JsonParser.parseString(
            """{"options":[{"code":"sponsor_coffee","name":"Coffee","price_cents":0}]}""",
        ).asJsonObject)
    }

    @Test
    fun confirmationUsesThisOrdersStatusNotOldMembershipEntitlement() {
        // Given
        val pending = JsonParser.parseString(
            """{"status":"pending","license":{"enabled":true}}""",
        ).asJsonObject
        val paid = JsonParser.parseString(
            """{"status":"paid","license":{"enabled":false}}""",
        ).asJsonObject

        // When / Then
        assertFalse(isSponsorshipPaid(pending))
        assertTrue(isSponsorshipPaid(paid))
    }

    @Test(expected = IOException::class)
    fun missingOrderStatusDoesNotConfirmSponsorship() {
        // Given / When / Then
        isSponsorshipPaid(JsonParser.parseString("""{"license":{"enabled":true}}""").asJsonObject)
    }
}
