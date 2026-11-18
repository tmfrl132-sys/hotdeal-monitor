package com.tmfrl.hotdeal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedTest {
    private val ppomppu = "https://www.ppomppu.co.kr/zboard/view.php?id=coupon&no=120874"
    private val clien = "https://www.clien.net/service/board/jirum/19294762?od=T31&po=0"

    @Test
    fun commentPageForSupportedSites() {
        assertEquals("https://m.ppomppu.co.kr/new/bbs_view.php?id=coupon&no=120874", commentPageUrl(ppomppu))
        assertEquals(
            "https://m.ppomppu.co.kr/new/bbs_view.php?id=phone&no=5",
            commentPageUrl("https://m.ppomppu.co.kr/new/bbs_view.php?id=phone&no=5"),
        )
        assertEquals("https://m.clien.net/service/board/jirum/19294762", commentPageUrl(clien))
        assertNull(commentPageUrl("https://www.youtube.com/watch?v=abc"))
    }

    @Test
    fun parsesCommentCounts() {
        assertEquals(7, parseCommentCount(ppomppu, """<a title="코멘트"><i class="cmnb">7</i></a>"""))
        assertEquals(3, parseCommentCount(clien, """<input type="hidden" value="3" id="commentCount">"""))
        assertNull(parseCommentCount(ppomppu, "<html>점검 중</html>"))
    }

    @Test
    fun parsesTitleWithEntities() {
        assertEquals("[아마존] B&W Pi6 : 클리앙", parseHtmlTitle("<title>[아마존] B&amp;W Pi6 : 클리앙</title>"))
        assertNull(parseHtmlTitle("<html></html>"))
    }

    @Test
    fun decodesEucKrPages() {
        val html = """<meta charset="euc-kr"><title>뽐뿌</title>"""
        assertTrue("뽐뿌" in decodeHtml(html.toByteArray(charset("EUC-KR"))))
        assertTrue("클리앙" in decodeHtml("<title>클리앙</title>".toByteArray()))
    }

    @Test
    fun validatesUrl() {
        assertTrue(isSavableUrl(" https://a.com/1 "))
        assertFalse(isSavableUrl("a.com"))
        assertFalse(isSavableUrl("https://a.com/1 메모"))
    }

    @Test
    fun newCommentsCountsSinceLastSeen() {
        val link = SavedLink("u", "t", comments = 12, seenComments = 9)
        assertEquals(3, link.newComments)
        assertEquals(0, markSeen(listOf(link), "u").single().newComments)
        assertEquals(0, SavedLink("u", "t").newComments)
        assertEquals(0, SavedLink("u", "t", comments = 2, seenComments = 5).newComments)
    }

    @Test
    fun firstCheckSetsBaselineWithoutNewBadge() {
        val checked = applyCheck(SavedLink("u", "u"), count = 5, title = "진짜 제목", now = "n")
        assertEquals(5, checked.comments)
        assertEquals(0, checked.newComments)
        assertEquals("진짜 제목", checked.title)
        assertEquals(2, applyCheck(checked, count = 7, title = "다른 제목", now = "n").newComments)
        assertEquals("진짜 제목", applyCheck(checked, count = 7, title = "다른 제목", now = "n").title)
    }

    @Test
    fun failedCheckKeepsLastCount() {
        val link = SavedLink("u", "t", comments = 4, seenComments = 4)
        assertEquals(4, applyCheck(link, count = null, title = null, now = "n").comments)
    }

    @Test
    fun addSkipsDuplicateAndPutsNewestFirst() {
        val a = SavedLink("a", "A")
        val list = addSaved(addSaved(emptyList(), a), SavedLink("b", "B"))
        assertEquals(listOf("b", "a"), list.map { it.url })
        assertEquals(2, addSaved(list, a).size)
    }
}
