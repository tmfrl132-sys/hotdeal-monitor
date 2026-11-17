import asyncio
import json
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path

import httpx

from monitor.config import Secrets, load_settings
from monitor.main import App

ROOT = Path(__file__).resolve().parents[1]
FIX = ROOT / "tests" / "fixtures"


def make_app(tmp_path, rss):
    sent = []

    def handler(req: httpx.Request):
        if req.url.host == "api.telegram.org":
            sent.append(json.loads(req.content)["text"])
            return httpx.Response(200, json={"ok": True})
        return httpx.Response(200, text=rss())

    s = replace(load_settings(ROOT / "settings.yaml"), db_path=tmp_path / "t.db",
                host_gap_sec=0)
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return App(s, Secrets("T", 777), client), client, sent


def test_collect_seeds_then_sends_new_instant(tmp_path):
    base = (FIX / "ppomppu_coupon.xml").read_text(encoding="utf-8")
    now_item = datetime.now(timezone.utc).strftime("%a, %d %b %Y %H:%M:%S GMT")
    new = (f"<item><title>[G마켓] 신세계상품권 7% 할인</title>"
           f"<link>http://www.ppomppu.co.kr/zboard/view.php?id=coupon&amp;no=999999</link>"
           f"<pubDate>{now_item}</pubDate><hits> [0|1|0|0]</hits></item>")
    feeds = iter([base, base.replace("<item>", new + "<item>", 1)])

    async def go():
        app, client, sent = make_app(tmp_path, lambda: next(feeds))
        src = app.by_name["ppomppu:coupon"]
        async with client:
            await app.collect_once(src, app.health[src.name])
            assert sent == []
            await app.collect_once(src, app.health[src.name])
        app.store.close()
        return sent

    sent = asyncio.run(go())
    assert len(sent) == 1
    assert sent[0].startswith("[상품권] [G마켓] 신세계상품권 7% 할인")


def test_maintain_sends_digest_once_per_slot(tmp_path):
    async def go():
        app, client, sent = make_app(tmp_path, lambda: "")
        from monitor.models import Post
        p = Post("ppomppu:ppomppu", "1", "니트", "https://x/1",
                 datetime(2026, 11, 14, 3, 0, tzinfo=timezone.utc), 9, 0)
        app.store.upsert_post(p, p.posted_at)
        app.store.enqueue_digest(p.source, p.post_id, "핫딜", p.posted_at)
        at = datetime(2026, 11, 14, 3, 31, tzinfo=timezone.utc)  # KST 12:31
        async with client:
            await app.maintain_once(at)
            await app.maintain_once(at)
        app.store.close()
        return sent

    sent = asyncio.run(go())
    assert len(sent) == 1 and sent[0].startswith("📬 묶음 알림 (12:31)")


def test_bot_loop_survives_command_error(tmp_path, monkeypatch):
    import monitor.main as main_mod

    calls = {"n": 0}

    def boom(*a, **k):
        raise ValueError("bad")

    monkeypatch.setattr(main_mod, "handle", boom)

    async def go():
        app, client, sent = make_app(tmp_path, lambda: "")

        async def updates(offset, timeout=30):
            calls["n"] += 1
            if calls["n"] == 1:
                return [{"update_id": 5, "message": {"text": "/목록", "chat": {"id": 777}}}]
            raise asyncio.CancelledError

        app.bot.updates = updates
        async with client:
            try:
                await app.run_bot()
            except asyncio.CancelledError:
                pass
        offset = app.store.get_state("tg_offset")
        app.store.close()
        return offset

    assert asyncio.run(go()) == "6"
    assert calls["n"] == 2  # 예외 뒤에도 다음 수신으로 넘어갔다


def test_local_mode_sends_to_page_without_telegram(tmp_path):
    def handler(req):
        assert req.url.host != "api.telegram.org"
        return httpx.Response(200, text="")

    s = replace(load_settings(ROOT / "settings.yaml"), db_path=tmp_path / "data" / "t.db")
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    from monitor.local import LocalNotifier
    app = App(s, None, client, local=LocalNotifier(tmp_path / "data", open_browser=False))

    async def go():
        async with client:
            await app.notify("🔥 [알뜰폰] 쌀먹\nhttps://a.com/1")
        app.store.close()

    asyncio.run(go())
    assert app.bot is None
    assert "https://a.com/1" in (tmp_path / "data" / "alerts.html").read_text(encoding="utf-8")


def test_status_lists_sources(tmp_path):
    app, client, _ = make_app(tmp_path, lambda: "")
    text = app.status()
    app.store.close()
    asyncio.run(client.aclose())
    assert text.startswith("📊 오늘 보낸 알림 0건")
    assert "뽐뿌 휴대폰포럼: ⏳ 아직 수집 전" in text
