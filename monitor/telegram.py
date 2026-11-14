"""텔레그램 Bot API 호출 (sendMessage, getUpdates)."""
from __future__ import annotations

import asyncio
import logging

import httpx

log = logging.getLogger(__name__)


class Bot:
    def __init__(self, token: str, client: httpx.AsyncClient):
        self.base = f"https://api.telegram.org/bot{token}"
        self.client = client

    async def send(self, chat_id: int, text: str) -> None:
        payload = {"chat_id": chat_id, "text": text, "disable_web_page_preview": True}
        for attempt in range(4):
            try:
                r = await self.client.post(f"{self.base}/sendMessage", json=payload, timeout=20)
                if r.status_code == 429:
                    wait = r.json().get("parameters", {}).get("retry_after", 5)
                    await asyncio.sleep(wait)
                    continue
                r.raise_for_status()
                return
            except httpx.HTTPError as e:
                log.warning("텔레그램 발송 실패 (%d회): %s", attempt + 1, e)
                await asyncio.sleep(2 ** attempt)
        log.error("텔레그램 발송 포기: %s", text[:80])

    async def updates(self, offset: int, timeout: int = 30) -> list[dict]:
        r = await self.client.get(
            f"{self.base}/getUpdates",
            params={"offset": offset, "timeout": timeout, "allowed_updates": '["message"]'},
            timeout=timeout + 10,
        )
        r.raise_for_status()
        return r.json().get("result", [])
