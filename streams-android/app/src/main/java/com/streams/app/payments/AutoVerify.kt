package com.streams.app.payments

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.streams.app.data.Repo
import com.streams.app.data.supabase
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * "Auto-verify payments" — switched on in Admin → Settings on the phone that receives the
 * bank SMS / UPI notifications for the business UPI ID. Every incoming credit is sent to the
 * server, which activates the customer's order with that exact amount.
 */
object AutoVerify {
    private const val PREFS = "auto_verify"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun hasSmsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(context, PaymentNotificationListener::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    /** Queue a detected credit for upload; retried until the network is back. */
    fun report(context: Context, credit: CreditParser.Credit, source: String, raw: String) {
        if (!isEnabled(context)) return
        val request = OneTimeWorkRequestBuilder<ReportCreditWorker>()
            .setInputData(
                workDataOf(
                    "amount" to credit.amount,
                    "ref" to credit.ref,
                    "source" to source.take(80),
                    "raw" to raw.take(500),
                ),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}

class ReportCreditWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val amount = inputData.getDouble("amount", 0.0)
        if (amount <= 0.0) return Result.failure()
        // The worker can run while the app is closed: wait for the saved admin session to load.
        supabase.auth.sessionStatus.first { it !is SessionStatus.Initializing }
        if (supabase.auth.currentUserOrNull() == null) return Result.failure()
        return try {
            Repo.recordBankCredit(
                amount = amount,
                ref = inputData.getString("ref"),
                source = inputData.getString("source") ?: "",
                raw = inputData.getString("raw") ?: "",
            )
            Result.success()
        } catch (e: RestException) {
            Result.failure() // e.g. this account is not an admin — retrying won't help
        } catch (e: Exception) {
            if (runAttemptCount < 30) Result.retry() else Result.failure()
        }
    }
}
