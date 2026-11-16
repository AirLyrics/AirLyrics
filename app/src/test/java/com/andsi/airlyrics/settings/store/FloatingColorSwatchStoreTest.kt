package com.andsi.airlyrics.settings.store

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Color
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FloatingColorSwatchStoreTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val preferences get() = app.getSharedPreferences("floating_color_swatches", Context.MODE_PRIVATE)

    @Before @After fun clear() {
        preferences.edit().clear().commit()
    }

    private fun context(preferences: SharedPreferences): Context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
    }

    @Test fun onlyConfirmedColorsArePublishedWhileDiskCommitIsPending() {
        val previous = Color.rgb(12, 34, 56)
        val next = Color.rgb(98, 76, 54)
        FloatingColorSwatchStore.save(app, previous)
        val memoryUpdated = CountDownLatch(1)
        val allowDiskResult = CountDownLatch(1)
        val real = preferences
        val delayed = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val editor = real.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        // Model SharedPreferences publishing its map before disk completion.
                        editor.apply()
                        memoryUpdated.countDown()
                        check(allowDiskResult.await(5, TimeUnit.SECONDS))
                        return editor.commit()
                    }
                }
            }
        }
        val wrapped = context(delayed)
        val worker = Executors.newSingleThreadExecutor()
        val result = worker.submit<Boolean> { FloatingColorSwatchStore.save(wrapped, next) }
        try {
            assertTrue(memoryUpdated.await(5, TimeUnit.SECONDS))
            assertTrue(real.getString("rgb_colors", null)!!.contains((next and 0xFFFFFF).toString()))
            assertEquals(listOf(previous), FloatingColorSwatchStore.colors(wrapped))
            allowDiskResult.countDown()
            assertTrue(result.get(5, TimeUnit.SECONDS))
            assertEquals(listOf(previous, next), FloatingColorSwatchStore.colors(wrapped))
            assertFalse(FloatingColorSwatchStore.save(wrapped, next))
            assertEquals(listOf(previous, next), FloatingColorSwatchStore.colors(wrapped))
        } finally {
            allowDiskResult.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun failedCommitRestoresThePreviousMapAndCanBeRetried() {
        val previous = Color.rgb(12, 34, 56)
        val next = Color.rgb(98, 76, 54)
        FloatingColorSwatchStore.save(app, previous)
        val real = preferences
        val failing = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val editor = real.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        editor.apply()
                        return false
                    }
                }
            }
        }
        val wrapped = context(failing)
        assertThrows(IOException::class.java) { FloatingColorSwatchStore.save(wrapped, next) }
        assertEquals(listOf(previous), FloatingColorSwatchStore.colors(wrapped))
        assertEquals(listOf(previous), FloatingColorSwatchStore.colors(app))
        assertTrue(FloatingColorSwatchStore.save(app, next))
        assertEquals(listOf(previous, next), FloatingColorSwatchStore.colors(app))
    }
}
