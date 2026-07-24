plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

allprojects {
    apply(plugin = rootProject.libs.plugins.detekt.get().pluginId)
    apply(plugin = rootProject.libs.plugins.ktlint.get().pluginId)

    detekt {
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        autoCorrect = false
        parallel = true
        // v1.24.0 — baseline absorbant la dette de style existante (1 153 issues au 2026-07-23),
        // même posture que `app/lint-baseline.xml`. Elle permet de rendre `detekt` BLOQUANT en CI
        // sans chantier cosmétique préalable : seules les violations NOUVELLES font échouer le
        // build. Régénérer avec `./gradlew :app:detektBaseline` uniquement après avoir résorbé
        // de la dette — jamais pour faire taire un nouveau problème.
        val projectBaseline = file("detekt-baseline.xml")
        if (projectBaseline.exists()) baseline = projectBaseline
    }

    dependencies {
        add("detektPlugins", rootProject.libs.detekt.formatting)
    }

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.3.1")
        android.set(true)
        // First-run posture: report violations as warnings instead of failing the build. Switch
        // back to `false` once `./gradlew ktlintFormat` has been run once and committed.
        ignoreFailures.set(true)
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
        }
        filter {
            exclude("**/generated/**", "**/build/**")
        }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
