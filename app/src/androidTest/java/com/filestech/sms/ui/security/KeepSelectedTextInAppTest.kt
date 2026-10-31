package com.filestech.sms.ui.security

import android.content.Context
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.textclassifier.TextClassification
import android.view.textclassifier.TextClassificationContext
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.filestech.sms.MainActivity
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The selection toolbar must not hand a message's text to another app: see [keepSelectedTextInApp].
 *
 * On the real activity, where the fields and the message selection dialog live: the classifier
 * manager Compose reads, a session made from it as Compose makes one, the context a dialog wraps, and
 * the Compose flag that lists other apps' text actions before Android 11. The control, the
 * application's own manager keeping the system classifier, shows the first assertion can fail and that
 * the setting stays local to the activity.
 *
 * ⚠️ The activity is launched ONCE, in one test. Each `@HiltAndroidTest` builds its own component,
 * hence its own DataStores; a second launch in the same process would open a second live DataStore on
 * the same file, which DataStore refuses by crashing the process.
 */
@OptIn(ExperimentalFoundationApi::class)
@SdkSuppress(minSdkVersion = 28)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class KeepSelectedTextInAppTest {

    // Order 0: Hilt builds its component before anything launches `MainActivity`.
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @Test
    fun theActivityKeepsSelectedTextInTheAppForEveryWindow() {
        hilt.inject()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var ofTheActivity: TextClassificationManager? = null
            var ofTheWindow: TextClassificationManager? = null
            scenario.onActivity { activity ->
                ofTheActivity = activity.getSystemService(TextClassificationManager::class.java)
                // A Compose dialog — the message selection dialog among them — wraps the activity's context.
                ofTheWindow = ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault_Dialog)
                    .getSystemService(TextClassificationManager::class.java)
            }
            val manager = checkNotNull(ofTheActivity)

            assertThat(manager.textClassifier).isSameInstanceAs(TextClassifier.NO_OP)
            assertThat(ofTheWindow).isSameInstanceAs(manager)
            // What Compose does before showing the toolbar: a session, then a classification.
            val session = manager.createTextClassificationSession(
                TextClassificationContext.Builder(
                    ApplicationProvider.getApplicationContext<Context>().packageName,
                    TextClassifier.WIDGET_TYPE_TEXTVIEW,
                ).build(),
            )
            val classification = session.classifyText(TextClassification.Request.Builder(WORD, 0, WORD.length).build())
            session.destroy()
            assertThat(classification.actions).isEmpty()
            // Before Android 11 every app is visible: Compose must not list their text actions. From 11
            // on, package visibility hides them and the flag is left as Compose ships it.
            assertThat(ComposeFoundationFlags.isSmartSelectionEnabled)
                .isEqualTo(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        }
    }

    @Test
    fun theControlTheApplicationManagerKeepsTheSystemClassifier() {
        val application = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(TextClassificationManager::class.java)
        assertThat(application.textClassifier).isNotSameInstanceAs(TextClassifier.NO_OP)
    }

    private companion object {
        const val WORD = "Intensive"
    }
}
