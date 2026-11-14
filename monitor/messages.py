"""텔레그램 메시지 서식."""
from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

from monitor.models import Post, Verdict

LIMIT = 4096  # 텔레그램 메시지 최대 길이
KST = ZoneInfo("Asia/Seoul")


def ago(posted: datetime, now: datetime) -> str:
    minutes = int((now - posted).total_seconds() // 60)
    if minutes < 1:
        return "방금"
    if minutes < 60:
        return f"{minutes}분 전"
    if minutes < 24 * 60:
        return f"{minutes // 60}시간 전"
    return f"{minutes // (24 * 60)}일 전"


def format_instant(post: Post, v: Verdict, source_label: str, now: datetime) -> str:
    head = f"{'🔥 ' if v.fire else ''}[{v.label or v.category}] {post.title}"
    meta = [source_label, ago(post.posted_at, now)]
    if post.recommends is not None:
        meta.append(f"추천 {post.recommends}")
    if post.comments is not None:
        meta.append(f"댓글 {post.comments}")
    return f"{head}\n{' · '.join(meta)}\n{post.url}"


def _pack(lines: list[str]) -> list[str]:
    """줄 단위로 LIMIT 이하 조각을 만든다. 한 줄이 LIMIT을 넘으면 잘라 넣는다."""
    parts, cur = [], ""
    for line in lines:
        line = line[:LIMIT]
        candidate = f"{cur}\n{line}" if cur else line
        if len(candidate) > LIMIT:
            parts.append(cur)
            cur = line
        else:
            cur = candidate
    if cur:
        parts.append(cur)
    return parts


def format_digest(items: list[tuple[Post, str]], now: datetime) -> list[str]:
    if not items:
        return []
    groups: dict[str, list[Post]] = {}
    for post, category in items:
        groups.setdefault(category, []).append(post)
    lines = [f"📬 묶음 알림 ({now.astimezone(KST):%H:%M})"]
    for category, posts in groups.items():
        lines += ["", f"■ {category} ({len(posts)})"]
        for p in posts:
            lines += [f"• {p.title}", p.url]
    return _pack(lines)
