"""텔레그램 명령 해석과 실행. 네트워크를 쓰지 않는다."""
from __future__ import annotations

import re
from datetime import datetime, timedelta
from typing import Callable
from zoneinfo import ZoneInfo

from monitor.store import Store

KST = ZoneInfo("Asia/Seoul")

ALIASES = {
    "추가": "add", "add": "add",
    "제외": "except", "except": "except",
    "삭제": "del", "del": "del",
    "목록": "list", "list": "list",
    "일시정지": "pause", "pause": "pause",
    "재개": "resume", "resume": "resume",
    "상태": "status", "status": "status",
    "도움말": "help", "help": "help", "start": "help",
}
CATEGORIES = {"재테크": "재테크", "알뜰폰": "알뜰폰", "카드": "신용카드", "신용카드": "신용카드",
              "핫딜": "핫딜", "상품권": "상품권"}

HELP = """명령어
/추가 <키워드> — 즉시알림 키워드 (전체)
/추가 <카테고리> <키워드> — 해당 카테고리만
/제외 <키워드> (/제외 <카테고리> <키워드>)
/삭제 <키워드>
/목록
/일시정지 <시간> — 예: 30분, 2시간, 1시간30분
/재개
/상태
카테고리: 재테크, 알뜰폰, 카드, 핫딜, 상품권"""

_DURATION = re.compile(r"^(?:(\d+)\s*(?:시간|h))?\s*(?:(\d+)\s*(?:분|m))?$", re.IGNORECASE)


def parse_duration(text: str) -> timedelta | None:
    m = _DURATION.fullmatch(text.strip())
    if not m or not (m[1] or m[2]):
        return None
    d = timedelta(hours=int(m[1] or 0), minutes=int(m[2] or 0))
    return d if d > timedelta(0) else None


def _scope(category: str | None) -> str:
    return f"{category}만" if category else "전체 카테고리"


def _split_category(args: str) -> tuple[str | None, str]:
    first, _, rest = args.partition(" ")
    if first in CATEGORIES and rest.strip():
        return CATEGORIES[first], rest.strip()
    return None, args.strip()


def handle(text: str, chat_id: int, owner_id: int, store: Store,
           status: Callable[[], str], now: datetime) -> str | None:
    if chat_id != owner_id or not text.startswith("/"):
        return None
    head, _, args = text[1:].strip().partition(" ")
    cmd = ALIASES.get(head.split("@")[0].lower())
    args = args.strip()
    if cmd is None:
        return "모르는 명령입니다. /도움말 을 보내 보세요."

    if cmd in ("add", "except"):
        category, word = _split_category(args)
        if not word:
            return f"사용법: /{head} [카테고리] <키워드>"
        if cmd == "add":
            store.add_keyword("instant", word, category)
            return f"✅ 즉시알림 키워드 추가: {word} ({_scope(category)})"
        store.add_keyword("exclude", word, category)
        return f"🚫 제외 키워드 추가: {word} ({_scope(category)})"

    if cmd == "del":
        if not args:
            return "사용법: /삭제 <키워드>"
        return f"🗑 삭제됨: {args}" if store.remove_keyword(args) else f"없는 키워드입니다: {args}"

    if cmd == "list":
        def fmt(kind):
            items = store.keywords(kind)
            return ", ".join(f"{w}({c or '전체'})" for w, c in items) or "없음"
        return f"[즉시알림] {fmt('instant')}\n[제외] {fmt('exclude')}"

    if cmd == "pause":
        d = parse_duration(args)
        if d is None:
            return "사용법: /일시정지 <시간> — 예: 30분, 2시간, 1시간30분"
        until = now + d
        store.set_state("pause_until", until.isoformat())
        return f"⏸ {until.astimezone(KST):%m/%d %H:%M}까지 알림을 멈춥니다. /재개 로 바로 풀 수 있습니다."

    if cmd == "resume":
        store.delete_state("pause_until")
        return "▶️ 알림을 다시 보냅니다."

    if cmd == "status":
        return status()

    return HELP
