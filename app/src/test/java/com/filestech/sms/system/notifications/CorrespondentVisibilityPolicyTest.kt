package com.filestech.sms.system.notifications

import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.NotificationSettings
import com.filestech.sms.domain.settings.PreviewMode
import com.filestech.sms.security.AppLockManager
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * v1.28.3 — **la politique de rédaction des notifications, prouvée une fois pour toutes.**
 *
 * Elle vivait en deux exemplaires : `IncomingMessageNotifier` la portait, `MmsFailureNotifier`
 * pas du tout — c'est le finding F08 — et le notificateur d'échec d'ENVOI allait en demander un
 * troisième. C'est le motif dominant de cette relecture, qui s'est reproduit trois fois pendant
 * les corrections elles-mêmes : *une règle écrite, mais posée sur un seul des chemins qui en
 * avaient besoin*.
 *
 * D'où [CorrespondentVisibilityPolicy], et d'où ce fichier : une politique unique n'a de valeur
 * que si elle est vérifiée pour elle-même. Le repli est la moitié qui compte le plus — toute
 * lecture qui échoue doit faire **taire** la notification, jamais l'inverse.
 */
class CorrespondentVisibilityPolicyTest {

    private val settings = mockk<SettingsRepository>()
    private val appLock = mockk<AppLockManager>()
    private val conversationDao = mockk<ConversationDao>()

    private fun politique() = CorrespondentVisibilityPolicy(settings, appLock, conversationDao)

    private fun etatVerrou(state: AppLockManager.LockState) {
        every { appLock.state } returns MutableStateFlow(state)
    }

    private fun reglages(mode: PreviewMode) {
        coEvery { settings.hydratedOrNull() } returns AppSettings(
            notifications = NotificationSettings(previewMode = mode),
        )
    }

    private fun coffre(vararg adresses: String) {
        coEvery { conversationDao.snapshotVaultOneToOne() } returns adresses.mapIndexed { i, a ->
            ConversationEntity(
                id = (i + 1).toLong(),
                threadId = null,
                addressesCsv = a,
                displayName = null,
                lastMessageAt = 0L,
                lastMessagePreview = null,
                inVault = true,
            )
        }
    }

    @Test
    fun `en session leurre on ne poste rien du tout`() = runTest {
        etatVerrou(AppLockManager.LockState.PanicDecoy)

        assertThat(politique().verdictPour("+33611111111"))
            .isEqualTo(CorrespondentVisibilityPolicy.Verdict.TAIRE)
    }

    @Test
    fun `un correspondant du coffre ne produit aucune notification`() = runTest {
        etatVerrou(AppLockManager.LockState.Unlocked)
        coffre("+33611111111")
        reglages(PreviewMode.ALWAYS)

        assertThat(politique().verdictPour("+33611111111"))
            .isEqualTo(CorrespondentVisibilityPolicy.Verdict.TAIRE)
    }

    /**
     * Le rapprochement passe par la clé numérique et non par l'égalité de chaîne : une adresse
     * arrive sous des formes qui varient d'un chemin à l'autre, et une comparaison stricte
     * rendrait la garde inopérante exactement là où elle sert.
     */
    @Test
    fun `le coffre est reconnu sous une autre notation du meme numero`() = runTest {
        etatVerrou(AppLockManager.LockState.Unlocked)
        coffre("+33611111111")
        reglages(PreviewMode.ALWAYS)

        assertThat(politique().verdictPour("06 11 11 11 11"))
            .isEqualTo(CorrespondentVisibilityPolicy.Verdict.TAIRE)
    }

    @Test
    fun `apercus masques anonymise sans faire taire`() = runTest {
        etatVerrou(AppLockManager.LockState.Unlocked)
        coffre()
        reglages(PreviewMode.NEVER)

        assertThat(politique().verdictPour("+33611111111"))
            .isEqualTo(CorrespondentVisibilityPolicy.Verdict.ANONYMISER)
    }

    /**
     * Contrôle POSITIF, sans lequel rien de ce fichier ne prouverait quoi que ce soit : une
     * politique qui ferait toujours taire passerait tous les tests ci-dessus **et** supprimerait
     * la fonctionnalité entière.
     */
    @Test
    fun `un correspondant ordinaire est nomme`() = runTest {
        etatVerrou(AppLockManager.LockState.Unlocked)
        coffre()
        reglages(PreviewMode.ALWAYS)

        assertThat(politique().verdictPour("+33611111111"))
            .isEqualTo(CorrespondentVisibilityPolicy.Verdict.NOMMER)
    }

    /**
     * **Le repli, et c'est la moitié qui compte le plus.** Une base illisible ne doit pas faire
     * afficher le nom d'un correspondant protégé sur un écran verrouillé. Le coût du repli sûr
     * est une notification manquée ; celui du repli permissif ne se compare pas.
     */
    @Test
    fun `une lecture du coffre impossible fait taire`() = runTest {
        etatVerrou(AppLockManager.LockState.Unlocked)
        coEvery { conversationDao.snapshotVaultOneToOne() } throws IllegalStateException("base illisible")
        reglages(PreviewMode.ALWAYS)

        assertThat(politique().verdictPour("+33611111111"))
            .isEqualTo(CorrespondentVisibilityPolicy.Verdict.TAIRE)
    }

    /** Des réglages illisibles ne font pas taire, mais anonymisent — le repli le plus prudent. */
    @Test
    fun `des reglages illisibles anonymisent`() = runTest {
        etatVerrou(AppLockManager.LockState.Unlocked)
        coffre()
        coEvery { settings.hydratedOrNull() } throws IllegalStateException("reglages illisibles")

        assertThat(politique().verdictPour("+33611111111"))
            .isEqualTo(CorrespondentVisibilityPolicy.Verdict.ANONYMISER)
    }

    /** Adresse absente : rien à protéger, mais rien à nommer non plus. */
    @Test
    fun `une adresse absente est anonymisee`() = runTest {
        etatVerrou(AppLockManager.LockState.Unlocked)

        assertThat(politique().verdictPour(null))
            .isEqualTo(CorrespondentVisibilityPolicy.Verdict.ANONYMISER)
    }

    // ── v1.28.3, audit global A-01 : la conversation connue fait foi avant l'adresse ──────

    private fun conversation(id: Long, inVault: Boolean, adresses: String) {
        coEvery { conversationDao.findById(id) } returns ConversationEntity(
            id = id,
            threadId = null,
            addressesCsv = adresses,
            displayName = null,
            lastMessageAt = 0L,
            lastMessagePreview = null,
            inVault = inVault,
        )
    }

    /**
     * Le rapprochement par adresse ne regarde que les 1-à-1 : une ligne rattachée à un GROUPE du
     * coffre lui échapperait. Ici l'adresse ne correspond à aucune 1-à-1 du coffre ; seul l'id
     * de conversation sait qu'il faut se taire.
     */
    @Test
    fun `une conversation du coffre connue par son id fait taire meme si l'adresse ne dit rien`() = runTest {
        etatVerrou(AppLockManager.LockState.Unlocked)
        coffre()
        reglages(PreviewMode.ALWAYS)
        conversation(7L, inVault = true, adresses = "+33611111111;+33622222222")

        assertThat(politique().verdictPour("+33611111111", conversationId = 7L))
            .isEqualTo(CorrespondentVisibilityPolicy.Verdict.TAIRE)
    }

    /** Contrôle positif : une conversation connue HORS coffre ne change rien au verdict. */
    @Test
    fun `une conversation hors coffre connue par son id laisse nommer`() = runTest {
        etatVerrou(AppLockManager.LockState.Unlocked)
        coffre()
        reglages(PreviewMode.ALWAYS)
        conversation(7L, inVault = false, adresses = "+33611111111")

        assertThat(politique().verdictPour("+33611111111", conversationId = 7L))
            .isEqualTo(CorrespondentVisibilityPolicy.Verdict.NOMMER)
    }

    @Test
    fun `une conversation illisible fait taire`() = runTest {
        etatVerrou(AppLockManager.LockState.Unlocked)
        coEvery { conversationDao.findById(7L) } throws IllegalStateException("base illisible")

        assertThat(politique().verdictPour("+33611111111", conversationId = 7L))
            .isEqualTo(CorrespondentVisibilityPolicy.Verdict.TAIRE)
    }

    // ── v1.28.3, audit global D-02 : ce qui paraît sur l'écran verrouillé ─────────────────

    @Test
    fun `l'ecran verrouille suit le reglage d'apercu`() = runTest {
        etatVerrou(AppLockManager.LockState.Unlocked)

        reglages(PreviewMode.ALWAYS)
        assertThat(politique().ecranVerrouille())
            .isEqualTo(CorrespondentVisibilityPolicy.EcranVerrouille.PUBLIC)
        reglages(PreviewMode.WHEN_UNLOCKED)
        assertThat(politique().ecranVerrouille())
            .isEqualTo(CorrespondentVisibilityPolicy.EcranVerrouille.PRIVE)
        reglages(PreviewMode.NEVER)
        assertThat(politique().ecranVerrouille())
            .isEqualTo(CorrespondentVisibilityPolicy.EcranVerrouille.SECRET)
    }

    /** Le repli va dans le même sens que tout le reste : illisible = rien ne paraît. */
    @Test
    fun `des reglages illisibles rendent l'ecran secret`() = runTest {
        etatVerrou(AppLockManager.LockState.Unlocked)
        coEvery { settings.hydratedOrNull() } throws IllegalStateException("reglages illisibles")

        assertThat(politique().ecranVerrouille())
            .isEqualTo(CorrespondentVisibilityPolicy.EcranVerrouille.SECRET)
    }
}
