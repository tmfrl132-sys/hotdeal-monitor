"""GitHub Actions용 한 번 실행: 모든 소스를 읽어 deals.json을 만든다.

이전 deals.json(Pages에 올라가 있는 것)을 받아 이어 붙이므로 따로 DB가 없다.
최근 글은 고르지 않은 것도 모두 싣는다. 태블릿 앱이 사용자 키워드로 다시 고르기 때문이다.

실행: python -m monitor.publish --out site/deals.json --prev-url https://.../deals.json
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx

from monitor.config import Settings, load_settings
from monitor.fetcher import Fetcher
from monitor.models import Post, Source
from monitor.rules import judge, judge_youtube
from monitor.sources import parse

log = logging.getLogger("monitor.publish")

KEEP_HOURS = 24         # 고르지 않은 글 보관 시간
KEEP_PICKED_HOURS = 48  # 고른 글 보관 시간
REQUIRED = ("id", "source", "title", "url", "posted_at")


def _iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _parse_iso(text: str) -> datetime:
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


def build_items(posts_by_source: dict[Source, list[Post]], s: Settings, now: datetime) -> list[dict]:
    instant = [(w, None) for w in s.default_instant]
    exclude = [(w, None) for w in s.default_exclude]
    items = []
    for source, posts in posts_by_source.items():
        for p in posts:
            v = judge_youtube(p, s, exclude) if source.kind == "youtube" \
                else judge(p, source, s, instant, exclude, now)
            items.append({
                "id": f"{p.source}:{p.post_id}",
                "source": p.source,
                "source_label": source.label or source.name,
                "category": v.category,
                "title": p.title,
                "url": p.url,
                "posted_at": _iso(p.posted_at),
                "recommends": p.recommends,
                "comments": p.comments,
                "ended": p.ended,
                "pick": v.action if v.action in ("instant", "queue") else None,
                "fire": v.fire,
                "label": v.label,
            })
    return items


def merge(prev: list, current: list[dict], now: datetime) -> list[dict]:
    by_id: dict[str, dict] = {}
    for item in prev:
        if isinstance(item, dict) and all(k in item for k in REQUIRED):
            by_id[item["id"]] = item
    for item in current:
        old = by_id.get(item["id"])
        if old and old.get("pick") and not item["pick"]:
            # 한 번 고른 글은 나이 제한(6시간)이 지나도 계속 보인다
            item = {**item, "pick": old["pick"], "fire": item["fire"] or old.get("fire", False),
                    "label": item["label"] or old.get("label", "")}
        by_id[item["id"]] = item

    kept = []
    for item in by_id.values():
        try:
            age = now - _parse_iso(item["posted_at"])
        except ValueError:
            continue
        limit = KEEP_PICKED_HOURS if item.get("pick") else KEEP_HOURS
        if age <= timedelta(hours=limit):
            kept.append(item)
    kept.sort(key=lambda i: i["posted_at"], reverse=True)
    return kept


def render(items: list[dict], sources: list[dict], now: datetime) -> dict:
    return {"version": 1, "generated_at": _iso(now), "sources": sources, "items": items}


def active_sources(s: Settings, skip: set[str]) -> list[Source]:
    """켜진 소스 중 skip에 없는 것. GitHub 서버를 막는 소스(FM코리아 430)는 --skip으로 뺀다."""
    return [x for x in s.sources if x.enabled and x.name not in skip]


async def collect(s: Settings, client: httpx.AsyncClient,
                  skip: set[str] = frozenset()) -> tuple[dict[Source, list[Post]], list[dict]]:
    fetcher = Fetcher(client, s.user_agent, s.host_gap_sec)
    posts_by_source, statuses = {}, []
    for src in active_sources(s, set(skip)):
        try:
            posts = parse(src, await fetcher.get(src.url), datetime.now(timezone.utc))
            posts_by_source[src] = posts
            statuses.append({"name": src.name, "label": src.label, "ok": True, "error": None,
                             "count": len(posts)})
        except Exception as e:  # 한 소스 실패가 나머지를 막지 않는다
            log.warning("%s 실패: %s", src.name, e)
            statuses.append({"name": src.name, "label": src.label, "ok": False,
                             "error": str(e)[:120], "count": 0})
    return posts_by_source, statuses


async def load_prev(client: httpx.AsyncClient, url: str | None) -> list:
    if not url:
        return []
    try:
        r = await client.get(url, timeout=20)
        if r.status_code != 200:
            log.warning("이전 deals.json 없음 (HTTP %d) — 새로 시작", r.status_code)
            return []
        return r.json().get("items", [])
    except (httpx.HTTPError, ValueError, AttributeError) as e:
        log.warning("이전 deals.json을 읽지 못함: %s — 새로 시작", e)
        return []


async def run(out: Path, prev_url: str | None, skip: set[str] = frozenset()) -> dict:
    s = load_settings()
    async with httpx.AsyncClient() as client:
        prev = await load_prev(client, prev_url)
        posts_by_source, statuses = await collect(s, client, skip)
    now = datetime.now(timezone.utc)
    items = merge(prev, build_items(posts_by_source, s, now), now)
    doc = render(items, statuses, now)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(doc, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    return doc


def main() -> None:
    ap = argparse.ArgumentParser(description="deals.json 만들기")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--prev-url", default=None)
    ap.add_argument("--skip", action="append", default=[], help="건너뛸 소스 이름 (여러 번 가능)")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)
    doc = asyncio.run(run(args.out, args.prev_url, set(args.skip)))
    ok = sum(1 for x in doc["sources"] if x["ok"])
    picked = sum(1 for x in doc["items"] if x["pick"])
    print(f"소스 {ok}/{len(doc['sources'])} 성공, 글 {len(doc['items'])}건 (고른 글 {picked}건)")
    if ok == 0:
        raise SystemExit("모든 소스가 실패했습니다")


if __name__ == "__main__":
    main()
