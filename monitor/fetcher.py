"""HTTP 가져오기. 같은 호스트에는 host_gap_sec 간격을 지킨다."""
from __future__ import annotations

import asyncio
import time
from typing import Awaitable, Callable
from urllib.parse import urlparse

import httpx


class FetchError(Exception):
    pass


class Blocked(FetchError):
    """403/429. 소스를 한동안 쉬어야 한다."""

    def __init__(self, status: int):
        super().__init__(f"차단 응답 {status}")
        self.status = status


class Fetcher:
    def __init__(self, client: httpx.AsyncClient, user_agent: str, host_gap_sec: float,
                 clock: Callable[[], float] = time.monotonic,
                 sleep: Callable[[float], Awaitable[None]] = asyncio.sleep):
        self.client = client
        self.headers = {"User-Agent": user_agent, "Accept-Language": "ko-KR,ko;q=0.9"}
        self.gap = host_gap_sec
        self.clock = clock
        self.sleep = sleep
        self._last: dict[str, float] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    async def get(self, url: str) -> str:
        host = urlparse(url).netloc
        lock = self._locks.setdefault(host, asyncio.Lock())
        async with lock:
            last = self._last.get(host)
            if last is not None:
                wait = self.gap - (self.clock() - last)
                if wait > 0:
                    await self.sleep(wait)
            try:
                r = await self.client.get(url, headers=self.headers, timeout=20,
                                          follow_redirects=True)
            except httpx.HTTPError as e:
                raise FetchError(f"{type(e).__name__}: {e}") from None
            finally:
                self._last[host] = self.clock()
        if r.status_code in (403, 429):
            raise Blocked(r.status_code)
        if r.status_code != 200:
            raise FetchError(f"HTTP {r.status_code}")
        return r.text
