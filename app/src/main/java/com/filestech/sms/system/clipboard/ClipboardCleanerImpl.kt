package com.filestech.sms.system.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.filestech.sms.domain.clipboard.ClipboardCleaner
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.28.6 — implémentation de [ClipboardCleaner].
 *
 * **Deux chemins, parce que `minSdk` vaut 26 et que `clearPrimaryClip` demande Android 9.** Sans le
 * repli, la ligne n'aurait rien fait sur Android 8 sans que rien ne le signale — un correctif qui
 * ne s'applique qu'aux appareils récents et se croit posé partout.
 *
 * **Ce que cela ne garantit pas, et il faut le dire :** depuis Android 10, seul le propriétaire du
 * presse-papiers ou l'application au premier plan peut y écrire. Ici l'application EST au premier
 * plan — l'utilisateur vient de taper « Supprimer » — donc l'appel aboutit ; mais appelé depuis un
 * service en arrière-plan il échouerait en silence, d'où le `runCatching` et non une promesse.
 */
@Singleton
class ClipboardCleanerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ClipboardCleaner {

    override fun clear() {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
            ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.clearPrimaryClip()
            } else {
                // Android 8 : pas de `clearPrimaryClip`. Un clip vide ne laisse plus de texte, ce
                // qui est le but ; le presse-papiers n'est pas « absent » mais ne porte plus rien
                // de l'application.
                manager.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }.onFailure { Timber.w(it, "wipe: presse-papiers") }
    }
}
