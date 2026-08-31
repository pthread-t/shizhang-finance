package com.billrecord.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.billrecord.ledger.data.AppPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLockCompatibilityTest {
    @Test
    fun appLockEnabledActivityStartsWithoutUsingNewerPlatformApis() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = AppPreferences(context)
        runBlocking { preferences.setAppLock(true) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
            }
        } finally {
            runBlocking { preferences.setAppLock(false) }
        }
    }
}
