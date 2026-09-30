package com.streams.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object Tier {
    const val FREE = "free"
    const val PREMIUM = "premium"
    const val HIDDEN = "hidden_premium"

    fun next(tier: String) = when (tier) {
        FREE -> PREMIUM
        PREMIUM -> HIDDEN
        else -> FREE
    }

    fun label(tier: String) = when (tier) {
        FREE -> "Free"
        PREMIUM -> "Premium"
        else -> "Exclusive"
    }
}

@Serializable
data class Channel(
    val id: String = "",
    val name: String,
    val description: String = "",
    @SerialName("is_premium") val isPremium: Boolean = false,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class Title(
    val id: String = "",
    val kind: String = "movie",                 // "movie" or "series"
    val name: String,
    val description: String = "",
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("cover_path") val coverPath: String? = null,
    @SerialName("trailer_path") val trailerPath: String? = null,
    @SerialName("video_path") val videoPath: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    val tier: String = Tier.FREE,
    val published: Boolean = false,
    val featured: Boolean = false,
    @SerialName("view_count") val viewCount: Long = 0,
    @SerialName("preview_count") val previewCount: Long = 0,
) {
    val isSeries get() = kind == "series"
}

@Serializable
data class Episode(
    val id: String = "",
    @SerialName("title_id") val titleId: String,
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String,
    val description: String = "",
    @SerialName("video_path") val videoPath: String,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
)

@Serializable
data class Plan(
    val id: String,
    val name: String,
    @SerialName("duration_label") val durationLabel: String,
    @SerialName("duration_days") val durationDays: Int,
    @SerialName("duration_hours") val durationHours: Int = 0,
    val price: Double,
    val currency: String = "INR",
    val active: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class Subscription(
    @SerialName("user_id") val userId: String,
    @SerialName("plan_id") val planId: String? = null,
    val status: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class Payment(
    val id: String,
    @SerialName("user_email") val userEmail: String? = null,
    @SerialName("plan_id") val planId: String,
    @SerialName("plan_name") val planName: String? = null,
    val amount: Double? = null,
    val currency: String? = null,
    val reference: String,
    val status: String,
    @SerialName("admin_note") val adminNote: String? = null,
    @SerialName("created_at") val createdAt: String,
    /** "manual" (customer typed a UTR) or "upi_auto" (confirmed from the UPI app's response). */
    val method: String = "manual",
    @SerialName("txn_id") val txnId: String? = null,
    @SerialName("reviewed_by") val reviewedBy: String? = null,
    /** Set when the money was seen arriving (bank SMS / UPI app notification on the owner's phone). */
    @SerialName("bank_credit_id") val bankCreditId: String? = null,
) {
    val isAuto get() = method == "upi_auto"
    val bankVerified get() = bankCreditId != null
    /** The number to look for in the bank statement. */
    val utr get() = txnId ?: reference
}

/** A credit seen on the owner's phone (Admin → Settings → Auto-verify). */
@Serializable
data class BankCredit(
    val id: String,
    val amount: Double,
    val ref: String? = null,
    val source: String? = null,
    @SerialName("payment_id") val paymentId: String? = null,
    @SerialName("received_at") val receivedAt: String,
)

@Serializable
data class PaymentSettings(
    @SerialName("upi_id") val upiId: String,
    @SerialName("payee_name") val payeeName: String,
    @SerialName("cloud_quota_gb") val cloudQuotaGb: Int = 2048,
    /** Merchant category code (mc) for a merchant UPI ID, e.g. from BharatPe / Paytm / PhonePe Business. */
    @SerialName("merchant_code") val merchantCode: String? = null,
)

/** A file in the member's private Cloud Storage folder (user-files/<user id>/...). */
@Serializable
data class CloudFile(
    val name: String,
    val size: Long = 0,
    val mimetype: String? = null,
    @SerialName("created_at") val createdAt: String,
) {
    /** Display name without the folder and the upload-time prefix. */
    val displayName: String get() = name.substringAfterLast('/').substringAfter("__")
}

@Serializable
data class AdminEmail(val email: String, val role: String)

@Serializable
data class Campaign(
    val id: String = "",
    val name: String,
    val message: String = "",
    val active: Boolean = false,
)

@Serializable
data class AdminStats(
    @SerialName("total_videos") val totalVideos: Long = 0,
    @SerialName("exclusive_videos") val exclusiveVideos: Long = 0,
    @SerialName("total_views") val totalViews: Long = 0,
    @SerialName("preview_views") val previewViews: Long = 0,
    @SerialName("paying_subscribers") val payingSubscribers: Long = 0,
    @SerialName("expired_subscribers") val expiredSubscribers: Long = 0,
    @SerialName("pending_payments") val pendingPayments: Long = 0,
)
