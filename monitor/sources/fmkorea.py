"""FM코리아 핫딜 목록 HTML (https://www.fmkorea.com/hotdeal).

종료된 딜은 제목 링크 클래스가 hotdeal_var8Y다.
"""
from __future__ import annotations

import re
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from bs4 import BeautifulSoup

from monitor.models import Post

BASE = "https://www.fmkorea.com"
KST = ZoneInfo("Asia/Seoul")


def _int(el) -> int:
    m = re.search(r"\d+", el.get_text()) if el else None
    return int(m.group()) if m else 0


def _posted(text: str, now: datetime) -> datetime | None:
    text = text.strip()
    now_kst = now.astimezone(KST)
    if m := re.fullmatch(r"(\d{1,2}):(\d{2})", text):
        t = now_kst.replace(hour=int(m[1]), minute=int(m[2]), second=0, microsecond=0)
        if t > now_kst + timedelta(minutes=5):
            t -= timedelta(days=1)  # 오늘 시각이 아직 안 왔으면 어제 글
        return t.astimezone(timezone.utc)
    if m := re.fullmatch(r"(\d{4})\.(\d{2})\.(\d{2})", text):
        return datetime(int(m[1]), int(m[2]), int(m[3]), tzinfo=KST).astimezone(timezone.utc)
    return None


def parse(source_name: str, text: str, now: datetime) -> list[Post]:
    soup = BeautifulSoup(text, "lxml")
    posts = []
    for li in soup.select("div.fm_best_widget li"):
        link = li.select_one("h3.title a[href]")
        name = li.select_one(".ellipsis-target")
        date = li.select_one(".regdate")
        if not link or not name or not date:
            continue
        posted = _posted(date.get_text(), now)
        if posted is None:
            continue
        info = [a.get_text(strip=True) for a in li.select(".hotdeal_info a.strong")]
        title = name.get_text(strip=True)
        if info:
            title = f"[{info[0]}] {title}"
        if len(info) > 1:
            title = f"{title} ({info[1]})"
        pid = link["href"].strip("/").split("?")[0]
        posts.append(Post(
            source=source_name, post_id=pid, title=title, url=f"{BASE}/{pid}",
            posted_at=posted,
            recommends=_int(li.select_one(".pc_voted_count .count")),
            comments=_int(li.select_one(".comment_count")),
            ended="hotdeal_var8Y" in link.get("class", []),
        ))
    return posts
