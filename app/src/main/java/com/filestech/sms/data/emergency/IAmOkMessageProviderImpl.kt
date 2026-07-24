package com.filestech.sms.data.emergency

import android.content.Context
import com.filestech.sms.R
import com.filestech.sms.domain.emergency.IAmOkMessageProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Résout le corps du SMS « Je vais bien » depuis les ressources de l'app. Vit dans `data/`
 * (couche qui a accès au `Context` et au `R`), gardant [IAmOkMessageProvider] — donc `domain/` —
 * indépendant du framework Android.
 */
@Singleton
class IAmOkMessageProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : IAmOkMessageProvider {

    override fun body(): String = context.getString(R.string.emergency_i_am_ok_body)
}
