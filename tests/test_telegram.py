import asyncio
import logging

import httpx

from monitor import telegram
from monitor.telegram import Bot

TOKEN = "123:SECRET"


def test_send_failure_log_hides_token(monkeypatch, caplog):
    async def no_sleep(_):
        return None
    monkeypatch.setattr(telegram.asyncio, "sleep", no_sleep)
    transport = httpx.MockTransport(lambda req: httpx.Response(500))

    async def go():
        async with httpx.AsyncClient(transport=transport) as c:
            await Bot(TOKEN, c).send(1, "hi")

    with caplog.at_level(logging.WARNING):
        asyncio.run(go())
    assert caplog.records
    assert "SECRET" not in caplog.text


def test_send_posts_without_preview():
    seen = {}

    def handler(req):
        seen["body"] = req.content
        return httpx.Response(200, json={"ok": True})

    async def go():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as c:
            await Bot(TOKEN, c).send(1, "hi")

    asyncio.run(go())
    assert b'"disable_web_page_preview":true' in seen["body"].replace(b" ", b"")
