"""쌀먹 라이프 이벤트 목록 HTML (https://ssalmuk.life/).

알뜰폰 사은품 이벤트를 요금제별 순이익으로 계산해 올리는 사이트다. 게시판이 아니라서
글 시각·추천·댓글이 없다. 시각은 이벤트 시작일(KST 0시)을 쓴다.
- 끝난 이벤트: article에 expired 클래스
- 사이트가 쌀먹으로 판정한 이벤트: div.best에 verdict-ssalmeok 클래스
"""
from __future__ import annotations

import re
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

from bs4 import BeautifulSoup

from monitor.models import Post

BASE = "https://ssalmuk.life"
KST = ZoneInfo("Asia/Seoul")
SSALMEOK_CLASS = "verdict-ssalmeok"
# /events/<통신사>/<이벤트ID>/<시작일>
_PATH = re.compile(r"^/events/[^/]+/[^/]+/(\d{4})-(\d{2})-(\d{2})$")
_MONTHLY = re.compile(r"(\d+개월)\s*·\s*월 평균\s*([+-][\d,]+원)")


def _text(el) -> str:
    return " ".join(el.get_text(" ", strip=True).split()) if el else ""


def is_ssalmeok(post_title: str) -> bool:
    """parse가 제목 끝에 붙인 표시로 사이트 판정을 알아낸다."""
    return post_title.endswith("[쌀먹]")


def parse(source_name: str, text: str, now: datetime) -> list[Post]:
    soup = BeautifulSoup(text, "lxml")
    posts = []
    for art in soup.select("article.event"):
        link = art.select_one("a.event-card[href]")
        name = art.select_one(".event-top strong")
        if not link or not name:
            continue
        path = link["href"].split("?")[0].rstrip("/")
        m = _PATH.match(path)
        if not m:
            continue
        start = datetime(int(m[1]), int(m[2]), int(m[3]), tzinfo=KST)
        carrier = _text(art.select_one(".event-top .badge")).split("·")[0].strip()
        title = _text(name)
        if carrier:
            title = f"[{carrier}] {title}"
        best = art.select_one("div.best")
        if pm := _MONTHLY.search(_text(best.select_one(".best-profit")) if best else ""):
            title = f"{title} (월 {pm[2]}·{pm[1]})"
        if best is not None and SSALMEOK_CLASS in best.get("class", []):
            title = f"{title} [쌀먹]"
        posts.append(Post(
            source=source_name, post_id=path.removeprefix("/events/"), title=title,
            url=BASE + path,
            posted_at=start.astimezone(timezone.utc),
            recommends=None, comments=None,
            ended="expired" in art.get("class", []),
        ))
    return posts
