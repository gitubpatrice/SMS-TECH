package com.filestech.sms.system.scheduler

import androidx.work.WorkInfo.State.BLOCKED
import androidx.work.WorkInfo.State.CANCELLED
import androidx.work.WorkInfo.State.ENQUEUED
import androidx.work.WorkInfo.State.FAILED
import androidx.work.WorkInfo.State.RUNNING
import androidx.work.WorkInfo.State.SUCCEEDED
import com.filestech.sms.system.scheduler.ArriveesDuProfilPrincipalWorker.Companion.OBSERVATION_A
import com.filestech.sms.system.scheduler.ArriveesDuProfilPrincipalWorker.Companion.OBSERVATION_B
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-11-27 (issue #98, relectures Claude H1 et GPT) — après chaque passe, UNE observation du stockage attend un
 * changement ([observationAArmer]).
 *
 * Ce que ces cas fixent : une observation en attente (`ENQUEUED`) ou placée derrière une passe (`BLOCKED`) suffit ;
 * une observation EN COURS ne compte pas, elle a déjà servi. Le cas qui éteignait la chaîne : deux observations qui
 * tournent en même temps.
 */
class ObservationAArmerTest {

    @Test
    fun rienNAttend_auDemarrage_laPremiereEstArmee() {
        assertThat(observationAArmer(emptyList(), emptyList(), soi = null)).isEqualTo(OBSERVATION_A)
        assertThat(observationAArmer(listOf(SUCCEEDED), listOf(SUCCEEDED), soi = null)).isEqualTo(OBSERVATION_A)
        assertThat(observationAArmer(listOf(CANCELLED), listOf(FAILED), soi = null)).isEqualTo(OBSERVATION_A)
    }

    @Test
    fun uneObservation_armeLAutreNom() {
        assertThat(observationAArmer(listOf(RUNNING), listOf(SUCCEEDED), soi = OBSERVATION_A)).isEqualTo(OBSERVATION_B)
        assertThat(observationAArmer(listOf(SUCCEEDED), listOf(RUNNING), soi = OBSERVATION_B)).isEqualTo(OBSERVATION_A)
    }

    @Test
    fun uneObservationQuiAttend_suffit() {
        assertThat(observationAArmer(listOf(RUNNING), listOf(ENQUEUED), soi = OBSERVATION_A)).isNull()
        assertThat(observationAArmer(listOf(SUCCEEDED, BLOCKED), listOf(RUNNING), soi = OBSERVATION_B)).isNull()
        assertThat(observationAArmer(listOf(ENQUEUED), emptyList(), soi = null)).isNull()
    }

    /** Le cas qui éteignait la chaîne : l'une a déclenché l'autre pendant qu'elle tournait. Il faut une suivante. */
    @Test
    fun deuxObservationsEnCours_neLaissentPasLaChaineSEteindre() {
        assertThat(observationAArmer(listOf(RUNNING), listOf(RUNNING), soi = OBSERVATION_A)).isEqualTo(OBSERVATION_B)
        assertThat(observationAArmer(listOf(RUNNING), listOf(RUNNING), soi = OBSERVATION_B)).isEqualTo(OBSERVATION_A)
        assertThat(observationAArmer(listOf(RUNNING), listOf(RUNNING), soi = null)).isEqualTo(OBSERVATION_A)
    }

    /** Le retour au premier plan arme A pendant que B attend déjà : la règle n'en arme pas une troisième. */
    @Test
    fun deuxObservationsEnAttente_rienDePlus() {
        assertThat(observationAArmer(listOf(ENQUEUED), listOf(ENQUEUED), soi = null)).isNull()
    }
}
