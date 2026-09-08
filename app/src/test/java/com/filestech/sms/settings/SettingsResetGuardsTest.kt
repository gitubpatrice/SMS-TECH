package com.filestech.sms.settings

import android.content.Context
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.VaultPurgeResult
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.LockMode
import com.filestech.sms.domain.settings.ThemeMode
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.security.VaultPinManager
import com.filestech.sms.ui.screens.settings.SettingsViewModel
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * v1.27.11 — verrouille les constats 1 et 2 de la revue externe GitLab !38458 sur les deux
 * chemins qui, autour du PIN du coffre, retiraient la protection **sans jamais la demander**.
 *
 * La v1.27.10 avait ferme les portes evidentes : on ne remplace ni ne retire le PIN du coffre
 * sans le connaitre. Le relecteur est alors alle voir les operations VOISINES, et les deux qu'il
 * a trouvees menaient au meme resultat par un autre chemin :
 *
 *  1. **« Reinitialiser tous les reglages »** ecrivait `AppSettings()` nu. Les empreintes des PIN
 *     vivent hors des reglages : elles survivaient, mais `lockMode` et `vaultPinEnabled`
 *     revenaient a leurs defauts, si bien que plus personne ne les consultait. Le coffre restait
 *     plein et s'ouvrait sans rien demander. Le bouton etant hors du garde `!isPanicDecoy`, une
 *     session leurre en sortait en trois tapes.
 *  2. **La porte de sortie « PIN oublie »** retirait le PIN quel que soit le sort de la purge —
 *     y compris quand celle-ci n'avait rien efface du tout.
 *
 * Les deux assertions portent sur [SettingsViewModel] et non sur l'ecran : c'est precisement
 * l'erreur que la v1.27.10 avait deja commise une fois, en posant sa garde dans un dialogue de
 * confirmation. Un garde d'ecran ne dit rien du prochain point d'entree.
 */
class SettingsResetGuardsTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** Instantane des reglages, mute par le faux [SettingsRepository] comme le ferait DataStore. */
    private var stored = AppSettings()
    private val settingsFlow = MutableStateFlow(AppSettings())

    private val settings: SettingsRepository = mockk(relaxed = true) {
        every { flow } returns settingsFlow
        coEvery { update(any()) } answers {
            stored = firstArg<(AppSettings) -> AppSettings>().invoke(stored)
            settingsFlow.value = stored
        }
    }

    private val conversationRepo: ConversationRepository = mockk(relaxed = true)
    private val vaultPin: VaultPinManager = mockk(relaxed = true)
    private val appLock: AppLockManager = mockk(relaxed = true) {
        every { state } returns MutableStateFlow(AppLockManager.LockState.Unlocked)
    }

    private fun viewModel() = SettingsViewModel(
        settings = settings,
        telephonySyncManager = mockk(relaxed = true),
        defaultAppManager = mockk(relaxed = true),
        panic = mockk(relaxed = true),
        appLock = appLock,
        blockedImporter = mockk(relaxed = true),
        conversationRepo = conversationRepo,
        vaultPin = vaultPin,
        context = mockk<Context>(relaxed = true),
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─────────────────────────── Constat 1 : reinitialiser les reglages ───────────────────────────

    @Test
    fun `resetting the preferences keeps the lock and the vault PIN armed`() = runTest(dispatcher) {
        stored = AppSettings().let {
            it.copy(
                appearance = it.appearance.copy(themeMode = ThemeMode.DARK_TECH),
                security = it.security.copy(
                    lockMode = LockMode.PIN,
                    vaultPinEnabled = true,
                    panicCodeEnabled = true,
                ),
            )
        }

        viewModel().resetAll()

        // Ce que la reinitialisation DOIT preserver — sans quoi le coffre s'ouvre tout seul et
        // `AppLockManager.resolveInitialState`, qui ne lit que `lockMode`, conclut `Disabled`.
        assertThat(stored.security.lockMode).isEqualTo(LockMode.PIN)
        assertThat(stored.security.vaultPinEnabled).isTrue()
        assertThat(stored.security.panicCodeEnabled).isTrue()
    }

    @Test
    fun `resetting the preferences still restores the comfort defaults`() = runTest(dispatcher) {
        // Le pendant du test precedent : preserver la securite ne doit pas vider le bouton de sa
        // fonction. Sans cette assertion, un `resetAll` devenu no-op passerait la premiere.
        stored = AppSettings().let {
            it.copy(appearance = it.appearance.copy(themeMode = ThemeMode.DARK_TECH))
        }

        viewModel().resetAll()

        assertThat(stored.appearance.themeMode).isEqualTo(AppSettings().appearance.themeMode)
    }

    // ──────────────────── Constat 2 : la porte de sortie « PIN du coffre oublie » ────────────────────

    @Test
    fun `the forgotten-PIN exit keeps the PIN when the purge left something behind`() =
        runTest(dispatcher) {
            // Une conversation effacee, une dont la copie systeme a survecu : elle reviendra a la
            // resynchronisation suivante, hors du coffre. Le PIN ne doit pas partir.
            coEvery { conversationRepo.deleteAllInVault(force = false) } returns
                VaultPurgeResult(deleted = 1, failed = 1, remaining = 0)

            val vm = viewModel()
            vm.forgetVaultPinAndPurge()

            coVerify(exactly = 0) { vaultPin.forgetVaultPin() }
            assertThat(vm.events.first())
                .isInstanceOf(SettingsViewModel.Event.VaultPurgeIncomplete::class.java)
        }

    @Test
    fun `the forgotten-PIN exit keeps the PIN when a conversation entered during the purge`() =
        runTest(dispatcher) {
            // Rien n'a echoue, et pourtant le coffre n'est pas vide : c'est le cas que ni le
            // compte de succes ni celui d'echecs ne voit, et que `remaining` releve.
            coEvery { conversationRepo.deleteAllInVault(force = false) } returns
                VaultPurgeResult(deleted = 3, failed = 0, remaining = 1)

            val vm = viewModel()
            vm.forgetVaultPinAndPurge()

            coVerify(exactly = 0) { vaultPin.forgetVaultPin() }
        }

    @Test
    fun `the forgotten-PIN exit removes the PIN once the vault is demonstrably empty`() =
        runTest(dispatcher) {
            // Le controle positif : sans lui, une garde qui refuserait TOUJOURS passerait les
            // deux tests ci-dessus tout en condamnant la seule issue de l'utilisateur.
            coEvery { conversationRepo.deleteAllInVault(force = false) } returns
                VaultPurgeResult(deleted = 4, failed = 0, remaining = 0)

            val vm = viewModel()
            vm.forgetVaultPinAndPurge()

            coVerify(exactly = 1) { vaultPin.forgetVaultPin() }
            assertThat(vm.events.first())
                .isInstanceOf(SettingsViewModel.Event.VaultPurged::class.java)
            // Le drapeau d'echec ne doit pas survivre a une purge reussie : sinon le prochain PIN
            // de coffre que l'utilisateur configurerait se verrait offrir la sortie degradee
            // des son premier ennui.
            assertThat(stored.security.vaultPurgeFailedOnce).isFalse()
        }

    // ───────────── v1.28.2 : le refus prudent ne doit pas devenir une impasse definitive ─────────────

    /**
     * Premier echec : on retient qu'il a eu lieu, mais on ne propose RIEN d'autre que de
     * reessayer. Une panne passagere — role SMS momentanement perdu — se leve d'elle-meme, et
     * offrir tout de suite la sortie degradee pousserait a detruire plus que necessaire.
     */
    @Test
    fun `the first failure only remembers it happened`() = runTest(dispatcher) {
        coEvery { conversationRepo.deleteAllInVault(force = false) } returns
            VaultPurgeResult(deleted = 0, failed = 1, remaining = 1)

        val vm = viewModel()
        vm.forgetVaultPinAndPurge()

        coVerify(exactly = 0) { vaultPin.forgetVaultPin() }
        assertThat(vm.events.first())
            .isInstanceOf(SettingsViewModel.Event.VaultPurgeIncomplete::class.java)
        assertThat(stored.security.vaultPurgeFailedOnce).isTrue()
    }

    /**
     * Second echec : celui-la ne se levera pas tout seul — c'est en general une liaison restauree
     * d'un autre telephone, que rien dans l'application ne repare. On ouvre la sortie, **sans
     * rien retirer encore** : c'est une proposition, pas une action.
     */
    @Test
    fun `the second failure offers the way out without taking it`() = runTest(dispatcher) {
        stored = stored.copy(security = stored.security.copy(vaultPurgeFailedOnce = true))
        settingsFlow.value = stored
        coEvery { conversationRepo.deleteAllInVault(force = false) } returns
            VaultPurgeResult(deleted = 0, failed = 1, remaining = 1)

        val vm = viewModel()
        vm.forgetVaultPinAndPurge()

        // LE point : le PIN reste, tant que l'utilisateur n'a pas dit oui.
        coVerify(exactly = 0) { vaultPin.forgetVaultPin() }
        assertThat(vm.events.first())
            .isInstanceOf(SettingsViewModel.Event.VaultPurgeStuck::class.java)
    }

    /**
     * La sortie assumee : l'utilisateur a dit oui apres qu'on lui a annonce ce qui subsisterait.
     * Le PIN part, et **ce qui reste lui est redit** — c'est la contrepartie, elle ne se tait pas.
     */
    @Test
    fun `the deliberate way out removes the PIN and states what survives`() = runTest(dispatcher) {
        stored = stored.copy(security = stored.security.copy(vaultPurgeFailedOnce = true))
        settingsFlow.value = stored
        coEvery { conversationRepo.deleteAllInVault(force = true) } returns
            VaultPurgeResult(deleted = 2, failed = 1, remaining = 0)

        val vm = viewModel()
        vm.forgetVaultPinAndPurge(force = true)

        coVerify(exactly = 1) { vaultPin.forgetVaultPin() }
        val evenement = vm.events.first()
        assertThat(evenement)
            .isInstanceOf(SettingsViewModel.Event.VaultPurgedWithResidue::class.java)
        // Le nombre annonce est celui qui SUBSISTE, pas celui qui est parti.
        assertThat((evenement as SettingsViewModel.Event.VaultPurgedWithResidue).left).isEqualTo(1)
        assertThat(stored.security.vaultPurgeFailedOnce).isFalse()
    }

    /**
     * Controle negatif de la sortie assumee : elle ne doit pas devenir le chemin ordinaire. Sans
     * `force`, une purge incomplete ne retire toujours rien — c'est la garde de la v1.28.1, et
     * elle doit survivre a l'ajout de la sortie.
     */
    @Test
    fun `the way out is never taken on the caller's behalf`() = runTest(dispatcher) {
        coEvery { conversationRepo.deleteAllInVault(force = false) } returns
            VaultPurgeResult(deleted = 0, failed = 2, remaining = 2)

        val vm = viewModel()
        vm.forgetVaultPinAndPurge()
        vm.events.first()
        vm.forgetVaultPinAndPurge()

        coVerify(exactly = 0) { vaultPin.forgetVaultPin() }
        coVerify(exactly = 0) { conversationRepo.deleteAllInVault(force = true) }
    }
}
