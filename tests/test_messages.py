from datetime import datetime, timedelta, timezone

from monitor.messages import LIMIT, format_digest, format_instant
from monitor.models import Post, Verdict

NOW = datetime(2026, 11, 14, 3, 30, tzinfo=timezone.utc)  # KST 12:30


def mk(pid="1", title="○○모바일 0원 쌀먹", rec=2, com=11, minutes_ago=3, source="ppomppu:phone"):
    return Post(source, pid, title, f"https://x/{pid}", NOW - timedelta(minutes=minutes_ago), rec, com)


def test_format_instant_fire_and_counts():
    text = format_instant(mk(), Verdict("instant", "알뜰폰", fire=True), "뽐뿌 휴대폰포럼", NOW)
    assert text == (
        "🔥 [알뜰폰] ○○모바일 0원 쌀먹\n"
        "뽐뿌 휴대폰포럼 · 3분 전 · 추천 2 · 댓글 11\n"
        "https://x/1"
    )


def test_format_instant_omits_none_counts_and_uses_label():
    p = mk(rec=None, com=None, minutes_ago=90)
    text = format_instant(p, Verdict("instant", "신용카드", fire=True, label="신용카드·단종"), "정가거부", NOW)
    assert text.splitlines()[0] == "🔥 [신용카드·단종] ○○모바일 0원 쌀먹"
    assert text.splitlines()[1] == "정가거부 · 1시간 전"


def test_format_instant_no_fire():
    text = format_instant(mk(), Verdict("instant", "상품권"), "뽐뿌게시판", NOW)
    assert text.startswith("[상품권] ")


def test_format_digest_groups_by_category():
    items = [(mk("1", "니트"), "핫딜"), (mk("2", "적금 특판"), "재테크"), (mk("3", "핫팩"), "핫딜")]
    parts = format_digest(items, NOW)
    assert len(parts) == 1
    lines = parts[0].splitlines()
    assert lines[0] == "📬 묶음 알림 (12:30)"
    assert "■ 핫딜 (2)" in lines and "■ 재테크 (1)" in lines
    assert lines.index("■ 핫딜 (2)") < lines.index("■ 재테크 (1)")


def test_digest_splits_over_4096():
    items = [(mk(str(i), "가" * 150), "핫딜") for i in range(100)]
    parts = format_digest(items, NOW)
    assert len(parts) > 1
    assert all(len(p) <= LIMIT for p in parts)
    joined = "\n".join(parts)
    assert all(f"https://x/{i}" in joined for i in range(100))


def test_digest_empty_returns_nothing():
    assert format_digest([], NOW) == []
