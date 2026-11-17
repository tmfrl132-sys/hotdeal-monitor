"""뽐뿌 RSS (https://www.ppomppu.co.kr/rss.php?id=<게시판>).

<hits> 필드는 " [댓글|조회|추천|비추천]" 순서다.
2026-11-18 모바일 글 페이지(m.ppomppu.co.kr/new/bbs_view.php)와 대조해 확인했다.
예: RSS [1|311|4|0] ↔ 페이지 댓글 1·추천 5·다른의견 0. RSS는 몇 분 늦게 갱신된다.
"""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from urllib.parse import parse_qs, urlparse

from monitor.models import Post

HIT_COMMENTS, HIT_VIEWS, HIT_RECOMMENDS = 0, 1, 2
_HITS = re.compile(r"\[([\d|]+)\]")


def _hits(text: str | None) -> list[int] | None:
    m = _HITS.search(text or "")
    return [int(x) for x in m.group(1).split("|")] if m else None


def parse(source_name: str, text: str, now: datetime) -> list[Post]:
    root = ET.fromstring(text)
    posts = []
    for item in root.iter("item"):
        link = (item.findtext("link") or "").strip().replace("http://", "https://", 1)
        no = parse_qs(urlparse(link).query).get("no", [None])[0]
        title = (item.findtext("title") or "").strip()
        if not no or not title:
            continue
        hits = _hits(item.findtext("hits"))
        posted = parsedate_to_datetime(item.findtext("pubDate")).astimezone(timezone.utc)
        posts.append(Post(
            source=source_name, post_id=no, title=title, url=link, posted_at=posted,
            recommends=hits[HIT_RECOMMENDS] if hits else None,
            comments=hits[HIT_COMMENTS] if hits else None,
        ))
    return posts
