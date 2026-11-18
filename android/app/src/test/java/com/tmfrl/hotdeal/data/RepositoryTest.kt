package com.tmfrl.hotdeal.data

import com.tmfrl.hotdeal.model.SAMPLE
import com.tmfrl.hotdeal.model.SavedLink
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
    fun hiddenStoreUndoRestoresMostRecentFirst() = runTest {
        val store = HiddenStore(tmp.root.resolve("hidden.json"))
        val all = setOf("a", "b", "c")
        store.hide("b", all)
        store.hide("a", all)
        store.hide("c", all)
        assertEquals("c", store.undo())
        assertEquals("a", store.undo())
        assertEquals(setOf("b"), store.read())
        assertEquals("b", store.undo())
        assertNull(store.undo())
    }

    @Test
    fun hidingAgainMovesToMostRecent() = runTest {
        val store = HiddenStore(tmp.root.resolve("hidden.json"))
        val all = setOf("a", "b")
        store.hide("a", all)
        store.hide("b", all)
        store.hide("a", all)
        assertEquals("a", store.undo())
        assertEquals(setOf("b"), store.read())
    }

    @Test
    fun checkSavedLinksUpdatesCountsAndSurvivesFailure() = runTest {
        val store = SavedStore(tmp.root.resolve("saved.json"))
        val ok = "https://www.ppomppu.co.kr/zboard/view.php?id=coupon&no=1"
        val down = "https://www.ppomppu.co.kr/zboard/view.php?id=coupon&no=2"
        val other = "https://www.youtube.com/watch?v=abc"
        store.update { listOf(SavedLink(ok, "A"), SavedLink(down, "B"), SavedLink(other, "C")) }
        val asked = mutableListOf<String>()
        checkSavedLinks(store, "now") { page ->
            asked += page
            if ("no=2" in page) throw IOException("offline")
            """<i class="cmnb">6</i>"""
        }
        val byUrl = store.read().associateBy { it.url }
        assertEquals(6, byUrl.getValue(ok).comments)
        assertNull(byUrl.getValue(down).comments)
        // 댓글 수를 읽을 수 없고 제목도 이미 있는 링크는 요청하지 않는다
        assertEquals(2, asked.size)
    }

    @Test
    fun pastedLinkGetsTitleFromPage() = runTest {
        val store = SavedStore(tmp.root.resolve("saved.json"))
        val url = "https://example.com/a"
        store.update { listOf(SavedLink(url, url)) }
        checkSavedLinks(store, "now") { "<title>글 제목</title>" }
        assertEquals("글 제목", store.read().single().title)
    }

    @Test
    fun keywordStoreUpdatesFile() = runTest {
        val store = KeywordStore(tmp.root.resolve("keywords.json"))
        store.update { it.withInstant("쌀먹", null) }
        assertEquals("쌀먹", KeywordStore(tmp.root.resolve("keywords.json")).read().instant.single().word)
    }
}
