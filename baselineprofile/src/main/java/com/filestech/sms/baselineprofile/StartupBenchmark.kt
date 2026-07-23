package com.filestech.sms.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures cold-start time with and without the baseline profile, so the "faster startup" claim is
 * a number rather than a hope. Run both and compare `timeToInitialDisplayMs`:
 *
 * ```
 * ./gradlew :baselineprofile:connectedBenchmarkAndroidTest
 * ```
 *
 * [startupNoProfile] is the floor (nothing AOT-compiled); [startupBaselineProfile] applies the
 * generated profile. The delta is the win the release build ships.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupNoProfile() = benchmark(CompilationMode.None())

    @Test
    fun startupBaselineProfile() = benchmark(CompilationMode.Partial())

    private fun benchmark(mode: CompilationMode) = rule.measureRepeated(
        packageName = "com.filestech.sms",
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD,
        compilationMode = mode,
    ) {
        pressHome()
        startActivityAndWait()
    }
}
