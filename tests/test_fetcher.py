import asyncio

import httpx
import pytest

from monitor.fetcher import Blocked, Fetcher, FetchError


class FakeClock:
    def __init__(self):
        self.t = 100.0
        self.sleeps = []

    def now(self):
        return self.t

    async def sleep(self, sec):
        self.sleeps.append(sec)
        self.t += sec


def make(handler, clock):
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return client, Fetcher(client, "UA", 2.0, clock=clock.now, sleep=clock.sleep)


def test_fetcher_host_gap():
    clock = FakeClock()

    async def go():
        client, f = make(lambda r: httpx.Response(200, text="ok"), clock)
        async with client:
            await f.get("https://a.com/1")
            await f.get("https://a.com/2")
            await f.get("https://b.com/1")

    asyncio.run(go())
    assert clock.sleeps == [2.0]


def test_fetcher_sends_user_agent():
    seen = {}

    def handler(r):
        seen["ua"] = r.headers["user-agent"]
        return httpx.Response(200, text="ok")

    async def go():
        client, f = make(handler, FakeClock())
        async with client:
            assert await f.get("https://a.com/") == "ok"

    asyncio.run(go())
    assert seen["ua"] == "UA"


@pytest.mark.parametrize("code", [403, 429])
def test_fetcher_blocked(code):
    async def go():
        client, f = make(lambda r: httpx.Response(code), FakeClock())
        async with client:
            await f.get("https://a.com/")

    with pytest.raises(Blocked):
        asyncio.run(go())


def test_fetcher_other_error():
    async def go():
        client, f = make(lambda r: httpx.Response(500), FakeClock())
        async with client:
            await f.get("https://a.com/")

    with pytest.raises(FetchError):
        asyncio.run(go())
