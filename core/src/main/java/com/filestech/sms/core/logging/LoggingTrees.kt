package com.filestech.sms.core.logging

import timber.log.Timber

/**
 * Debug tree which prefixes log lines with file:line for fast jumps in the IDE.
 * Strips potentially sensitive payloads (PIN, message body) is the caller's job —
 * we never want this happening automatically because we shouldn't log such data at all.
 */
class LineNumberDebugTree : Timber.DebugTree() {
    override fun createStackElementTag(element: StackTraceElement): String =
        "SmsTech::${element.fileName}:${element.lineNumber}"
}

/**
 * Release tree: drops everything except WARN/ERROR (and even those without payload).
 * The app must NEVER log SMS body, PIN, password or PII in release builds.
 */
class NoOpReleaseTree : Timber.Tree() {
    override fun isLoggable(tag: String?, priority: Int): Boolean =
        priority >= android.util.Log.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Intentionally no-op: avoid leaking anything in logcat in release builds.
    }
}
