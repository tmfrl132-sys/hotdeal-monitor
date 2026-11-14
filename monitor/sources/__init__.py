"""소스별 파서 분기. 각 파서는 parse(source_name, text, now) -> list[Post]."""
from __future__ import annotations

from datetime import datetime, timezone

from monitor.models import Post, Source


class ParseError(Exception):
    """파싱 결과가 0건. 사이트 구조가 바뀌었거나 점검 페이지를 받은 경우."""


def parse(source: Source, text: str, now: datetime | None = None) -> list[Post]:
    from monitor.sources import clien, fmkorea, ppomppu, youtube

    parsers = {
        "ppomppu_rss": ppomppu.parse,
        "youtube": youtube.parse,
        "clien": clien.parse,
        "fmkorea": fmkorea.parse,
    }
    try:
        posts = parsers[source.kind](source.name, text, now or datetime.now(timezone.utc))
    except ParseError:
        raise
    except Exception as e:  # XML 깨짐 등
        raise ParseError(f"{source.name}: {e}") from e
    if not posts:
        raise ParseError(f"{source.name}: 글을 하나도 찾지 못했습니다")
    return posts
