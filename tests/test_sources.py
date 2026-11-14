from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest

from monitor.models import Source
from monitor.sources import ParseError, parse

FIX = Path(__file__).parent / "fixtures"
KST = ZoneInfo("Asia/Seoul")
# 샘플을 저장한 시각 (KST 2026-11-14 19:58)
SAVED = datetime(2026, 11, 14, 19, 58, tzinfo=KST)


def src(kind, name):
    return Source(name, kind, "u", 60, ("핫딜",))


def read(name):
    return (FIX / name).read_text(encoding="utf-8")


def test_ppomppu_rss_fields():
    posts = parse(src("ppomppu_rss", "ppomppu:coupon"), read("ppomppu_coupon.xml"), SAVED)
    p = posts[0]
    assert len(posts) == 15
    assert p.source == "ppomppu:coupon"
    assert p.post_id == "120752"
    assert p.title == "[네이버페이] 매일 브랜드뽑기 10원(14시 오픈)"
    assert p.url == "https://www.ppomppu.co.kr/zboard/view.php?id=coupon&no=120752"
    assert p.posted_at == datetime(2026, 11, 14, 5, 1, 33, tzinfo=timezone.utc)
    # hits = [댓글|조회|추천|비추천]
    assert (p.comments, p.recommends) == (0, 13)


def test_youtube_rss_fields():
    posts = parse(src("youtube", "youtube:jgb"), read("youtube_jgb.xml"), SAVED)
    p = posts[0]
    assert len(posts) == 15
    assert p.post_id == "c-S8qm8iv1I"
    assert p.title == "6천원을 그냥 준다고요!? 일단 받으세요!!"
    assert p.url == "https://www.youtube.com/watch?v=c-S8qm8iv1I"
    assert p.posted_at == datetime(2026, 11, 14, 7, 39, 32, tzinfo=timezone.utc)
    assert p.recommends is None and p.comments is None


def test_clien_list_fields():
    posts = parse(src("clien", "clien:jirum"), read("clien_jirum.html"), SAVED)
    p = posts[0]
    assert p.post_id == "19292123"
    assert p.title == "[롯데온] 언더아머 헤어밴드 손목밴드 (2,900원)"
    assert p.url == "https://www.clien.net/service/board/jirum/19292123"
    assert p.posted_at == datetime(2026, 11, 14, 10, 20, 6, tzinfo=KST)
    assert p.posted_at.utcoffset().total_seconds() == 0
    assert (p.recommends, p.comments) == (2, 8)
    assert not p.ended


def test_clien_skips_notice_ads_and_marks_sold_out():
    posts = parse(src("clien", "clien:jirum"), read("clien_jirum.html"), SAVED)
    titles = [p.title for p in posts]
    assert not any("이용규칙" in t for t in titles)
    # 알리 제휴 고정 글은 건너뛰고, 같은 글이 일반 목록에도 있으면 그쪽만 남는다
    ids = [p.post_id for p in posts]
    assert len(ids) == len(set(ids))
    sold = [p for p in posts if p.title == "[젠틀몬스터] 인텔리전트 아이웨어 1차 프리오더"]
    assert sold and sold[0].ended


def test_fmkorea_list_fields():
    posts = parse(src("fmkorea", "fmkorea:hotdeal"), read("fmkorea_hotdeal.html"), SAVED)
    p = posts[0]
    assert len(posts) == 20
    assert p.post_id == "10554645537"
    assert p.title == "[네이버] 붙이는 핫팩 20개입 (7,900원)"
    assert p.url == "https://www.fmkorea.com/10554645537"
    assert p.posted_at == datetime(2026, 11, 14, 19, 55, tzinfo=KST)
    assert (p.recommends, p.comments) == (0, 0)


def test_fmkorea_counts_and_ended():
    posts = parse(src("fmkorea", "fmkorea:hotdeal"), read("fmkorea_hotdeal.html"), SAVED)
    by_id = {p.post_id: p for p in posts}
    assert (by_id["10554397082"].recommends, by_id["10554397082"].comments) == (2, 8)
    assert by_id["10554389399"].ended


def test_fmkorea_time_after_now_is_yesterday():
    html = read("fmkorea_hotdeal.html")
    early = datetime(2026, 11, 14, 0, 30, tzinfo=KST)  # 19:55 글은 전날 글이어야 한다
    p = parse(src("fmkorea", "fmkorea:hotdeal"), html, early)[0]
    assert p.posted_at == datetime(2026, 11, 13, 19, 55, tzinfo=KST)


@pytest.mark.parametrize("kind", ["ppomppu_rss", "youtube", "clien", "fmkorea"])
def test_empty_raises_parse_error(kind):
    with pytest.raises(ParseError):
        parse(src(kind, "x"), "<html><body>점검 중</body></html>", SAVED)
