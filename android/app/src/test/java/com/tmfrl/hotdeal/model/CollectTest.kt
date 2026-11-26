package com.tmfrl.hotdeal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class CollectTest {
    private val now = Instant.parse("2026-11-26T05:00:00Z")
    private fun status(code: Int, at: String = "2026-11-26T04:59:00Z") = CollectStatus(at, code)

    @Test
    fun badgeWhileWaitingForNewData() {
        assertEquals("요청 중…", collectBadge(status(COLLECT_PENDING), null, now))
        assertEquals("수집 중…", collectBadge(status(COLLECT_OK), "2026-11-26T04:40:00Z", now))
    }

    @Test
    fun badgeClearsWhenNewDataArrives() {
        assertNull(collectBadge(status(COLLECT_OK), "2026-11-26T04:59:50Z", now))
    }

    @Test
    fun badgeShowsFailures() {
        assertEquals("토큰 필요", collectBadge(status(COLLECT_NO_TOKEN), null, now))
        assertEquals("연결 실패", collectBadge(status(COLLECT_NETWORK), null, now))
        assertEquals("수집 실패 401", collectBadge(status(401), null, now))
    }

    @Test
    fun badgeExpires() {
        assertNull(collectBadge(status(401, "2026-11-26T04:50:00Z"), null, now))
        assertNull(collectBadge(null, null, now))
        assertNull(collectBadge(status(401, "깨진값"), null, now))
    }

    @Test
    fun statusRoundTrip() {
        val s = status(COLLECT_OK)
        assertEquals(s, parseCollectStatus(encodeCollectStatus(s)))
        assertNull(parseCollectStatus("{"))
    }

    @Test
    fun messages() {
        assertEquals("토큰이 틀렸거나 만료됐습니다 (401)", collectMessage(401))
        assertEquals("실패 (HTTP 500)", collectMessage(500))
    }
}
