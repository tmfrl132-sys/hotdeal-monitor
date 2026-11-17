package com.tmfrl.hotdeal.data

import com.tmfrl.hotdeal.model.SAMPLE
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class RepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun refreshCachesAndFallsBackOnError() = runTest {
        val cache = tmp.root.resolve("deals.json")
        var body: () -> String = { SAMPLE }
        val repo = Repository(cache) { body() }
        assertNull(repo.cached())
        assertEquals(5, repo.refresh()?.items?.size)
        body = { throw IOException("offline") }
        assertEquals(5, repo.refresh()?.items?.size)
        body = { "{broken" }
        assertEquals(5, repo.refresh()?.items?.size)
        assertEquals(5, repo.cached()?.items?.size)
    }

    @Test
    fun hiddenStoreAddsPrunesAndClears() = runTest {
        val file = tmp.root.resolve("hidden.json")
        val store = HiddenStore(file)
        assertEquals(emptySet<String>(), store.read())
        store.hide("a", current = setOf("a", "b"))
        store.hide("b", current = setOf("a", "b"))
        assertEquals(setOf("a", "b"), HiddenStore(file).read())
        // deals.json에서 빠진 글의 id는 다음에 숨길 때 정리된다
        store.hide("c", current = setOf("b", "c"))
        assertEquals(setOf("b", "c"), store.read())
        store.clear()
        assertEquals(emptySet<String>(), store.read())
        file.writeText("broken")
        assertEquals(emptySet<String>(), store.read())
    }

    @Test
    fun keywordStoreUpdatesFile() = runTest {
        val store = KeywordStore(tmp.root.resolve("keywords.json"))
        store.update { it.withInstant("쌀먹", null) }
        assertEquals("쌀먹", KeywordStore(tmp.root.resolve("keywords.json")).read().instant.single().word)
    }
}
