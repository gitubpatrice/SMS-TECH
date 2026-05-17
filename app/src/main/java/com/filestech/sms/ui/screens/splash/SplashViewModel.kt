package com.filestech.sms.ui.screens.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.data.local.datastore.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * v1.3.7 — ViewModel du splash de présentation de l'app au premier lancement.
 *
 * Expose un [StateFlow] booléen [shouldShow] :
 *   - **valeur initiale `true`** par sécurité (mieux vaut afficher un splash inutile
 *     pendant 1 frame que skipper un splash dû). Sera réécrite par la première
 *     émission DataStore (en pratique instantanée car le `Flow` est froid mais
 *     hot-démarré par `stateIn`).
 *   - **`true`** tant que [com.filestech.sms.data.local.datastore.AdvancedSettings
 *     .splashShown] est `false` côté disque.
 *   - **`false`** ensuite — la Composable doit alors appeler `onFinished()` sans
 *     rendre la moindre frame splash.
 *
 * [markShown] est idempotent ; appelé une seule fois à la sortie du splash
 * (auto-dismiss à 3 s OU tap-to-skip) pour persister le flag. Toute ouverture
 * suivante observera `shouldShow.value = false` au cold start et redirigera
 * immédiatement vers la home. Seul un `Effacer les données` côté Paramètres
 * système ré-affichera le splash (nouvelle "1ère ouverture" du point de vue user).
 *
 * **Pourquoi `SharingStarted.Eagerly`** : on veut que la lecture DataStore commence
 * dès la construction du ViewModel (au cold start, juste avant la première
 * recomposition), pour que `shouldShow.value` ait sa vraie valeur dès le premier
 * frame du Composable et que la décision "splash vs skip" soit prise sans flash.
 */
@HiltViewModel
class SplashViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val shouldShow: StateFlow<Boolean> = settings.flow
        .map { !it.advanced.splashShown }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = true,
        )

    fun markShown() {
        viewModelScope.launch {
            settings.update { it.copy(advanced = it.advanced.copy(splashShown = true)) }
        }
    }
}
