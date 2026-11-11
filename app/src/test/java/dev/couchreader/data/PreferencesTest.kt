package dev.couchreader.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PreferencesTest {
    @Test fun defaultsMigrationAndAllFieldsRoundTrip() {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("reading", 0)
        storage.edit().clear().commit()
        assertEquals(ReadingPreferences(), PreferencesRepository(context).get())
        storage.edit().putInt("version", 1).putInt("size", 4).putBoolean("dark", true).commit()
        assertEquals(ReadingPreferences(4, true, false, 30), PreferencesRepository(context).get())
        for (size in 0..5) for (spread in listOf(false, true)) for (sleep in ReadingPreferences.sleepOptions) {
            val value = ReadingPreferences(size, size % 2 == 0, spread, sleep)
            PreferencesRepository(context).save(value)
            assertEquals(value, PreferencesRepository(context).get())
            assertEquals(2, storage.getInt("version", 0))
        }
        for (rate in ReadingPreferences.speechRates) {
            val value = ReadingPreferences(speechRate = rate)
            PreferencesRepository(context).save(value)
            assertEquals(value, PreferencesRepository(context).get())
        }
        for (voice in listOf("en-in-x-ena-local", "hi-in-x-hia-local", "")) {
            val value = ReadingPreferences(voice = voice)
            PreferencesRepository(context).save(value)
            assertEquals(value, PreferencesRepository(context).get())
        }
        storage.edit().putInt("speechRate", 42).apply()
        assertEquals(100, PreferencesRepository(context).get().speechRate)
        storage.edit().putInt("sleepMinutes", -2).putInt("size", 100).apply()
        assertEquals(30, PreferencesRepository(context).get().sleepMinutes)
        assertEquals(5, PreferencesRepository(context).get().size)
    }
}
