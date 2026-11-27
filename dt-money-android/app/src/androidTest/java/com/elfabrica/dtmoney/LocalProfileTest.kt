package com.elfabrica.dtmoney

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalProfileTest {
    @Test fun disconnectPreservesLocalScopeAndReconnectingRestoresTheCorrectAccountProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$name:$suffix", mode)
        }
        try {
            val settings = AppSettings(isolated)
            assertNull(settings.loadSession())
            settings.useLocal()
            val localScope = settings.activeScope!!
            assertNull(settings.credentials(localScope))
            val first = Session(1, "Primeira", "test-token-1")
            val second = Session(2, "Segunda", "test-token-2")
            settings.saveSession("https://example.test", first)
            assertEquals(localScope, settings.activeScope)
            settings.signOut()
            assertNotNull(settings.loadSession())
            assertEquals(localScope, settings.activeScope)
            assertNull(settings.credentials(localScope))
            settings.saveSession("https://example.test", second)
            assertNotEquals(localScope, settings.activeScope)
            assertNull(settings.credentials(localScope))
            settings.signOut()
            settings.saveSession("https://example.test", first)
            assertEquals(localScope, settings.activeScope)
            assertEquals(first, settings.credentials(localScope)!!.second)
        } finally {
            context.deleteSharedPreferences("dt_money:$suffix")
        }
    }
}
