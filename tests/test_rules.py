from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from monitor.config import load_settings
from monitor.models import Post, Verdict
from monitor.rules import categorize, is_noise, judge, judge_ssalmuk, judge_youtube, parse_discount

ROOT = Path(__file__).resolve().parents[1]
NOW = datetime(2026, 11, 14, 3, 0, tzinfo=timezone.utc)
S = load_settings(ROOT / "settings.yaml")
SRC = {s.name: s for s in S.sources}
INSTANT = [("쌀먹", None)]
EXCLUDE = [("종료", None), ("품절", None), ("마감", None), ("질문", None), ("후기", None)]


def mk(title, source="ppomppu:ppomppu", minutes_ago=1, rec=0, com=0, ended=False):
    return Post(source, "1", title, "https://x", NOW - timedelta(minutes=minutes_ago), rec, com, ended)


def j(post, instant=INSTANT, exclude=EXCLUDE):
    return judge(post, SRC[post.source], S, instant, exclude, NOW)


def test_parse_discount():
    assert parse_discount("[컬쳐랜드] 문화상품권 5.5% 할인") == 5.5
    assert parse_discount("10% 쿠폰 + 3%") == 10
    assert parse_discount("할인 없음") is None


def test_ssalmeok_is_instant_with_fire():
    v = j(mk("○○모바일 0원 요금제 쌀먹", source="ppomppu:phone"))
    assert v == Verdict("instant", "알뜰폰", fire=True)


def test_giftcard_discount_instant():
    v = j(mk("[G마켓] 신세계상품권 6% 할인"))
    assert (v.action, v.category) == ("instant", "상품권")


def test_coupon_board_percent_without_giftcard_word_not_instant():
    # 쿠폰게시판 기본 카테고리가 상품권이어도, 상품권 단어 없는 % 글은 즉시알림이 아니다
    v = j(mk("[슈퍼SOL] 오늘도 돌려요! 100% 당첨 룰렛!", source="ppomppu:coupon"))
    assert v.action != "instant"


def test_giftcard_below_threshold_waits():
    v = j(mk("[G마켓] 신세계상품권 3% 할인"))
    assert (v.action, v.category) == ("wait", "상품권")


def test_hotdeal_50pct_not_instant():
    v = j(mk("[쿠팡] 니트 50% 할인"))
    assert (v.action, v.category) == ("wait", "핫딜")


def test_exclude_beats_instant():
    assert j(mk("쌀먹 요금제 종료", source="ppomppu:phone")).action == "drop"


@pytest.mark.parametrize("title", [
    "헬모 쌀먹 이번에처음하는데 궁금한게있습니다.",
    "헬모 쌀먹 셀프개통 친구추천 중복인가요? 처음이라 도와주십셔ㅠ",
    "쌀먹 요금제 문의드립니다",
])
def test_default_exclude_drops_question_posts(title):
    exclude = [(w, None) for w in S.default_exclude]
    assert j(mk(title, source="ppomppu:phone"), exclude=exclude).action == "drop"


def test_default_exclude_keeps_real_ssalmeok_deal():
    exclude = [(w, None) for w in S.default_exclude]
    v = j(mk("[헬로모바일] 7개월 0원 요금제 쌀먹 가능", source="ppomppu:phone"), exclude=exclude)
    assert v.action == "instant"


def test_site_ended_flag_drops():
    assert j(mk("쌀먹 요금제", source="ppomppu:phone", ended=True)).action == "drop"


def test_keyword_match_ignores_case_and_spaces():
    v = j(mk("쌀 먹 가능 요금제", source="ppomppu:phone"), instant=[("쌀먹", None)])
    assert v.action == "instant"
    v = j(mk("KT MVNO 특가", source="ppomppu:phone"), instant=[("mvno", None)])
    assert v.action == "instant"


def test_category_scoped_keyword():
    scoped = [("스타벅스", "상품권")]
    assert j(mk("스타벅스 모바일상품권 특가"), instant=scoped).action == "instant"
    # 상품권 키워드가 없는 글은 핫딜 → 상품권 한정 키워드가 맞지 않는다
    assert j(mk("스타벅스 텀블러 할인"), instant=scoped).action == "wait"


def test_category_scoped_exclude():
    v = j(mk("[쿠팡] 니트 품절임박", rec=99), exclude=[("품절", "상품권")])
    assert v.action == "queue"


def test_card_discontinue_community_instant():
    v = j(mk("○○카드 단종 확정"))
    assert v == Verdict("instant", "신용카드", fire=True, label="신용카드·단종")


def test_giftcard_keyword_overrides_board_category():
    assert categorize(mk("온누리상품권 10% 할인", source="ppomppu:money"), SRC["ppomppu:money"], S) == "상품권"


def test_reaction_within_window_queues():
    v = j(mk("[쿠팡] 니트", minutes_ago=20, rec=5))
    assert v == Verdict("queue", "핫딜")


def test_reaction_comments_threshold():
    assert j(mk("[쿠팡] 니트", minutes_ago=20, com=20)).action == "queue"


def test_reaction_late_needs_double():
    assert j(mk("[쿠팡] 니트", minutes_ago=120, rec=5)).action == "wait"
    assert j(mk("[쿠팡] 니트", minutes_ago=120, rec=10)).action == "queue"


def test_reaction_expired_drops():
    assert j(mk("[쿠팡] 니트", minutes_ago=7 * 60, rec=0)).action == "drop"


def test_expired_post_drops_even_with_instant_keyword():
    assert j(mk("쌀먹 요금제", source="ppomppu:phone", minutes_ago=7 * 60)).action == "drop"


def test_none_reactions_never_queue():
    p = Post("clien:jirum", "1", "니트", "u", NOW - timedelta(minutes=5), None, None)
    assert j(p).action == "wait"


@pytest.mark.parametrize("title,expected", [
    ("○○카드 단종, 막차", Verdict("instant", "신용카드", fire=True, label="신용카드·단종")),
    ("이번달 신용카드 추천", Verdict("instant", "신용카드")),
    ("뚜레쥬르 50% 할인", Verdict("queue", "핫딜")),
])
def test_youtube_three_way(title, expected):
    p = mk(title, source="youtube:jgb")
    assert judge_youtube(p, S, EXCLUDE) == expected


def test_youtube_discontinue_ignores_exclude():
    p = mk("○○카드 발급중단 종료 임박", source="youtube:jgb")
    assert judge_youtube(p, S, EXCLUDE).action == "instant"


def test_youtube_other_video_excluded():
    p = mk("라면 할인 종료", source="youtube:jgb")
    assert judge_youtube(p, S, EXCLUDE).action == "drop"


@pytest.mark.parametrize("title", [
    "[네이버페이] 매일 브랜드뽑기 10원(14시 오픈)",
    "[네이버페이] 1원 적립 더퀴르",
    "[네이버페이] 제주항공, 롯데카드 등 받으세요 (& 라이브, 운세, 보험, 랜덤, 마이",
    "[카카오페이] 10시퀴즈 정답",
    "[토스]정답",
    "[KB Pay] 오늘의 퀴즈 11/18일자 정답",
    "[슈퍼SOL] 오늘도 돌려요! 100% 당첨 룰렛!",
    "신한슈퍼SOL 밸런스게임",
    "하나카드 랜덤박스 열어보세요",
    "[토스] 261118 토스 두근두근 1등 찍기 팀플전",
    "[하나Pay] 트래블 미션 (매일 5P) + 랜덤박스",
    "11/18일자 네이버페이 65원 + 랜덤포인트 모음",
])
def test_digital_scrap_titles_are_noise(title):
    assert is_noise(title, S)


@pytest.mark.parametrize("title", [
    "[G마켓] 신세계상품권 10만원권 6% 할인",
    "[쿠팡] 삼다수 2L 24병 (11,900원/무료)",
    "[카카오] 중고도서 100종 (3,900원부터)",
    "카카오뱅크 스텔스통장개설하고 700원 ~ 3만원 받기",
    "에스오일 5천원!",
    "[헬로모바일] 7개월 0원 요금제 쌀먹 가능",
])
def test_real_deals_are_not_noise(title):
    assert not is_noise(title, S)


def ss(title="[헬로모바일] 매월 5만원 (월 +21,704원·8개월) [쌀먹]", hours_ago=10, ended=False):
    return Post("ssalmuk:events", "hellomobile/586/2026-11-09", title, "https://x",
                NOW - timedelta(hours=hours_ago), None, None, ended)


def test_ssalmuk_fresh_ssalmeok_is_instant_with_fire():
    assert judge_ssalmuk(ss(), EXCLUDE, NOW) == Verdict("instant", "알뜰폰", fire=True)
    # 시작일이 미래인 이벤트(미리 올라온 것)도 바로 알린다
    assert judge_ssalmuk(ss(hours_ago=-20), EXCLUDE, NOW).action == "instant"


def test_ssalmuk_old_ended_excluded_or_not_ssalmeok():
    assert judge_ssalmuk(ss(hours_ago=49), EXCLUDE, NOW).action == "drop"
    assert judge_ssalmuk(ss(ended=True), EXCLUDE, NOW).action == "drop"
    assert judge_ssalmuk(ss(title="[유모바일] 타임딜 마감 임박 [쌀먹]"), EXCLUDE, NOW).action == "drop"
    assert judge_ssalmuk(ss(title="[유모바일] 타임딜"), EXCLUDE, NOW) == Verdict("wait", "알뜰폰")
