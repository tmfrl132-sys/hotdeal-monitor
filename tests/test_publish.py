from datetime import datetime, timedelta, timezone
from pathlib import Path

from monitor.config import load_settings
from monitor.models import Post
from monitor.publish import active_sources, build_items, merge, render, rules_of

ROOT = Path(__file__).resolve().parents[1]
S = load_settings(ROOT / "settings.yaml")
SRC = {s.name: s for s in S.sources}
NOW = datetime(2026, 11, 17, 3, 0, tzinfo=timezone.utc)


def mk(pid, title, source="ppomppu:phone", minutes_ago=1, rec=0, com=0, ended=False):
    return Post(source, pid, title, f"https://x/{pid}", NOW - timedelta(minutes=minutes_ago), rec, com, ended)


def item_of(items, pid):
    return next(i for i in items if i["id"].endswith(":" + pid))


def test_build_items_fields_and_pick():
    items = build_items({SRC["ppomppu:phone"]: [mk("1", "○○모바일 쌀먹"), mk("2", "요금제 정리")]}, S, NOW)
    a, b = item_of(items, "1"), item_of(items, "2")
    assert a == {
        "id": "ppomppu:phone:1", "source": "ppomppu:phone", "source_label": "뽐뿌 휴대폰포럼",
        "category": "알뜰폰", "title": "○○모바일 쌀먹", "url": "https://x/1",
        "posted_at": "2026-11-17T02:59:00Z", "recommends": 0, "comments": 0, "ended": False,
        "pick": "instant", "fire": True, "label": "", "picked_at": None,
    }
    assert b["pick"] is None and b["fire"] is False


def test_build_items_queue_and_youtube():
    yt = Post("youtube:jgb", "v1", "이번달 신용카드 추천", "https://y/v1", NOW, None, None)
    items = build_items({SRC["ppomppu:ppomppu"]: [mk("3", "[쿠팡] 니트", "ppomppu:ppomppu", 20, rec=5)],
                         SRC["youtube:jgb"]: [yt]}, S, NOW)
    assert item_of(items, "3")["pick"] == "queue"
    v = item_of(items, "v1")
    assert (v["pick"], v["category"], v["recommends"]) == ("instant", "신용카드", None)


def test_merge_keeps_pick_after_it_expires():
    prev = build_items({SRC["ppomppu:ppomppu"]: [mk("3", "[쿠팡] 니트", "ppomppu:ppomppu", 20, rec=5)]}, S, NOW)
    later = NOW + timedelta(hours=7)
    # 7시간 뒤에는 판정이 drop(나이 초과)이지만 이미 골라진 글은 계속 보인다
    cur = build_items({SRC["ppomppu:ppomppu"]: [mk("3", "[쿠팡] 니트", "ppomppu:ppomppu", 20 + 420, rec=9)]}, S, later)
    merged = merge(prev, cur, later)
    m = item_of(merged, "3")
    assert m["pick"] == "queue" and m["recommends"] == 9


def test_merge_keeps_prev_items_missing_from_feed():
    prev = build_items({SRC["ppomppu:phone"]: [mk("1", "쌀먹")]}, S, NOW)
    merged = merge(prev, [], NOW + timedelta(minutes=15))
    assert [i["id"] for i in merged] == ["ppomppu:phone:1"]


def test_merge_prunes_old_items():
    prev = build_items({SRC["ppomppu:phone"]: [mk("1", "쌀먹", minutes_ago=0), mk("2", "요금제 정리", minutes_ago=0)]}, S, NOW)
    merged = merge(prev, [], NOW + timedelta(hours=30))
    # 고르지 않은 글은 24시간, 고른 글은 48시간 보관
    assert [i["id"] for i in merged] == ["ppomppu:phone:1"]
    assert merge(prev, [], NOW + timedelta(hours=49)) == []


def test_merge_sorts_newest_first():
    cur = build_items({SRC["ppomppu:phone"]: [mk("old", "a", minutes_ago=30), mk("new", "b", minutes_ago=2)]}, S, NOW)
    assert [i["id"] for i in merge([], cur, NOW)] == ["ppomppu:phone:new", "ppomppu:phone:old"]


def test_merge_ignores_broken_prev_items():
    merged = merge([{"id": "x"}, "garbage"], [], NOW)
    assert merged == []


def test_active_sources_skips_disabled_and_named():
    names = [s.name for s in active_sources(S, skip={"fmkorea:hotdeal"})]
    assert "fmkorea:hotdeal" not in names
    assert "ppomppu:stock" not in names  # settings.yaml에서 꺼 둠
    assert "clien:jirum" in names and "youtube:jgb" in names


def test_picked_at_set_when_first_picked_and_kept():
    first = build_items({SRC["ppomppu:ppomppu"]: [mk("3", "[쿠팡] 니트", "ppomppu:ppomppu", 10, rec=0)]}, S, NOW)
    m1 = merge([], first, NOW)
    assert item_of(m1, "3")["picked_at"] is None
    later = NOW + timedelta(minutes=15)
    cur = build_items({SRC["ppomppu:ppomppu"]: [mk("3", "[쿠팡] 니트", "ppomppu:ppomppu", 10, rec=6)]}, S, later)
    m2 = merge(m1, cur, later)
    assert item_of(m2, "3")["picked_at"] == "2026-11-17T03:15:00Z"
    even_later = later + timedelta(minutes=15)
    cur = build_items({SRC["ppomppu:ppomppu"]: [mk("3", "[쿠팡] 니트", "ppomppu:ppomppu", 10, rec=9)]}, S, even_later)
    assert item_of(merge(m2, cur, even_later), "3")["picked_at"] == "2026-11-17T03:15:00Z"


def test_rules_section_reflects_settings():
    r = rules_of(S, skip={"fmkorea:hotdeal"})
    assert r["instant_keywords"] == S.default_instant
    assert "종료" in r["exclude_keywords"]
    assert r["giftcard_min_discount"] == 5
    assert r["reaction"]["핫딜"] == {"window_min": 30, "recommends": 5, "comments": 20}
    assert r["late_multiplier"] == 2 and r["watch_hours"] == 6
    assert r["keep_hours"] == 24 and r["keep_picked_hours"] == 48
    labels = [x["label"] for x in r["sources"]]
    assert "FM코리아 핫딜" not in labels and "정가거부" in labels
    assert r["discontinue_keywords"] == S.discontinue_keywords


def test_render_document():
    doc = render([], [{"name": "a", "label": "A", "ok": False, "error": "HTTP 403", "count": 0}], NOW, rules={"x": 1})
    assert doc["rules"] == {"x": 1}
    assert doc["version"] == 1
    assert doc["generated_at"] == "2026-11-17T03:00:00Z"
    assert doc["sources"][0]["error"] == "HTTP 403"
    assert doc["items"] == []
