package com.streams.app.data

import android.content.Context
import android.net.Uri
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.storage.upload
import io.ktor.http.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.hours

/**
 * Every read/write the app does. Security is enforced by Postgres (RLS + functions in
 * supabase/schema.sql) — this file never decides who is allowed to see or pay for what.
 */
object Repo {

    // ---------------------------------------------------------------- auth
    suspend fun signInWithGoogle() = supabase.auth.signInWith(Google, redirectUrl = Links.LOGIN)

    suspend fun sendMagicLink(email: String) =
        supabase.auth.signInWith(OTP, redirectUrl = Links.LOGIN) {
            this.email = email.trim()
            createUser = true
        }

    suspend fun adminPasswordSignIn(email: String, password: String) =
        supabase.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }

    /** "Forgot password?" — e-mails a one-time link that opens the set-password screen. */
    suspend fun sendAdminPasswordLink(email: String) =
        supabase.auth.signInWith(OTP, redirectUrl = Links.ADMIN_SET_PASSWORD) {
            this.email = email.trim()
            createUser = true
        }

    suspend fun setPassword(newPassword: String) {
        supabase.auth.updateUser { password = newPassword }
    }

    suspend fun signOut() = supabase.auth.signOut()

    suspend fun myAdminRole(): String? =
        supabase.postgrest.rpc("my_admin_role").data.trim().trim('"').takeIf { it.isNotBlank() && it != "null" }

    // ---------------------------------------------------------------- catalogue
    suspend fun titles(): List<Title> =
        supabase.from("titles").select {
            order("created_at", Order.DESCENDING)
        }.decodeList()

    suspend fun title(id: String): Title? =
        supabase.from("titles").select { filter { eq("id", id) } }.decodeList<Title>().firstOrNull()

    suspend fun channels(): List<Channel> =
        supabase.from("channels").select { order("sort_order", Order.ASCENDING) }.decodeList()

    suspend fun episodes(titleId: String): List<Episode> =
        supabase.from("episodes").select {
            filter { eq("title_id", titleId) }
            order("episode_number", Order.ASCENDING)
        }.decodeList()

    suspend fun activeCampaign(): Campaign? =
        supabase.from("campaigns").select {
            filter { eq("active", true) }
            order("created_at", Order.DESCENDING)
        }.decodeList<Campaign>().firstOrNull()

    /** Signed, time-limited link to a file in the private "videos" bucket. Postgres decides if allowed. */
    suspend fun streamUrl(path: String): String =
        supabase.storage.from("videos").createSignedUrl(path, 6.hours)

    suspend fun recordView(titleId: String, preview: Boolean) = runCatching {
        supabase.postgrest.rpc("record_view", buildJsonObject {
            put("p_title_id", titleId); put("p_preview", preview)
        })
    }

    // ---------------------------------------------------------------- subscription & payments
    suspend fun plans(): List<Plan> =
        supabase.from("subscription_plans").select {
            filter { eq("active", true) }
            order("sort_order", Order.ASCENDING)
        }.decodeList()

    suspend fun mySubscription(): Subscription? {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return null
        return supabase.from("subscriptions").select { filter { eq("user_id", uid) } }
            .decodeList<Subscription>().firstOrNull()
    }

    suspend fun hasActiveSubscription(): Boolean =
        supabase.postgrest.rpc("has_active_subscription").data.trim() == "true"

    suspend fun paymentSettings(): PaymentSettings =
        supabase.from("app_settings").select().decodeSingle()

    suspend fun myPayments(): List<Payment> {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return emptyList()
        return supabase.from("payments").select {
            filter { eq("user_id", uid) }
            order("created_at", Order.DESCENDING)
        }.decodeList()
    }

    /** Price is looked up on the server from planId — the app never sends an amount. */
    suspend fun submitPayment(planId: String, reference: String): Payment =
        supabase.postgrest.rpc("submit_payment", buildJsonObject {
            put("p_plan_id", planId); put("p_reference", reference)
        }).decodeAs()

    // ---------------------------------------------------------------- admin
    suspend fun adminStats(): AdminStats = supabase.postgrest.rpc("admin_stats").decodeAs()

    suspend fun adminPayments(status: String?): List<Payment> =
        supabase.from("payments").select {
            if (status != null) filter { eq("status", status) }
            order("created_at", Order.DESCENDING)
            limit(200)
        }.decodeList()

    suspend fun approvePayment(id: String) {
        supabase.postgrest.rpc("approve_payment", buildJsonObject { put("p_payment_id", id) })
    }

    suspend fun rejectPayment(id: String, note: String) {
        supabase.postgrest.rpc("reject_payment", buildJsonObject {
            put("p_payment_id", id); put("p_note", note)
        })
    }

    suspend fun saveSettings(s: PaymentSettings) {
        supabase.from("app_settings").update(buildJsonObject {
            put("upi_id", s.upiId.trim()); put("payee_name", s.payeeName.trim())
        }) { filter { eq("id", 1) } }
    }

    suspend fun team(): List<AdminEmail> = supabase.from("admin_emails").select().decodeList()

    suspend fun addManager(email: String) {
        supabase.from("admin_emails").insert(buildJsonObject {
            put("email", email.trim().lowercase()); put("role", "manager")
        })
    }

    suspend fun removeManager(email: String) {
        supabase.from("admin_emails").delete { filter { eq("email", email) } }
    }

    suspend fun saveChannel(id: String?, name: String, description: String, premium: Boolean) {
        val body = buildJsonObject {
            put("name", name.trim()); put("description", description.trim()); put("is_premium", premium)
        }
        if (id == null) supabase.from("channels").insert(body)
        else supabase.from("channels").update(body) { filter { eq("id", id) } }
    }

    suspend fun deleteChannel(id: String) {
        supabase.from("channels").delete { filter { eq("id", id) } }
    }

    suspend fun campaigns(): List<Campaign> =
        supabase.from("campaigns").select { order("created_at", Order.DESCENDING) }.decodeList()

    suspend fun saveCampaign(id: String?, name: String, message: String, active: Boolean) {
        val body = buildJsonObject { put("name", name.trim()); put("message", message.trim()); put("active", active) }
        if (id == null) supabase.from("campaigns").insert(body)
        else supabase.from("campaigns").update(body) { filter { eq("id", id) } }
    }

    suspend fun deleteCampaign(id: String) {
        supabase.from("campaigns").delete { filter { eq("id", id) } }
    }

    suspend fun setPublished(id: String, published: Boolean) {
        supabase.from("titles").update({ set("published", published) }) { filter { eq("id", id) } }
    }

    suspend fun setTier(id: String, tier: String) {
        supabase.from("titles").update({ set("tier", tier) }) { filter { eq("id", id) } }
    }

    suspend fun setFeatured(id: String, featured: Boolean) {
        supabase.from("titles").update({ set("featured", featured) }) { filter { eq("id", id) } }
    }

    /** Insert when [id] is null, otherwise update. Returns the title id. */
    suspend fun saveTitle(id: String?, fields: JsonObject): String {
        return if (id == null) {
            supabase.from("titles").insert(fields) { select() }.decodeSingle<Title>().id
        } else {
            supabase.from("titles").update(fields) { filter { eq("id", id) } }
            id
        }
    }

    suspend fun deleteTitle(t: Title) {
        val eps = episodes(t.id)
        supabase.from("titles").delete { filter { eq("id", t.id) } }
        runCatching {
            val videos = listOfNotNull(t.videoPath, t.trailerPath) + eps.map { it.videoPath }
            if (videos.isNotEmpty()) supabase.storage.from("videos").delete(videos)
            t.coverPath?.let { supabase.storage.from("images").delete(it) }
        }
    }

    suspend fun addEpisode(titleId: String, number: Int, name: String, videoPath: String) {
        supabase.from("episodes").insert(buildJsonObject {
            put("title_id", titleId); put("episode_number", number); put("name", name.trim()); put("video_path", videoPath)
        })
    }

    suspend fun deleteEpisode(e: Episode) {
        supabase.from("episodes").delete { filter { eq("id", e.id) } }
        runCatching { supabase.storage.from("videos").delete(e.videoPath) }
    }

    /**
     * Copies the picked file to a temp file (so big videos stream from disk instead of memory)
     * and uploads it. Returns the storage path to save on the title.
     */
    suspend fun uploadFile(context: Context, uri: Uri, bucket: String, folder: String): String =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val ext = when {
                mime.contains("mp4") -> "mp4"
                mime.contains("quicktime") -> "mov"
                mime.contains("webm") -> "webm"
                mime.contains("matroska") -> "mkv"
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                mime.startsWith("image") -> "jpg"
                else -> "bin"
            }
            val tmp = File.createTempFile("upload", ".$ext", context.cacheDir)
            try {
                resolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val path = "$folder/${UUID.randomUUID()}.$ext"
                supabase.storage.from(bucket).upload(path, tmp) {
                    upsert = false
                    contentType = ContentType.parse(mime)
                }
                path
            } finally {
                tmp.delete()
            }
        }
}
