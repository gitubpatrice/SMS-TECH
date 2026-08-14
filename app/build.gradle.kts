import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
}

// La version publiee vient de `version.properties`, versionne a la racine — jamais de l'historique.
//
// Elle etait derivee de `git rev-list --count HEAD`. Deux defauts, mesures le 2026-08-08 :
//   - un rebase / squash / une greffe d'historique fait BAISSER le versionCode, et Android refuse
//     alors d'installer par-dessus : toutes les installations existantes cessent de se mettre a jour ;
//   - hors depot git (archive source, tarball), `runCatching` retombait sur `getOrDefault(1)` et
//     produisait versionCode=1 **en silence**. Verifie : `git archive` + `assembleRelease` donnait 1.
//
// Le repli silencieux etait le pire des deux : rien dans la sortie de build ne le signalait.
// Ici, un fichier absent ou malforme fait ECHOUER la configuration, bruyamment.
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    require(f.exists()) { "version.properties absent a la racine du projet." }
    f.inputStream().use { load(it) }
}
val appVersionCode: Int = requireNotNull(versionProps.getProperty("versionCode")?.trim()?.toIntOrNull()) {
    "versionCode absent ou non entier dans version.properties."
}
val appVersionName: String = requireNotNull(versionProps.getProperty("versionName")?.trim()?.ifBlank { null }) {
    "versionName absent ou vide dans version.properties."
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    // AGP glisse par defaut, dans le bloc de signature de l'APK, la liste CHIFFREE de nos
    // dependances, a destination de la console Play. Aucune application Files Tech n'est
    // publiee sur Play : ce bloc ne sert rien ici, et un blob illisible n'a pas sa place dans
    // un binaire dont tout l'argument est d'etre verifiable. Le scanner F-Droid le refuse
    // (« found extra signing block 'Dependency metadata' »).
    //
    // Mesure sur Agenda Tech : 9085 octets retires. Constate present sur TOUTES les apps du
    // portefeuille le 2026-08-14 — il ne pouvait pas etre vu plus tot, car les controles
    // n'analysaient que des APK NON SIGNES, qui n'ont pas de bloc de signature.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    namespace = "com.filestech.sms"
    // compileSdk 36 requis par les androidx récents (core-ktx 1.16+, lifecycle 2.11,
    // compose-bom 2026.x, activity 1.13). targetSdk reste 35 : on compile contre
    // l'API 36 sans opter dans les changements de comportement Android 16.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.filestech.sms"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "com.filestech.sms.HiltTestRunner"
        vectorDrawables { useSupportLibrary = true }

        // Room schema export pour tests de migration
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
            arg("room.generateKotlin", "true")
        }

    }

    // AGP 8.13+: locale filters replace the deprecated `resourceConfigurations`.
    androidResources {
        localeFilters += listOf("en", "fr")
    }

    // v1.24.0 — `MigrationTestHelper` lit les schémas Room depuis les ASSETS de l'APK de test.
    // Sans cette ligne, `room.schemaLocation` exporte bien les JSON dans `app/schemas/` mais ils
    // n'atteignent jamais l'appareil : chaque test de migration échoue sur
    // `FileNotFoundException: Cannot find the schema file in the assets folder`.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // `versionNameSuffix` removed — the user-facing version string is the same
            // (`versionName` above) for both build types. Distinguishing debug from release
            // is still possible via `applicationIdSuffix` (different package id, both can
            // be installed in parallel) and via `BuildConfig.LOG_ENABLED`.
            isMinifyEnabled = false
            isDebuggable = true
            buildConfigField("boolean", "LOG_ENABLED", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            buildConfigField("boolean", "LOG_ENABLED", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Reproductibilité. AGP retire les symboles de debug des bibliothèques natives avec
        // l'outil `strip` du NDK — et deux NDK différents ne produisent pas les mêmes octets.
        // C'est ce qui faisait échouer la vérification F-Droid en 1.27.5 : `classes.dex` était
        // identique, seuls les quatre `libdatastore_shared_counter.so` (AndroidX DataStore)
        // différaient, parce que leur buildserver n'a pas le même NDK que la machine de release.
        //
        // En conservant les symboles, AGP ne strip pas : le fichier est recopié tel quel depuis
        // l'AAR, donc identique partout. Coût : quelques kilo-octets.
        jniLibs {
            keepDebugSymbols += "**/libdatastore_shared_counter.so"
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        unitTests.all { test -> test.useJUnitPlatform() }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        // Étage 2.3 multi-module — lint PAR module : chaque module (`:app`, `:core`, …) analyse
        // et baseline son propre code. `checkDependencies=false` évite que la lint d'`:app`
        // re-scanne les sources des modules-lib (sinon leurs findings baselinés côté module
        // resurgissent ici). La couverture projet est préservée : `check`/`build` lance la lint
        // de tous les modules.
        checkDependencies = false
        baseline = file("lint-baseline.xml")
    }

    // Splits ABI pour APK plus légers (universal + per-ABI)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

// Kotlin 2.3+ : l'ancien DSL `android { kotlinOptions { ... } }` est supprimé.
// Migration vers le DSL `compilerOptions` (équivalent strict : jvmTarget 17 + mêmes opt-in).
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

dependencies {
    // Modules extraits en Gradle (Étage 2.3)
    implementation(project(":core"))
    implementation(project(":domain"))
    implementation(project(":data"))
    // Core
    implementation(libs.androidx.core.ktx)
    // v1.24.0 — installe le baseline profile généré au premier lancement (démarrage plus rapide).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.documentfile)
    // Audit SEC-M4 (v1.14.8) — `androidx.security:security-crypto:1.1.0-alpha06` retiré.
    // Dépendance ALPHA depuis 2021, ZÉRO usage confirmé dans le code (pas d'EncryptedSharedPrefs,
    // pas d'EncryptedFile). SMS Tech utilise son propre AeadCipher + KeystoreManager + DataStore.
    // Surface d'attaque inutile (alpha = pas de security-patches stables).

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Navigation Compose
    implementation(libs.androidx.navigation.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager + Hilt-Work
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Biometric — stable 1.1.0 (BiometricPrompt API). The "-ktx" variant only exists in 1.2.0-alpha,
    // which we avoid for a release build.
    implementation(libs.androidx.biometric)

    // Coil
    implementation(libs.coil.compose)

    // Accompanist
    implementation(libs.accompanist.permissions)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // v1.7.0 — retiré : kotlinx-coroutines-play-services (était bridge
    // Task<T>→suspend pour ML Kit), mlkit-translate, mlkit-language-id.
    // Cf. TranslationService.kt header pour le plan migration FLOSS v1.8.x.

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // SQLCipher
    implementation(libs.sqlcipher.android)

    // Logging
    implementation(libs.timber)

    // Desugaring (java.time on minSdk 26 already, but desugar still useful for stable libs)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    // Unit tests
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    // v1.27.2 — Hilt dans les tests JVM (Robolectric), pour pouvoir substituer un collaborateur
    // par un faux qui ÉCHOUE : c'est ainsi qu'on exerce les chemins d'erreur des receveurs, là où
    // vivaient le SMS perdu et le MMS effacé du 2026-08-04. `hilt-android-testing` n'était déclaré
    // que pour les tests instrumentés, donc inaccessible sans émulateur.
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    // `AndroidJUnit4` sert de lanceur aux tests Robolectric ; il n'était lui aussi disponible que
    // côté instrumenté.
    testImplementation(libs.androidx.test.ext.junit)
    // ⚠️ Sans le moteur vintage, une classe JUnit 4 est silencieusement IGNORÉE par la plateforme
    // JUnit 5 : la tâche passe au vert sans avoir rien exécuté. Le pire des échecs — un test qui
    // ne teste pas et ne le dit pas.
    testRuntimeOnly(libs.junit.vintage.engine)

    // Android tests
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.mockk.android)
    // v1.24.0 — outillage du filet de sécurité instrumenté : assertions Truth, MigrationTestHelper
    // (tests de migration Room sur le chemin SQLCipher réel), coroutines de test, Hilt.
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
