package com.edgevision.wallpaper.entitlement

/**
 * Subscription/product tiers. Nothing in the app may compare against these
 * directly for feature access; features are gated through [Feature] so a new
 * marketplace or promotional tier does not require edits across the codebase.
 */
enum class Tier(val rank: Int) {
    FREE(0),
    PRO(1),
    PREMIUM(2),
    ;

    fun atLeast(other: Tier): Boolean = rank >= other.rank
}

/** Every gated capability, declared once with the tier that unlocks it. */
enum class Feature(val minimumTier: Tier) {
    CORE_DETECTION(Tier.FREE),
    ALERT_GRADING(Tier.FREE),
    MANUAL_CAPTURE(Tier.FREE),
    ADVANCED_DETECTION(Tier.PRO),
    AUTO_BURST_CAPTURE(Tier.PRO),
    ADVANCED_ALERTS(Tier.PRO),
    DETECTION_HISTORY(Tier.PREMIUM),
    ADDITIONAL_MODELS(Tier.PREMIUM),
}

/**
 * Result of asking a store for the current entitlement. `Unavailable` is a
 * first-class outcome: no billing client configured, offline, or store error.
 * It always resolves to FREE rather than pretending a purchase succeeded.
 */
sealed interface TierResult {
    data class Resolved(val tier: Tier, val source: String) : TierResult

    data class Unavailable(val reason: String) : TierResult
}

/** A marketplace integration (Play, Galaxy Store, AppGallery, direct licence). */
interface EntitlementProvider {
    val id: String

    /** Reads cached entitlement; must not block the main thread for long. */
    fun currentTier(): TierResult
}

/**
 * The single authority on what the user may use. Callers ask [hasFeature],
 * never "is premium".
 */
class EntitlementService(
    private val provider: EntitlementProvider? = null,
) {

    private val cached: TierResult = provider?.currentTier() ?: TierResult.Unavailable("No billing provider configured")

    val tier: Tier
        get() = when (val result = cached) {
            is TierResult.Resolved -> result.tier
            is TierResult.Unavailable -> Tier.FREE
        }

    /** Human-readable status for diagnostics; never claims an unverified purchase. */
    val statusDescription: String
        get() = when (val result = cached) {
            is TierResult.Resolved -> "${result.tier.name} (${result.source})"
            is TierResult.Unavailable -> "Free (${result.reason})"
        }

    fun hasFeature(feature: Feature): Boolean = tier.atLeast(feature.minimumTier)
}

/**
 * Purchase flow contract. Deliberately has no success implementation yet: a
 * real store integration must verify the purchase server-side before returning
 * [PurchaseResult.Verified]. Until then every implementation reports
 * [PurchaseResult.NotConfigured], and the UI must say so.
 */
interface BillingGateway {
    val id: String

    fun purchase(tier: Tier): PurchaseResult
}

sealed interface PurchaseResult {
    /** A purchase was verified by the store and/or a backend. */
    data class Verified(val tier: Tier, val token: String) : PurchaseResult

    /** Billing is not wired up in this build. Never treated as success. */
    data class NotConfigured(val reason: String) : PurchaseResult

    data object Cancelled : PurchaseResult

    data class Failed(val reason: String) : PurchaseResult
}

/** Placeholder provider used until a store SDK is integrated. */
class UnconfiguredBillingGateway : BillingGateway {
    override val id: String = "unconfigured"

    override fun purchase(tier: Tier): PurchaseResult =
        PurchaseResult.NotConfigured("No billing marketplace is configured in this build")
}

/** Local provider used by tests and by builds with no store integration. */
class FixedTierProvider(
    override val id: String,
    private val tier: Tier,
) : EntitlementProvider {
    override fun currentTier(): TierResult = TierResult.Resolved(tier, id)
}
