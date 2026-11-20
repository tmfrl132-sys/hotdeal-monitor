package com.tmfrl.hotdeal.model

import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 실제 페이지(2026-11-20에 받은 것)로 본문·댓글 파싱을 확인한다.
 * 샘플의 닉네임·회원 아이디는 "회원N"/"userN" 가명으로 바꿨다(공개 저장소).
 */
class ArticleTest {
    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    private val ppomppuUrl = "https://www.ppomppu.co.kr/zboard/view.php?id=phone&no=3939103"
    private val ppomppuMobile = "https://m.ppomppu.co.kr/new/bbs_view.php?id=phone&no=3939103"
    private val clienUrl = "https://www.clien.net/service/board/jirum/19262790?od=T31&po=0"
    private val clienMobile = "https://m.clien.net/service/board/jirum/19262790"

    @Test
    fun ppomppuPageBodyAndNestedComments() {
        val p = parsePpomppuPage(page("ppomppu_phone_p1.html"), ppomppuMobile)
        assertEquals("슬슬 퀀텀7도 차비로 나오네요~", p.title)
        assertEquals("2026-11-19 13:21", p.postedAt)
        assertEquals(4, p.recommends)
        assertTrue(p.body!!.contains("차비 3만원까지 확인했슴돠"))
        // 문단이 줄로 나뉜다
        assertTrue(p.body!!.contains("차비 3만원까지 확인했슴돠\n\n역시 차비폰은 퀀텀이네요~"))
        assertEquals(97, p.commentTotal)
        assertEquals(2, p.totalPages)
        // 1쪽: 댓글 50 + 답글 6 + 답글의 답글 10
        assertEquals(66, p.comments.size)
        assertEquals(50, p.comments.count { it.depth == 0 })
        assertEquals(6, p.comments.count { it.depth == 1 })
        assertEquals(10, p.comments.count { it.depth == 2 })
        assertEquals("2026-11-19 13:24:56", p.comments.first().time)
        assertEquals(10, p.comments.maxOf { it.likes ?: 0 }) // 답글 하나가 추천 10
        // 답글은 부모 댓글 바로 뒤에 온다
        val firstReply = p.comments.indexOfFirst { it.depth == 1 }
        assertEquals(0, p.comments[firstReply - 1].depth)
    }

    @Test
    fun ppomppuCommentsHaveNoNicknames() {
        val p = parsePpomppuPage(page("ppomppu_phone_p1.html"), ppomppuMobile)
        val all = p.comments.joinToString("\n") { it.text }
        // 언급 표시(@닉네임)는 지운다. 댓글 글자 속에 그냥 쓴 닉네임은 앱이 알아볼 수 없다.
        assertFalse(all.contains("@회원"))
        assertTrue(all.contains("@언급"))
    }

    @Test
    fun ppomppuRedirectLinksBecomeRealUrls() {
        val p = parsePpomppuPage(page("ppomppu_pmarket.html"), "https://m.ppomppu.co.kr/new/bbs_view.php?id=pmarket&no=304549")
        assertEquals("[토스-임박특가] 딘시 비건 선스틱 990원 (배송비 3,000원)", p.title)
        assertEquals(listOf("https://service.toss.im/shopping-discovery/c/2265279237"), p.links)
        val body = p.body!!
        assertTrue(body.contains("https://service.toss.im/shopping-discovery/c/2265279237"))
        assertFalse(body.contains("s.ppomppu.co.kr"))
        // 링크로 감싼 이미지 → [![](이미지)](링크), //cdn 주소는 https로
        assertTrue(body.contains("[![](https://cdn4.ppomppu.co.kr/zboard/data3/2026/1119/m_20261119144718_q2NkXMLWoR.jpg)](https://service.toss.im/"))
        assertTrue(body.contains("1개 990원 + 배송비 3,000원\n2개 1,980원"))
        assertEquals(24, p.totalPages)
        assertEquals(10, p.comments.size)
    }

    @Test
    fun ppomppuReadsEveryCommentPage() = runTest {
        val asked = mutableListOf<String>()
        val pages = mutableListOf<Pair<Int, Int>>()
        val result = readArticle(ppomppuUrl, { url ->
            asked += url
            when (url) {
                ppomppuMobile -> page("ppomppu_phone_p1.html")
                "$ppomppuMobile&c_page=2" -> page("ppomppu_phone_p2.html")
                else -> throw IOException("HTTP 404")
            }
        }, gapMs = 500) { p, n -> pages += p to n }
        val article = (result as ArticleResult.Read).article
        assertEquals(listOf(ppomppuMobile, "$ppomppuMobile&c_page=2"), asked)
        assertEquals(97, article.comments.size) // 1쪽 66 + 2쪽 31 = 사이트 표시 97
        assertEquals(97, article.commentTotal)
        assertEquals(listOf(1 to 2, 2 to 2), pages)
        assertEquals(null, article.note)
    }

    @Test
    fun clienPageBodyCommentsAndReplies() {
        val p = parseClienPage(page("clien_jirum.html"), "https://m.clien.net/service/board/jirum/19296066")
        assertEquals("[WearOS] Zu0̸x5 Fit Digital Watch Face 일시 무료", p.title)
        assertEquals("2026-11-20 16:16:20", p.postedAt)
        assertTrue(p.body!!.contains("유료 워치 페이스가 내일 오전 9시까지 한시적으로 무료배포중이라 정보공유합니다."))
        assertTrue(p.body!!.contains("![](https://edgio.clien.net/F01/2026/11/15867698/a41479b83a8d.jpeg?scale=width:480)"))
        assertTrue(p.body!!.contains("https://play.google.com/store/apps/details?id=com.zaheer.zuox5digitalfitwatchface"))
        assertEquals(6, p.commentTotal)
        assertEquals(6, p.comments.size)
        assertEquals(1, p.comments.count { it.depth == 1 })
        assertEquals(5, p.comments.count { it.byAuthor })
        val first = p.comments.first()
        assertEquals("2026-11-20 16:19:11", first.time)
        assertTrue(first.text.startsWith("무료 추가합니다."))
        assertTrue(first.text.contains("![](https://edgio.clien.net/F03/2026/11/15867699/a442a7b314e6.jpeg?scale=width:480)"))
        // 숨은 수정용 입력값이 글에 섞이지 않는다
        assertEquals(1, Regex("무료 추가합니다").findAll(first.text).count())
    }

    @Test
    fun clienSoldOutTitleAndMentionsAreAnonymous() {
        val p = parseClienPage(page("clien_jirum_page1.html"), clienMobile)
        assertEquals("[품절] 썬더볼트5독 + nvme 인클로저 + 140와트 어뎁터", p.title)
        assertEquals("2026-09-13 03:01:40", p.postedAt)
        assertEquals(4, p.recommends)
        val all = p.comments.joinToString("\n") { it.text }
        assertTrue(all.contains("@언급님 호스트 포트가 앞에 있는건 첨 보는것 같네요."))
        // 언급 표시(@닉네임)는 지운다. 댓글 글자 속에 그냥 쓴 닉네임은 앱이 알아볼 수 없다.
        assertFalse(all.contains("@회원"))
    }

    @Test
    fun clienReadsMoreCommentPages() = runTest {
        val asked = mutableListOf<String>()
        val result = readArticle(clienUrl, { url ->
            asked += url
            when (url) {
                // 샘플은 한 쪽에 30개씩 보이도록 줄였다(실제 사이트는 100개)
                clienMobile -> page("clien_jirum_page1.html")
                "$clienMobile/comment?order=date&po=1&ps=30" -> page("clien_jirum_comments_po1.html")
                else -> throw IOException("HTTP 404")
            }
        }, gapMs = 500)
        val article = (result as ArticleResult.Read).article
        assertEquals(listOf(clienMobile, "$clienMobile/comment?order=date&po=1&ps=30"), asked)
        assertEquals(60, article.comments.size)
        assertEquals(60, article.commentTotal)
        assertTrue(article.comments.drop(30).any { it.depth == 1 })
    }

    @Test
    fun clienCommentListUrl() {
        assertEquals(
            "https://m.clien.net/service/board/jirum/1/comment?order=date&po=2&ps=100",
            clienCommentListUrl("https://m.clien.net/service/board/jirum/1", 2, 100),
        )
    }

    @Test
    fun failedCommentPageKeepsWhatWasRead() = runTest {
        val result = readArticle(ppomppuUrl, { url ->
            if (url == ppomppuMobile) page("ppomppu_phone_p1.html") else throw IOException("HTTP 503")
        }, gapMs = 0)
        val article = (result as ArticleResult.Read).article
        assertEquals(66, article.comments.size)
        assertTrue(article.note!!.contains("2쪽"))
        assertTrue(article.note!!.contains("HTTP 503"))
    }

    @Test
    fun fetchFailureAndUnsupportedSites() = runTest {
        val failed = readArticle(ppomppuUrl, { throw IOException("HTTP 403") }, 0)
        assertTrue((failed as ArticleResult.Failed).reason.contains("HTTP 403"))

        val gone = readArticle(ppomppuUrl, { "<html><script>alert('삭제된 글입니다')</script></html>" }, 0)
        assertTrue((gone as ArticleResult.Failed).reason.contains("본문을 찾지 못했습니다"))

        val never: suspend (String) -> String = { throw AssertionError("받으면 안 된다: $it") }
        assertTrue(readArticle("https://www.fmkorea.com/10578615094", never, 0) is ArticleResult.Unsupported)
        assertTrue(readArticle("https://www.youtube.com/watch?v=abc", never, 0) is ArticleResult.Unsupported)
        assertTrue(readArticle("https://example.com/a", never, 0) is ArticleResult.Unsupported)
    }

    @Test
    fun htmlToMarkdownBasics() {
        val el = Jsoup.parseBodyFragment(
            """<p>첫 문단 <b>굵게</b>&nbsp;끝</p><p>둘째<br>줄</p><ul><li>하나</li><li>둘</li></ul>
               <p><a href="https://a.com/x">가게</a> <a href="https://b.com/long">https://b.com/lo...</a></p>
               <p><img src="//img.com/1.jpg"><img src="data:image/png;base64,AAA"></p><script>x()</script>""",
            "https://www.clien.net/",
        ).body()
        assertEquals(
            "첫 문단 굵게 끝\n\n둘째\n줄\n\n- 하나\n- 둘\n\n[가게](https://a.com/x) https://b.com/long\n\n![](https://img.com/1.jpg)",
            htmlToMarkdown(el),
        )
    }

    @Test
    fun ppomppuShortLinkDecoding() {
        assertEquals(
            "https://service.toss.im/shopping-discovery/c/2265279237",
            realUrl("https://s.ppomppu.co.kr?idno=pmarket_304549&target=aHR0cHM6Ly9zZXJ2aWNlLnRvc3MuaW0vc2hvcHBpbmctZGlzY292ZXJ5L2MvMjI2NTI3OTIzNw==&encode=on"),
        )
        assertEquals("https://s.ppomppu.co.kr?target=@@@", realUrl("https://s.ppomppu.co.kr?target=@@@"))
    }
}
