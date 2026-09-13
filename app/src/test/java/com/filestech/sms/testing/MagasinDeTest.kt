package com.filestech.sms.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v1.28.8 — un magasin de préférences PROPRE AU TEST, dans son dossier temporaire.
 *
 * Construire `SettingsRepository(context, …)` ou `SecurityStore(context)` sous Robolectric passe
 * par le délégué `preferencesDataStore`, qui garde la première instance de la JVM : tous les tests
 * partageaient alors un seul fichier, et leurs réglages avec. Sous Windows, une lecture en vol d'un
 * test faisait échouer le remplacement du fichier par l'écriture d'un autre — mesuré : 39 échecs
 * « Unable to rename » sur 400 écritures avec des lecteurs concurrents, 0 sans lecteur.
 *
 * [portee] porte la lecture et l'écriture du magasin : l'annuler en fin de test l'arrête.
 */
internal fun magasinDeTest(dossier: TemporaryFolder, portee: CoroutineScope): DataStore<Preferences> {
    val fichier = File(dossier.newFolder(), "test.preferences_pb")
    return PreferenceDataStoreFactory.create(scope = portee) { fichier }
}
