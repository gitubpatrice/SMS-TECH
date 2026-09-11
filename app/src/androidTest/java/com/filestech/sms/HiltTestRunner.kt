package com.filestech.sms

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Custom Android JUnit runner that swaps the real [MainApplication] for [HiltTestApplication]
 * so that Hilt injects test modules. Referenced from app/build.gradle.kts.
 *
 * v1.28.5 — **WorkManager est initialisé dans le processus de test.** Le manifeste retire
 * l'initialiseur automatique (l'application réelle implémente `Configuration.Provider`), mais
 * [HiltTestApplication] ne l'implémente pas. Or le système peut réveiller `SystemJobService`
 * dans CE processus — un travail périodique planifié par le paquet debug installé juste avant —
 * et `WorkManagerImpl.getInstance` lève alors « not initialized properly » : processus tué,
 * campagne arrêtée à 74 cas sur 131, mesuré le 2026-09-11. Le runner d'Andrew l'initialisait
 * explicitement pour la même raison. Un test qui a besoin d'une fabrique Hilt réinitialise à sa
 * façon ; `runCatching` laisse passer une seconde initialisation.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)

    override fun callApplicationOnCreate(app: Application) {
        super.callApplicationOnCreate(app)
        runCatching {
            WorkManager.initialize(app, Configuration.Builder().setMinimumLoggingLevel(Log.WARN).build())
        }
    }
}
