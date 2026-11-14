"""뽐뿌 RSS (https://www.ppomppu.co.kr/rss.php?id=<게시판>).

<hits> 필드는 " [댓글|조회|추천|비추천]" 순서로 본다.
글 페이지가 비브라우저 요청에 403을 줘서 직접 대조하지 못했다(2026-11-14).
추천 13·댓글 0인 쿠폰 글처럼 값의 분포가 이 순서와 맞는다. 다르면 아래 인덱스만 바꾼다.
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
