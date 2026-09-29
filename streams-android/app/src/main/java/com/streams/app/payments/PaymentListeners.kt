package com.streams.app.payments

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Bank "credited" SMS. Only DLT sender IDs (letters, e.g. "VM-BDNSMS") — never a plain phone number. */
class PaymentSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!AutoVerify.isEnabled(context)) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // A long SMS arrives in several parts from the same sender.
        parts.groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "" }.forEach { (sender, msgs) ->
            if (!sender.any { it.isLetter() }) return@forEach
            val body = msgs.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
            CreditParser.parse(body)?.let { AutoVerify.report(context, it, "sms:$sender", body) }
        }
    }
}

/** "₹149.37 received" notifications from UPI / bank apps. */
class PaymentNotificationListener : NotificationListenerService() {
    private val trusted = listOf(
        "com.google.android.apps.nbu.paisa", // Google Pay (user + business)
        "com.phonepe",                       // PhonePe (user + business)
        "net.one97.paytm",                   // Paytm
        "com.paytm",                         // Paytm for Business
        "in.org.npci.upiapp",                // BHIM
        "bandhan",                           // Bandhan Bank apps
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val context = applicationContext
        if (!AutoVerify.isEnabled(context)) return
        val pkg = sbn.packageName ?: return
        if (trusted.none { pkg.startsWith(it) || pkg.contains(it) }) return
        val extras = sbn.notification?.extras ?: return
        val text = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT),
        ).joinToString(" · ")
        CreditParser.parse(text)?.let { AutoVerify.report(context, it, "app:$pkg", text) }
    }
}
