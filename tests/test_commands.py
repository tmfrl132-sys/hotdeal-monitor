from datetime import datetime, timedelta, timezone

import pytest

from monitor.commands import handle
from monitor.store import Store

NOW = datetime(2026, 11, 14, 3, 0, tzinfo=timezone.utc)
OWNER = 777


@pytest.fixture
def store(tmp_path):
    s = Store(tmp_path / "t.db")
    s.seed_keywords(["쌀먹"], ["종료"])
    yield s
    s.close()


def run(store, text, chat=OWNER):
    return handle(text, chat, OWNER, store, lambda: "상태 OK", NOW)


def test_add_global_and_scoped(store):
    assert run(store, "/추가 페이코") == "✅ 즉시알림 키워드 추가: 페이코 (전체 카테고리)"
    assert run(store, "/추가 상품권 컬쳐랜드") == "✅ 즉시알림 키워드 추가: 컬쳐랜드 (상품권만)"
    assert ("페이코", None) in store.keywords("instant")
    assert ("컬쳐랜드", "상품권") in store.keywords("instant")


def test_add_with_category_alias_card(store):
    run(store, "/추가 카드 단종")
    assert ("단종", "신용카드") in store.keywords("instant")


def test_add_multiword_keyword(store):
    run(store, "/추가 상품권 신세계 모바일")
    assert ("신세계 모바일", "상품권") in store.keywords("instant")


def test_exclude(store):
    assert run(store, "/제외 품절") == "🚫 제외 키워드 추가: 품절 (전체 카테고리)"
    assert ("품절", None) in store.keywords("exclude")


def test_remove_and_list(store):
    run(store, "/추가 상품권 컬쳐랜드")
    assert run(store, "/삭제 컬쳐랜드") == "🗑 삭제됨: 컬쳐랜드"
    assert run(store, "/삭제 없는말") == "없는 키워드입니다: 없는말"
    listing = run(store, "/목록")
    assert "[즉시알림] 쌀먹(전체)" in listing
    assert "[제외] 종료(전체)" in listing
    assert "컬쳐랜드" not in listing


def test_pause_sets_until_and_resume_clears(store):
    reply = run(store, "/일시정지 2시간")
    assert reply.startswith("⏸")
    assert datetime.fromisoformat(store.get_state("pause_until")) == NOW + timedelta(hours=2)
    run(store, "/재개")
    assert store.get_state("pause_until") is None


@pytest.mark.parametrize("arg,delta", [
    ("30분", timedelta(minutes=30)), ("1시간30분", timedelta(minutes=90)),
    ("90m", timedelta(minutes=90)), ("2h", timedelta(hours=2)),
])
def test_pause_formats(store, arg, delta):
    run(store, f"/일시정지 {arg}")
    assert datetime.fromisoformat(store.get_state("pause_until")) == NOW + delta


def test_pause_bad_format_returns_usage(store):
    assert "사용법" in run(store, "/일시정지 내일")
    assert store.get_state("pause_until") is None


def test_status_and_help(store):
    assert run(store, "/상태") == "상태 OK"
    assert "/추가" in run(store, "/도움말")


def test_non_owner_ignored(store):
    before = store.keywords("instant")
    assert run(store, "/추가 해킹", chat=123) is None
    assert store.keywords("instant") == before


def test_non_command_ignored(store):
    assert run(store, "안녕") is None


def test_unknown_command_hints_help(store):
    assert "/도움말" in run(store, "/뭐지")


def test_english_alias(store):
    run(store, "/add 페이코")
    assert ("페이코", None) in store.keywords("instant")


def test_bot_username_suffix(store):
    run(store, "/add@deal_bot 페이코")
    assert ("페이코", None) in store.keywords("instant")


def test_add_without_word_returns_usage(store):
    assert "사용법" in run(store, "/추가")
