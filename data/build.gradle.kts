import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.filestech.sms.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "LOG_ENABLED", "true")
        }
        release {
            buildConfigField("boolean", "LOG_ENABLED", "false")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    lint {
        baseline = file("lint-baseline.xml")
    }
}

ksp {
    // Room génère ici (AppDatabase_Impl + schémas). On écrit les JSON dans le dossier historique
    // app/schemas pour que le MigrationTest instrumenté (resté dans :app) les lise sans changement.
    arg("room.schemaLocation", "$rootDir/app/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":domain"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.documentfile)

    // @ApplicationContext qualifier (annotation seule ; le plugin/graph Hilt vit dans :app)
    implementation(libs.hilt.android)

    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    implementation(libs.javax.inject)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.truth)
}
