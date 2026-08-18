package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TvLayoutTest {

    @Test
    fun testTvModels() {
        val item = StreamingItem(
            id = "test_123",
            title = "Test Movie",
            imageUrl = "https://example.com/poster.jpg",
            backdropUrl = "https://example.com/backdrop.jpg",
            rating = "8.5",
            year = "2024",
            isSeries = false,
            description = "A great movie for testing."
        )

        val category = StreamingCategory(
            id = "trending",
            title = "Trending Now",
            items = listOf(item)
        )

        assertEquals("test_123", item.id)
        assertEquals("Test Movie", item.title)
        assertEquals(1, category.items.size)
        assertEquals("Trending Now", category.title)
    }

    @Test
    fun testTvStringResources() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertNotNull(appName)
        assertEquals("ZubFlix", appName)
    }
}
