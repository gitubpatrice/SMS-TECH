package com.filestech.sms.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the R8 baseline profile shipped with the release APK.
 *
 * A baseline profile lists the classes and methods exercised on the critical startup path so ART
 * AOT-compiles them at install time instead of interpreting/JIT-ing them on the first launch —
 * typically 20–30 % faster cold start and first scroll. The generated profile lands in
 * `app/src/main/generated/baselineProfiles/` and is consumed automatically by
 * `androidx.profileinstaller`.
 *
 * The journey is intentionally just cold startup: the app requests SMS permissions and may show a
 * lock screen on first run, which UiAutomator scripting would have to fight through. Startup alone
 * captures the bulk of the win, and keeps generation deterministic on CI and on a fresh emulator.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "com.filestech.sms",
        // Also emits a startup profile: a startup-only subset ART applies at an earlier, cheaper
        // optimisation tier than the full baseline profile — extra shave off the first frame.
        includeInStartupProfile = true,
        maxIterations = 8,
        stableIterations = 3,
    ) {
        pressHome()
        startActivityAndWait()
    }
}
