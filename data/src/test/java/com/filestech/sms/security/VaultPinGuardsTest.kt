package com.filestech.sms.security

import com.filestech.sms.core.crypto.PasswordKdf
import com.filestech.sms.data.local.datastore.SecurityStore
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.settings.AppSettings
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * v1.27.10 — verrouille la correction de la revue externe GitLab !38458 sur le PIN du coffre.
 *
 * **Le défaut, tel qu'il a été reproduit sur émulateur Android 14 par le relecteur** : le PIN du
 * coffre pouvait être remplacé, ou retiré, sans qu'on demande jamais celui en place. Quiconque
 * connaissait le PIN d'application — c'est-à-dire exactement la personne contre laquelle ce
 * second facteur existe — ouvrait donc le coffre en deux tapes dans les Réglages.
 *
 * Ces assertions sont posées sur [VaultPinManager], **hors de toute interface**, et c'est le
 * point : la version fautive avait sa garde dans l'écran (une simple boîte de confirmation), pas
 * dans la couche de sécurité. Un futur remaniement des Réglages qui rebrancherait le dialogue de
 * création sur un coffre déjà protégé ferait échouer [replacing the vault PIN without the current
 * one is refused], pas seulement changer l'aspect d'un écran.
 *
 * La temporisation (constat 3 de la même revue) est vérifiée au même endroit et pour la même
 * raison : sans elle, un PIN à 4 chiffres se parcourt entièrement contre un PBKDF2 local.
 */
class VaultPinGuardsTest {

    private val io = UnconfinedTestDispatcher()

    // ──────────── Un SecurityStore en mémoire : DataStore n'existe pas hors appareil ────────────

    private var pinSnapshot: SecurityStore.PinSnapshot? = null
    private var storedVaultFails = 0
    private var lockout = SecurityStore.LockoutSnapshot(0L, 0L, 0L)

    private val store: SecurityStore = mockk(relaxed = true) {
        coEvery { vaultPinSnapshot() } answers { pinSnapshot }
        coEvery { setVaultPinHash(any(), any(), any()) } answers {
            pinSnapshot = SecurityStore.PinSnapshot(arg(0), arg(1), arg(2))
        }
        coEvery { clearVaultPin() } answers {
            pinSnapshot = null
            storedVaultFails = 0
            lockout = SecurityStore.LockoutSnapshot(0L, 0L, 0L)
            mockk(relaxed = true)
        }
        coEvery { vaultLockoutSnapshot() } answers { lockout }
        coEvery { setVaultLockout(any(), any(), any()) } answers {
            lockout = SecurityStore.LockoutSnapshot(arg(0), arg(2), arg(1))
            mockk(relaxed = true)
        }
        coEvery { clearVaultLockout() } answers {
            storedVaultFails = 0
            lockout = SecurityStore.LockoutSnapshot(0L, 0L, 0L)
            mockk(relaxed = true)
        }
        coEvery { setVaultFailCount(any()) } answers {
            storedVaultFails = arg(0)
            mockk(relaxed = true)
        }
        every { vaultFailCount } answers { MutableStateFlow(storedVaultFails) }
    }

    /**
     * Le drapeau `vaultPinEnabled` vit dans les réglages, le hash dans le store. Les deux se
     * suivent, et [VaultPinManager.configureVaultPin] lit les DEUX pour décider s'il y a un
     * coffre à protéger — d'où ce faux qui les tient réellement à jour.
     */
    private val settingsState = MutableStateFlow(AppSettings())
    private val settings: SettingsRepository = mockk(relaxed = true) {
        every { flow } returns settingsState
        coEvery { update(any()) } answers {
            settingsState.value = firstArg<(AppSettings) -> AppSettings>()(settingsState.value)
        }
    }

    /**
     * Le vrai PBKDF2 serait correct mais coûte ~300 ms par dérivation, calibration comprise :
     * une dizaine de tests deviendraient une dizaine de secondes. On le remplace par une
     * empreinte déterministe — ce qu'on vérifie ici n'est pas la robustesse du KDF (couverte
     * ailleurs) mais **qui a le droit d'écrire un nouveau hash**.
     */
    private val kdf: PasswordKdf = mockk {
        every { newSalt() } returns ByteArray(16) { 7 }
        every { calibrate(any()) } returns 210_000
        every { derive(any(), any(), any(), any()) } answers {
            firstArg<CharArray>().concatToString().encodeToByteArray()
        }
    }

    private fun manager() = VaultPinManager(store, settings, kdf, io)

    private suspend fun configure(pin: String) {
        assertThat(manager().configureVaultPin(pin.toCharArray())).isTrue()
    }

    // ──────────── Remplacement : le PIN en place est exigé ────────────

    @Test fun `replacing the vault PIN without the current one is refused`() = runTest {
        configure("1234")
        val hashBefore = pinSnapshot!!.hash.copyOf()

        // Le chemin de création refuse un coffre déjà gardé : c'est la garde de dernier recours,
        // celle qui tient même si un écran rebranche par mégarde le mauvais dialogue.
        val accepted = manager().configureVaultPin("9999".toCharArray())

        assertThat(accepted).isFalse()
        assertThat(pinSnapshot!!.hash).isEqualTo(hashBefore)
    }

    @Test fun `replacing the vault PIN with a wrong current one is refused`() = runTest {
        configure("1234")
        val hashBefore = pinSnapshot!!.hash.copyOf()

        val verdict = manager().changeVaultPin("0000".toCharArray(), "9999".toCharArray())

        assertThat(verdict).isEqualTo(PinVerdict.Invalid)
        assertThat(pinSnapshot!!.hash).isEqualTo(hashBefore)
    }

    @Test fun `replacing the vault PIN with the correct current one succeeds`() = runTest {
        configure("1234")

        val verdict = manager().changeVaultPin("1234".toCharArray(), "9999".toCharArray())

        assertThat(verdict).isEqualTo(PinVerdict.Ok)
        assertThat(manager().verifyVaultPin("9999".toCharArray())).isEqualTo(PinVerdict.Ok)
        assertThat(manager().verifyVaultPin("1234".toCharArray())).isEqualTo(PinVerdict.Invalid)
    }

    // ──────────── Retrait : le PIN en place est exigé lui aussi ────────────

    @Test fun `disabling the vault PIN with a wrong one leaves the gate standing`() = runTest {
        configure("1234")

        val verdict = manager().disableVaultPin("0000".toCharArray())

        assertThat(verdict).isEqualTo(PinVerdict.Invalid)
        assertThat(pinSnapshot).isNotNull()
        // LE défaut de la v1.27.9 : la bascule OFF retirait le hash ET le drapeau sans rien
        // demander, si bien que le coffre s'ouvrait ensuite pour qui connaissait le PIN d'app.
        assertThat(settingsState.value.security.vaultPinEnabled).isTrue()
    }

    @Test fun `disabling the vault PIN with the correct one clears hash and flag`() = runTest {
        configure("1234")

        val verdict = manager().disableVaultPin("1234".toCharArray())

        assertThat(verdict).isEqualTo(PinVerdict.Ok)
        assertThat(pinSnapshot).isNull()
        assertThat(settingsState.value.security.vaultPinEnabled).isFalse()
    }

    /**
     * La porte de sortie « PIN oublié » n'exige rien — elle n'a donc le droit d'exister que
     * parce que l'appelant a détruit le contenu du coffre juste avant. Le test fige le contrat
     * côté manager ; la destruction elle-même est le fait de `SettingsViewModel`.
     */
    @Test fun `the forgotten-PIN exit clears the gate without any secret`() = runTest {
        configure("1234")

        manager().forgetVaultPin()

        assertThat(pinSnapshot).isNull()
        assertThat(settingsState.value.security.vaultPinEnabled).isFalse()
    }

    // ──────────── Temporisation dédiée (constat 3) ────────────

    @Test fun `a streak of wrong vault PINs arms a lockout`() = runTest {
        configure("1234")
        val m = manager()

        repeat(AppLockManager.LOCKOUT_THRESHOLD - 1) {
            assertThat(m.verifyVaultPin("0000".toCharArray())).isEqualTo(PinVerdict.Invalid)
        }
        val verdict = m.verifyVaultPin("0000".toCharArray())

        assertThat(verdict).isInstanceOf(PinVerdict.LockedOut::class.java)
        assertThat(m.vaultLockoutRemainingMs()).isGreaterThan(0L)
    }

    @Test fun `a lockout refuses even the correct vault PIN`() = runTest {
        configure("1234")
        val m = manager()
        repeat(AppLockManager.LOCKOUT_THRESHOLD) { m.verifyVaultPin("0000".toCharArray()) }

        // Le bon PIN lui-même attend : sinon la temporisation ne coûterait rien à qui la
        // déclenche par une recherche exhaustive, il lui suffirait de continuer.
        assertThat(m.verifyVaultPin("1234".toCharArray()))
            .isInstanceOf(PinVerdict.LockedOut::class.java)
    }

    @Test fun `the lockout also covers replacement and removal`() = runTest {
        configure("1234")
        val m = manager()
        repeat(AppLockManager.LOCKOUT_THRESHOLD) { m.verifyVaultPin("0000".toCharArray()) }

        // Sans cette propriété, la temporisation serait contournable en martelant le dialogue de
        // changement plutôt que celui d'ouverture — deux portes, un seul garde.
        assertThat(m.changeVaultPin("1234".toCharArray(), "9999".toCharArray()))
            .isInstanceOf(PinVerdict.LockedOut::class.java)
        assertThat(m.disableVaultPin("1234".toCharArray()))
            .isInstanceOf(PinVerdict.LockedOut::class.java)
        assertThat(pinSnapshot).isNotNull()
    }

    @Test fun `a correct vault PIN clears the failure streak`() = runTest {
        configure("1234")
        val m = manager()
        repeat(AppLockManager.LOCKOUT_THRESHOLD - 1) { m.verifyVaultPin("0000".toCharArray()) }

        assertThat(m.verifyVaultPin("1234".toCharArray())).isEqualTo(PinVerdict.Ok)

        assertThat(storedVaultFails).isEqualTo(0)
        assertThat(m.vaultLockoutRemainingMs()).isEqualTo(0L)
    }

    // ──────────── L'incohérence documentée « drapeau ON, hash absent » ────────────

    @Test fun `a flag without a hash does not lock the user out of their own vault`() = runTest {
        // État atteignable après une restauration partielle : le drapeau est posé, le hash non.
        settingsState.value = AppSettings().let {
            it.copy(security = it.security.copy(vaultPinEnabled = true))
        }

        // Il n'y a aucun secret à prouver : refuser ici enfermerait l'utilisateur dehors sans
        // que rien ne soit gardé.
        assertThat(manager().configureVaultPin("1234".toCharArray())).isTrue()
        assertThat(manager().verifyVaultPin("1234".toCharArray())).isEqualTo(PinVerdict.Ok)
    }

    // ──────────── Hygiène des secrets ────────────

    @Test fun `every entry point wipes the CharArray it was handed`() = runTest {
        val created = "1234".toCharArray()
        manager().configureVaultPin(created)
        assertThat(created).isEqualTo(CharArray(4))

        val wrong = "0000".toCharArray()
        manager().verifyVaultPin(wrong)
        assertThat(wrong).isEqualTo(CharArray(4))

        val current = "1234".toCharArray()
        val fresh = "9999".toCharArray()
        manager().changeVaultPin(current, fresh)
        assertThat(current).isEqualTo(CharArray(4))
        assertThat(fresh).isEqualTo(CharArray(4))

        // Y compris sur le chemin de REFUS : c'est celui qu'un attaquant emprunte le plus.
        val refused = "0000".toCharArray()
        val unused = "8888".toCharArray()
        manager().changeVaultPin(refused, unused)
        assertThat(refused).isEqualTo(CharArray(4))
        assertThat(unused).isEqualTo(CharArray(4))
    }
}
