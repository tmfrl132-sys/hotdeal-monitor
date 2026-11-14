"""수집 한 사이클: 기록 → 판정 → 처리 결과 저장 → 즉시알림 문구."""
from __future__ import annotations

from datetime import datetime

from monitor.config import Settings
from monitor.messages import format_instant
from monitor.models import Post, Source, Verdict
from monitor.rules import judge, judge_youtube
from monitor.store import Store


def is_paused(store: Store, now: datetime) -> bool:
    until = store.get_state("pause_until")
    return until is not None and datetime.fromisoformat(until) > now


def _verdict(post: Post, source: Source, store: Store, s: Settings, now: datetime) -> Verdict:
    exclude = store.keywords("exclude")
    if source.kind == "youtube":
        return judge_youtube(post, s, exclude)
    return judge(post, source, s, store.keywords("instant"), exclude, now)


def process(source: Source, posts: list[Post], store: Store, s: Settings,
            now: datetime) -> list[str]:
    if not store.source_seeded(source.name):
        # 처음 보는 소스: 지금 있는 글은 알리지 않는다
        for p in posts:
            store.upsert_post(p, now)
            store.mark_dropped(p.source, p.post_id)
        store.mark_seeded(source.name)
        return []

    paused = is_paused(store, now)
    messages = []
    for p in posts:
        store.upsert_post(p, now)
        if store.is_handled(p.source, p.post_id):
            continue
        v = _verdict(p, source, store, s, now)
        if v.action == "instant":
            if paused:
                store.mark_sent(p.source, p.post_id, "paused", now)
            else:
                store.mark_sent(p.source, p.post_id, "instant", now)
                messages.append(format_instant(p, v, source.label or source.name, now))
        elif v.action == "queue":
            store.enqueue_digest(p.source, p.post_id, v.category, now)
        elif v.action == "drop":
            store.mark_dropped(p.source, p.post_id)
    return messages


def recheck_watching(store: Store, s: Settings, sources: dict[str, Source], now: datetime) -> None:
    """대기 글을 나이 기준으로 다시 판정한다. 여기서는 즉시알림을 보내지 않는다."""
    for p in store.posts_watching(now, s.watch_hours * 2):
        source = sources.get(p.source)
        if source is None:
            store.mark_dropped(p.source, p.post_id)
            continue
        v = _verdict(p, source, store, s, now)
        if v.action == "queue":
            store.enqueue_digest(p.source, p.post_id, v.category, now)
        elif v.action == "drop":
            store.mark_dropped(p.source, p.post_id)
