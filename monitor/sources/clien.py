"""클리앙 알뜰구매 목록 HTML (https://www.clien.net/service/board/jirum)."""
from __future__ import annotations

from datetime import datetime, timezone
from zoneinfo import ZoneInfo

from bs4 import BeautifulSoup

from monitor.models import Post

BASE = "https://www.clien.net"
KST = ZoneInfo("Asia/Seoul")


def _int(el) -> int:
    text = el.get_text(strip=True) if el else ""
    return int(text) if text.isdigit() else 0


def parse(source_name: str, text: str, now: datetime) -> list[Post]:
    soup = BeautifulSoup(text, "lxml")
    posts = []
    for row in soup.select("div.list_item.symph_row"):
        symph = row.select_one(".list_symph")
        if symph is not None and "ali" in symph.get("class", []):
            continue  # 알리익스프레스 제휴 고정 글
        subject = row.select_one(".list_subject")
        link = subject.select_one("a[href]") if subject else None
        stamp = row.select_one(".timestamp")
        if not link or not stamp:
            continue
        path = link["href"].split("?")[0]
        title = (subject.get("title") or link.get_text(strip=True)).strip()
        posted = datetime.strptime(stamp.get_text(strip=True), "%Y-%m-%d %H:%M:%S")
        posts.append(Post(
            source=source_name, post_id=path.rstrip("/").rsplit("/", 1)[-1], title=title,
            url=BASE + path,
            posted_at=posted.replace(tzinfo=KST).astimezone(timezone.utc),
            recommends=_int(row.select_one("[data-role=list-like-count]")),
            comments=_int(row.select_one(".list_reply span")),
            ended="sold_out" in row.get("class", []),
        ))
    return posts
