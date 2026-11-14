from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from monitor.config import load_settings
from monitor.models import Post
from monitor.pipeline import process, recheck_watching
from monitor.store import Store

ROOT = Path(__file__).resolve().parents[1]
S = load_settings(ROOT / "settings.yaml")
SRC = {s.name: s for s in S.sources}
NOW = datetime(2026, 11, 14, 3, 0, tzinfo=timezone.utc)


@pytest.fixture
def store(tmp_path):
    st = Store(tmp_path / "t.db")
    st.seed_keywords(S.default_instant, S.default_exclude)
    yield st
    st.close()


def mk(pid, title, source="ppomppu:phone", rec=0, minutes_ago=1):
    return Post(source, pid, title, f"https://x/{pid}", NOW - timedelta(minutes=minutes_ago), rec, 0)


def seed(store, source="ppomppu:phone"):
    process(SRC[source], [mk("old", "옛 글", source)], store, S, NOW)


def test_first_cycle_seeds_without_sending(store):
    posts = [mk("1", "쌀먹 요금제"), mk("2", "유심 질문")]
    assert process(SRC["ppomppu:phone"], posts, store, S, NOW) == []
    assert process(SRC["ppomppu:phone"], posts, store, S, NOW) == []
    assert store.pop_digest() == []


def test_new_source_added_later_is_seeded(store):
    seed(store, "ppomppu:phone")
    posts = [mk("9", "쌀먹 요금제", source="ppomppu:coupon")]
    assert process(SRC["ppomppu:coupon"], posts, store, S, NOW) == []


def test_instant_sent_once(store):
    seed(store)
    msgs = process(SRC["ppomppu:phone"], [mk("1", "○○모바일 쌀먹")], store, S, NOW)
    assert len(msgs) == 1 and msgs[0].startswith("🔥 [알뜰폰]")
    again = process(SRC["ppomppu:phone"], [mk("1", "○○모바일 쌀먹", rec=50)], store, S, NOW)
    assert again == []
    assert store.pop_digest() == []


def test_wait_then_queue_when_reactions_grow(store):
    seed(store)
    assert process(SRC["ppomppu:phone"], [mk("1", "요금제 정리")], store, S, NOW) == []
    assert store.pop_digest() == []
    process(SRC["ppomppu:phone"], [mk("1", "요금제 정리", rec=3)], store, S, NOW)
    items = store.pop_digest()
    assert [(p.post_id, c) for p, c in items] == [("1", "알뜰폰")]


def test_dropped_post_never_sent(store):
    seed(store)
    process(SRC["ppomppu:phone"], [mk("1", "요금제 질문")], store, S, NOW)
    process(SRC["ppomppu:phone"], [mk("1", "요금제 질문", rec=99)], store, S, NOW)
    assert store.pop_digest() == []


def test_paused_drops_instant_keeps_queue(store):
    seed(store)
    store.set_state("pause_until", (NOW + timedelta(hours=1)).isoformat())
    posts = [mk("1", "○○모바일 쌀먹"), mk("2", "요금제 정리", rec=5)]
    assert process(SRC["ppomppu:phone"], posts, store, S, NOW) == []
    assert store.is_handled("ppomppu:phone", "1")
    assert [p.post_id for p, _ in store.pop_digest()] == ["2"]


def test_pause_expired_sends(store):
    seed(store)
    store.set_state("pause_until", (NOW - timedelta(minutes=1)).isoformat())
    assert len(process(SRC["ppomppu:phone"], [mk("1", "쌀먹")], store, S, NOW)) == 1


def test_youtube_source_uses_judge_youtube(store):
    seed(store, "youtube:jgb")
    v = [Post("youtube:jgb", "a", "이번달 신용카드 추천", "u", NOW, None, None),
         Post("youtube:jgb", "b", "라면 할인", "u", NOW, None, None)]
    msgs = process(SRC["youtube:jgb"], v, store, S, NOW)
    assert len(msgs) == 1 and msgs[0].startswith("[신용카드]")
    assert [(p.post_id, c) for p, c in store.pop_digest()] == [("b", "핫딜")]


def test_recheck_drops_expired_waiting(store):
    seed(store)
    process(SRC["ppomppu:phone"], [mk("1", "요금제 정리")], store, S, NOW)
    recheck_watching(store, S, SRC, NOW + timedelta(hours=7))
    assert store.is_handled("ppomppu:phone", "1")
    assert store.pop_digest() == []
