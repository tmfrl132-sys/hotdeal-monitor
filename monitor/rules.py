"""카테고리 지정과 알림 판정. 네트워크 없는 순수 함수만 둔다."""
from __future__ import annotations

import re
from datetime import datetime, timedelta

from monitor.config import ReactionRule, Settings
from monitor.models import Post, Source, Verdict

CARD = "신용카드"
DISCONTINUE_LABEL = "신용카드·단종"
FIRE_KEYWORD = "쌀먹"
LATE_MULTIPLIER = 2  # window_min이 지난 글은 기준의 2배가 필요하다
SSALMUK_CATEGORY = "알뜰폰"
SSALMUK_FRESH_HOURS = 48  # 쌀먹 라이프: 시작일(KST 0시)부터 이 시간 안의 이벤트만 즉시알림

_DISCOUNT = re.compile(r"(\d+(?:\.\d+)?)\s*%")
_SMALL_WON = re.compile(r"(?<![\d,.])(\d{1,3})\s*원")

Keywords = list[tuple[str, "str | None"]]


def _norm(text: str) -> str:
    return re.sub(r"\s+", "", text).lower()


def _find(title: str, words) -> str | None:
    t = _norm(title)
    for w in words:
        if _norm(w) and _norm(w) in t:
            return w
    return None


def parse_discount(title: str) -> float | None:
    values = [float(m) for m in _DISCOUNT.findall(title)]
    return max(values) if values else None


def is_noise(title: str, s: Settings) -> bool:
    """디지털 폐지줍기: 퀴즈 정답, 룰렛·뽑기, 몇십 원 적립 글."""
    if _find(title, s.noise_keywords):
        return True
    # "12원"은 잡고 "3,900원"의 900원과 "0원 요금제"는 잡지 않는다
    return any(0 < int(m) <= s.noise_max_won for m in _SMALL_WON.findall(title))


def is_discontinue(title: str, s: Settings) -> bool:
    return _find(title, s.discontinue_keywords) is not None and _find(title, s.card_keywords) is not None


def categorize(post: Post, source: Source, s: Settings) -> str:
    if is_discontinue(post.title, s):
        return CARD
    matched = [c for c in source.categories if _find(post.title, s.keywords.get(c, []))]
    # 상품권·알뜰폰은 어느 게시판에든 올라온다
    for c in ("상품권", "알뜰폰"):
        if c not in matched and _find(post.title, s.keywords.get(c, [])):
            matched.append(c)
    for c in s.category_priority:
        if c in matched:
            return c
    return source.categories[0]


def _scoped_match(title: str, words: Keywords, category: str) -> str | None:
    return _find(title, [w for w, c in words if c is None or c == category])


def _met(rule: ReactionRule, post: Post, mult: int) -> bool:
    def over(value, need):
        return need is not None and value is not None and value >= need * mult
    return over(post.recommends, rule.recommends) or over(post.comments, rule.comments)


def judge(post: Post, source: Source, s: Settings, instant: Keywords, exclude: Keywords,
          now: datetime) -> Verdict:
    category = categorize(post, source, s)
    age = now - post.posted_at
    if age > timedelta(hours=s.watch_hours):
        return Verdict("drop", category)
    if post.ended or _scoped_match(post.title, exclude, category):
        return Verdict("drop", category)

    hit = _scoped_match(post.title, instant, category)
    if hit:
        return Verdict("instant", category, fire=_norm(hit) == _norm(FIRE_KEYWORD))
    # 게시판 기본값으로 상품권이 된 글은 제외: 제목에 상품권 단어가 있어야 한다
    if category == "상품권" and _find(post.title, s.keywords.get("상품권", [])):
        d = parse_discount(post.title)
        if d is not None and d >= s.giftcard_min_discount:
            return Verdict("instant", category)
    if is_discontinue(post.title, s):
        return Verdict("instant", CARD, fire=True, label=DISCONTINUE_LABEL)

    rule = s.reaction.get(category) or s.reaction["핫딜"]
    in_window = age <= timedelta(minutes=rule.window_min)
    if _met(rule, post, 1 if in_window else LATE_MULTIPLIER):
        return Verdict("queue", category)
    return Verdict("wait", category)


def judge_youtube(post: Post, s: Settings, exclude: Keywords) -> Verdict:
    if _find(post.title, s.discontinue_keywords):
        return Verdict("instant", CARD, fire=True, label=DISCONTINUE_LABEL)
    if _find(post.title, s.card_keywords):
        return Verdict("instant", CARD)
    if _scoped_match(post.title, exclude, "핫딜"):
        return Verdict("drop", "핫딜")
    return Verdict("queue", "핫딜")


def judge_ssalmuk(post: Post, exclude: Keywords, now: datetime) -> Verdict:
    """쌀먹 라이프 이벤트. 글 시각·반응 수가 없어서 사이트의 쌀먹 판정과 시작일로 고른다.

    시작일 기준 48시간은 deals.json의 고른 글 보관 시간과 같다. 그래야 보관 기간이 지나
    목록에서 빠진 이벤트가 다음 실행에서 다시 골라지지 않는다.
    """
    from monitor.sources.ssalmuk import is_ssalmeok

    if post.ended or _scoped_match(post.title, exclude, SSALMUK_CATEGORY):
        return Verdict("drop", SSALMUK_CATEGORY)
    if not is_ssalmeok(post.title):
        return Verdict("wait", SSALMUK_CATEGORY)
    if now - post.posted_at > timedelta(hours=SSALMUK_FRESH_HOURS):
        return Verdict("drop", SSALMUK_CATEGORY)
    return Verdict("instant", SSALMUK_CATEGORY, fire=True)
