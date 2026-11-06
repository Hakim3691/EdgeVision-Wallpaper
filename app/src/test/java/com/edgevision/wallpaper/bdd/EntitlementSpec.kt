package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.entitlement.EntitlementService
import com.edgevision.wallpaper.entitlement.Feature
import com.edgevision.wallpaper.entitlement.FixedTierProvider
import com.edgevision.wallpaper.entitlement.PurchaseResult
import com.edgevision.wallpaper.entitlement.Tier
import com.edgevision.wallpaper.entitlement.UnconfiguredBillingGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: marketplace-independent entitlements.
 *
 * Monetisation must be real or absent. With no store integration the app is
 * FREE, and a purchase attempt reports that it is not configured instead of
 * pretending to succeed.
 */
class EntitlementSpec {

    @Test
    fun `no provider resolves to the free tier`() {
        val service = EntitlementService()

        Then("the user is free and core features stay available") {
            assertEquals(Tier.FREE, service.tier)
            assertTrue(service.hasFeature(Feature.CORE_DETECTION))
            assertTrue(service.hasFeature(Feature.MANUAL_CAPTURE))
            assertFalse(service.hasFeature(Feature.AUTO_BURST_CAPTURE))
            assertTrue(service.statusDescription.startsWith("Free ("))
        }
    }

    @Test
    fun `a pro entitlement unlocks pro features but not premium ones`() {
        val service = EntitlementService(FixedTierProvider("test", Tier.PRO))

        Then("feature gates follow the declared tiers") {
            assertTrue(service.hasFeature(Feature.ADVANCED_DETECTION))
            assertTrue(service.hasFeature(Feature.AUTO_BURST_CAPTURE))
            assertFalse(service.hasFeature(Feature.DETECTION_HISTORY))
        }
    }

    @Test
    fun `a premium entitlement unlocks everything`() {
        val service = EntitlementService(FixedTierProvider("test", Tier.PREMIUM))

        Then("every declared feature is available") {
            Feature.entries.forEach { feature ->
                assertTrue("expected $feature to be available", service.hasFeature(feature))
            }
        }
    }

    @Test
    fun `an unconfigured store never reports a successful purchase`() {
        val gateway = UnconfiguredBillingGateway()

        Then("the purchase result is explicitly not configured") {
            val result = gateway.purchase(Tier.PRO)
            assertTrue(result is PurchaseResult.NotConfigured)
        }
    }
}
