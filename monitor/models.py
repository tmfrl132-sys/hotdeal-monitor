"""모든 모듈이 함께 쓰는 데이터 타입."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class Source:
    name: str                    # "ppomppu:coupon" 처럼 소스 안에서 유일
    kind: str                    # "ppomppu_rss" | "clien" | "fmkorea" | "youtube"
    url: str
    interval_sec: int
    categories: tuple[str, ...]  # 첫 번째가 기본 카테고리
    label: str = ""              # 알림에 보일 이름, 예: "뽐뿌 휴대폰포럼"
    enabled: bool = True


@dataclass(frozen=True)
class Post:
    source: str                  # Source.name
    post_id: str                 # 소스 안에서 유일 (글번호, 영상 ID)
    title: str
    url: str
    posted_at: datetime          # tz-aware UTC
    recommends: int | None
    comments: int | None
    ended: bool = False          # 사이트가 붙인 종료/품절 표시


@dataclass(frozen=True)
class Verdict:
    action: str                  # "drop" | "instant" | "queue" | "wait"
    category: str
    fire: bool = False           # 🔥 표시
    label: str = ""              # 메시지 라벨, 비면 category를 쓴다
