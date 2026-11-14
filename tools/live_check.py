"""텔레그램 없이 실제 사이트를 한 번씩 읽어 파싱되는지 확인한다. 실행: .venvScriptspython -X utf8 tools/live_check.py"""
import asyncio
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import httpx

from monitor.config import load_settings
from monitor.fetcher import Fetcher
from monitor.rules import judge, judge_youtube
from monitor.sources import parse


async def main():
    s = load_settings()
    instant = [(w, None) for w in s.default_instant]
    exclude = [(w, None) for w in s.default_exclude]
    async with httpx.AsyncClient() as c:
        f = Fetcher(c, s.user_agent, s.host_gap_sec)
        for src in s.sources:
            try:
                now = datetime.now(timezone.utc)
                posts = parse(src, await f.get(src.url), now)
                counts = {}
                for p in posts:
                    v = judge_youtube(p, s, exclude) if src.kind == "youtube" \
                        else judge(p, src, s, instant, exclude, now)
                    counts[v.action] = counts.get(v.action, 0) + 1
                print(f"OK  {src.name:18} {len(posts):3}건 {counts}  예: {posts[0].title[:30]}")
            except Exception as e:
                print(f"ERR {src.name:18} {type(e).__name__}: {e}")


asyncio.run(main())
