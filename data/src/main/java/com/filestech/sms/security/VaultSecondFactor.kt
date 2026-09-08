package com.filestech.sms.security

import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.settings.LockMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.27.13 — ce qu'il faut PROUVER pour ouvrir le coffre, sur cette installation.
 *
 * [NONE] n'est pas un trou : c'est la configuration d'un utilisateur qui n'a pose aucun second
 * facteur sur son coffre. Y entrer ne demande alors rien de plus que d'etre dans l'application,
 * et exiger davantage pour l'EXPORTER protegerait donc contre une personne qui peut deja lire le
 * contenu en deux tapes.
 */
enum class VaultSecondFactor { NONE, PIN, BIOMETRIC }

/**
 * v1.27.13 — la politique d'entree du coffre, lisible hors de son ecran.
 *
 * # Pourquoi elle existe
 *
 * La garde d'export posee en v1.27.2 exigeait `VaultSessionState.isUnlocked`. Or cette session
 * n'est ouverte que par [com.filestech.sms.ui.screens.vault.VaultScreen], et
 * `lockedOnBack` la referme a **chaque** sortie explique de cet ecran — inconditionnellement,
 * le reglage « verrouiller a la sortie » ne portant que sur la mise en arriere-plan. L'ecran de
 * sauvegarde n'etant atteignable qu'apres avoir quitte le coffre, la condition n'etait
 * satisfaisable par AUCUN chemin de navigation : **la sauvegarde chiffree etait impossible pour
 * tout utilisateur dont le coffre n'est pas vide**, depuis la v1.27.2 et jusqu'a cette version.
 *
 * La question a poser n'etait pas « la session est-elle ouverte ? » mais « y a-t-il un second
 * facteur, et l'a-t-on prouve ? ». Ce type repond a la premiere moitie.
 *
 * # Le meme calcul que la porte du coffre
 *
 * Les trois branches reproduisent exactement `VaultViewModel.entryGate` et l'effet d'entree qui
 * la consomme : PIN de coffre s'il est actif ET qu'une empreinte existe (drapeau seul, hash
 * absent = restauration incoherente, traite comme absent) ; sinon biometrie si c'est le mode de
 * verrouillage de l'application ; sinon rien.
 *
 * ⚠️ Deux copies d'une meme regle finissent par diverger, et la divergence de deux portes qui
 * gardent la meme chose est le motif qui a produit les vrais defauts de ce depot. Si l'une des
 * deux change, l'autre doit changer avec — a terme, `VaultScreen` devrait consommer CE type
 * plutot que recalculer sa porte.
 */
@Singleton
class VaultSecondFactorPolicy @Inject constructor(
    private val settings: SettingsRepository,
    private val vaultPin: VaultPinManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun current(): VaultSecondFactor = withContext(io) {
        val security = settings.flow.first().security
        when {
            security.vaultPinEnabled && vaultPin.isVaultPinConfigured() -> VaultSecondFactor.PIN
            security.lockMode == LockMode.BIOMETRIC -> VaultSecondFactor.BIOMETRIC
            else -> VaultSecondFactor.NONE
        }
    }
}
