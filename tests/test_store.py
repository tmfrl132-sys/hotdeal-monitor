from datetime import datetime, timedelta, timezone

import pytest

from monitor.models import Post
from monitor.store import Store

NOW = datetime(2026, 11, 14, 3, 0, tzinfo=timezone.utc)


def post(pid="1", rec=0, minutes_ago=5, source="ppomppu:coupon", title="제목"):
    return Post(source, pid, title, f"https://x/{pid}", NOW - timedelta(minutes=minutes_ago), rec, 0)


@pytest.fixture
def store(tmp_path):
    s = Store(tmp_path / "t.db")
    yield s
    s.close()


def test_upsert_returns_true_only_first_time(store):
    assert store.upsert_post(post(), NOW) is True
    assert store.upsert_post(post(), NOW) is False


def test_upsert_updates_reactions(store):
    store.upsert_post(post(rec=1), NOW)
    store.upsert_post(post(rec=9), NOW)
    assert store.get_post("ppomppu:coupon", "1").recommends == 9


def test_get_post_keeps_utc(store):
    store.upsert_post(post(), NOW)
    assert store.get_post("ppomppu:coupon", "1").posted_at == NOW - timedelta(minutes=5)


def test_sent_post_not_watching_and_handled(store):
    store.upsert_post(post("1"), NOW)
    store.upsert_post(post("2"), NOW)
    store.mark_sent("ppomppu:coupon", "1", "instant", NOW)
    assert store.is_handled("ppomppu:coupon", "1")
    assert not store.is_handled("ppomppu:coupon", "2")
    assert [p.post_id for p in store.posts_watching(NOW, 6)] == ["2"]


def test_watching_excludes_old_and_dropped(store):
    store.upsert_post(post("old", minutes_ago=7 * 60), NOW)
    store.upsert_post(post("d"), NOW)
    store.mark_dropped("ppomppu:coupon", "d")
    assert store.posts_watching(NOW, 6) == []


def test_digest_pop_marks_sent_and_empties(store):
    store.upsert_post(post("1"), NOW)
    store.enqueue_digest("ppomppu:coupon", "1", "핫딜", NOW)
    assert store.is_handled("ppomppu:coupon", "1")
    items = store.pop_digest()
    assert [(p.post_id, c) for p, c in items] == [("1", "핫딜")]
    assert store.pop_digest() == []
    assert store.is_handled("ppomppu:coupon", "1")


def test_keywords_add_remove_and_seed_once(store):
    store.seed_keywords(["쌀먹"], ["종료"])
    store.seed_keywords(["쌀먹"], ["종료"])
    assert store.keywords("instant") == [("쌀먹", None)]
    store.add_keyword("instant", "컬쳐랜드", "상품권")
    assert ("컬쳐랜드", "상품권") in store.keywords("instant")
    assert store.remove_keyword("쌀먹") == 1
    assert store.keywords("instant") == [("컬쳐랜드", "상품권")]
    assert store.keywords("exclude") == [("종료", None)]


def test_seed_does_not_return_removed_keyword(store):
    store.seed_keywords(["쌀먹"], [])
    store.remove_keyword("쌀먹")
    store.seed_keywords(["쌀먹"], [])
    assert store.keywords("instant") == []


def test_source_seeded(store):
    assert not store.source_seeded("a")
    store.mark_seeded("a")
    assert store.source_seeded("a")


def test_state(store):
    assert store.get_state("k") is None
    store.set_state("k", "v")
    store.set_state("k", "w")
    assert store.get_state("k") == "w"


def test_cleanup_removes_old_posts(store):
    store.upsert_post(post("old"), NOW - timedelta(days=100))
    store.mark_sent("ppomppu:coupon", "old", "instant", NOW - timedelta(days=100))
    store.upsert_post(post("new"), NOW)
    store.cleanup(NOW, 90)
    assert store.get_post("ppomppu:coupon", "old") is None
    assert not store.is_handled("ppomppu:coupon", "old")
    assert store.get_post("ppomppu:coupon", "new") is not None
