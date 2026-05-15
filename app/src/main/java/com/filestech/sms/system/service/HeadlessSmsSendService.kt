package com.filestech.sms.system.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.usecase.SendSmsUseCase
import com.filestech.sms.security.AppLockManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject

/**
 * Required by the platform for the default SMS app: must accept "respond via message" intents
 * (e.g. from the in-call screen). We translate them to our normal send path.
 *
 * **Audit F11 mitigation**: the service is `exported="true"` (required by the OS) and protected
 * by `SEND_RESPOND_VIA_MESSAGE` (held only by the OS). On top of that:
 *  - We refuse when the app lock is held (the user isn't authenticated → no sending).
 *  - We cap [Intent.EXTRA_TEXT] at [MAX_TEXT] characters.
 *  - We cap the number of recipients at [MAX_RECIPIENTS].
 *  - We reject any normalized recipient that doesn't match a phone shape.
 */
@AndroidEntryPoint
class HeadlessSmsSendService : Service() {

    @Inject lateinit var sendSms: SendSmsUseCase
    @Inject lateinit var appLock: AppLockManager
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent.action) {
            Intent.ACTION_SENDTO,
            Intent.ACTION_VIEW,
            Intent.ACTION_SEND -> handleSendTo(intent, startId)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun handleSendTo(intent: Intent, startId: Int) {
        // Parse + validate first (synchronously, cheap), then move the lock check + send into a
        // coroutine — this lets us call `appLock.ensureResolved()` lazily instead of forcing
        // `MainApplication.onCreate` to block the main thread waiting on DataStore (P-P0-5).
        val numbers = intent.data?.schemeSpecificPart
            ?.split(',', ';')
            ?.asSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.take(MAX_RECIPIENTS)
            ?.toList()
            ?: emptyList()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().take(MAX_TEXT)
        if (numbers.isEmpty() || text.isBlank()) {
            stopSelf(startId)
            return
        }
        val recipients = numbers
            .map { PhoneAddress.of(it) }
            .filter { it.normalized.isNotBlank() && it.normalized.length <= MAX_NUMBER_LEN }
        if (recipients.isEmpty()) {
            stopSelf(startId)
            return
        }
        scope.launch {
            try {
                // Audit M-12: bound the service lifetime. SendSmsUseCase opens N Room transactions
                // (one per recipient) sequentially; a single stuck SQLCipher write or a deadlock
                // with the import job could keep this Service alive past the OS Service-watchdog,
                // ending in a forced ANR + bad UX. 30 s is generous for the 50-recipient cap
                // (real-world send <500 ms per row) yet short enough to fail fast.
                withTimeout(SEND_TIMEOUT_MS) {
                    appLock.ensureResolved()
                    if (!appLock.isOpenForUi(appLock.state.value)) {
                        Timber.i("HeadlessSmsSendService: refused while app is locked")
                        return@withTimeout
                    }
                    sendSms.invoke(recipients = recipients, body = text)
                }
            } catch (_: TimeoutCancellationException) {
                Timber.w("HeadlessSmsSendService timed out after %d ms", SEND_TIMEOUT_MS)
            } catch (t: Throwable) {
                Timber.w(t, "HeadlessSmsSendService failed")
            } finally {
                stopSelf(startId)
            }
        }
    }

    private companion object {
        const val MAX_RECIPIENTS = 50
        const val MAX_TEXT = 5_000
        const val MAX_NUMBER_LEN = 32
        const val SEND_TIMEOUT_MS = 30_000L
    }
}
