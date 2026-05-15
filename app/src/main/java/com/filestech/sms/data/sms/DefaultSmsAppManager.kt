package com.filestech.sms.data.sms

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiny helper to query / request "default SMS app" role.
 *
 * Android Q (29) + → use [RoleManager.ROLE_SMS].
 * KitKat-Pie (19-28) → use [Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT].
 */
@Singleton
class DefaultSmsAppManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * On Android 10+ Samsung One UI sometimes leaves `Settings.Secure.sms_default_application`
     * null even when the RoleManager has correctly granted ROLE_SMS to us. So prefer the
     * RoleManager check on Q+ and fall back to the Telephony API on legacy devices.
     */
    fun isDefault(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(Context.ROLE_SERVICE) as RoleManager?
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                return rm.isRoleHeld(RoleManager.ROLE_SMS)
            }
        }
        return Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }

    /** Builds the intent to request default SMS app status. Returns null if not applicable. */
    fun buildChangeDefaultIntent(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val rm = context.getSystemService(Context.ROLE_SERVICE) as RoleManager?
        if (rm?.isRoleAvailable(RoleManager.ROLE_SMS) == true && !rm.isRoleHeld(RoleManager.ROLE_SMS)) {
            rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else null
    } else {
        Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(
            Telephony.Sms.Intents.EXTRA_PACKAGE_NAME,
            context.packageName,
        )
    }
}
