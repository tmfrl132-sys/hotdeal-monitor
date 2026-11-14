"""실행 진입점: 소스별 수집 루프, 텔레그램 봇 루프, 관리 루프."""
from __future__ import annotations

import asyncio
import logging
import signal
from datetime import datetime, timezone
from logging.handlers import TimedRotatingFileHandler
from zoneinfo import ZoneInfo

import httpx

from monitor.commands import handle
from monitor.config import Secrets, Settings, load_secrets, load_settings
from monitor.fetcher import Fetcher
from monitor.messages import format_digest
from monitor.models import Source
from monitor.pipeline import is_paused, process, recheck_watching
from monitor.schedule import SourceHealth, due_digest_slot
from monitor.sources import parse
from monitor.store import Store
from monitor.telegram import Bot

KST = ZoneInfo("Asia/Seoul")
log = logging.getLogger("monitor")


def _now() -> datetime:
    return datetime.now(timezone.utc)


def setup_logging(s: Settings) -> None:
    s.log_dir.mkdir(parents=True, exist_ok=True)
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s")
    file = TimedRotatingFileHandler(s.log_dir / "monitor.log", when="midnight",
                                    backupCount=7, encoding="utf-8")
    file.setFormatter(fmt)
    console = logging.StreamHandler()
    console.setFormatter(fmt)
    logging.basicConfig(level=logging.INFO, handlers=[file, console], force=True)
    # httpx/httpcore는 INFO에서 요청 URL(봇 토큰 포함)을 남기므로 경고 이상만 기록한다
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)


class App:
    def __init__(self, s: Settings, sec: Secrets, client: httpx.AsyncClient):
        self.s, self.sec = s, sec
        self.sources = [src for src in s.sources if src.enabled]
        self.by_name = {src.name: src for src in self.sources}
        self.store = Store(s.db_path)
        self.store.seed_keywords(s.default_instant, s.default_exclude)
        self.fetcher = Fetcher(client, s.user_agent, s.host_gap_sec)
        self.bot = Bot(sec.bot_token, client)
        self.health = {src.name: SourceHealth(src.label or src.name, s.fail_alert_after,
                                              s.backoff_min) for src in self.sources}

    async def notify(self, text: str) -> None:
        await self.bot.send(self.sec.chat_id, text)

    # ---- 상태 문구 (/상태) ----
    def status(self) -> str:
        now = _now()
        today = now.astimezone(KST).replace(hour=0, minute=0, second=0, microsecond=0)
        lines = [f"📊 오늘 보낸 알림 {self.store.count_sent_since(today)}건"]
        if is_paused(self.store, now):
            until = datetime.fromisoformat(self.store.get_state("pause_until"))
            lines.append(f"⏸ {until.astimezone(KST):%H:%M}까지 일시정지 중")
        for src in self.sources:
            h = self.health[src.name]
            if h.last_error:
                state = f"❌ 실패 {h.failures}회: {h.last_error[:60]}"
            elif h.last_success:
                state = f"✅ {h.last_success.astimezone(KST):%H:%M}"
            else:
                state = "⏳ 아직 수집 전"
            lines.append(f"{src.label}: {state}")
        return "\n".join(lines)

    # ---- 소스 수집 ----
    async def run_source(self, src: Source) -> None:
        h = self.health[src.name]
        while True:
            now = _now()
            if h.ready(now):
                await self.collect_once(src, h)
            await asyncio.sleep(src.interval_sec)

    async def collect_once(self, src: Source, h: SourceHealth) -> None:
        try:
            text = await self.fetcher.get(src.url)
            now = _now()
            posts = parse(src, text, now)
            messages = process(src, posts, self.store, self.s, now)
        except Exception as e:  # 한 소스의 실패가 다른 소스를 멈추면 안 된다
            log.warning("%s 수집 실패: %s", src.name, e)
            alert = h.on_failure(e, _now())
            if alert:
                await self.notify(alert)
            return
        recovered = h.on_success(_now())
        if recovered:
            await self.notify(recovered)
        for m in messages:
            await self.notify(m)
        if messages:
            log.info("%s 즉시알림 %d건", src.name, len(messages))

    # ---- 텔레그램 명령 ----
    async def run_bot(self) -> None:
        offset = int(self.store.get_state("tg_offset") or 0)
        while True:
            try:
                updates = await self.bot.updates(offset)
            except Exception as e:
                log.warning("텔레그램 명령 수신 실패: %s", e)
                await asyncio.sleep(5)
                continue
            for u in updates:
                offset = u["update_id"] + 1
                self.store.set_state("tg_offset", str(offset))
                msg = u.get("message") or {}
                text, chat = msg.get("text"), (msg.get("chat") or {}).get("id")
                if not text or chat is None:
                    continue
                try:
                    reply = handle(text, chat, self.sec.chat_id, self.store, self.status, _now())
                except Exception:
                    log.exception("명령 처리 실패: %s", text[:40])
                    reply = "명령을 처리하지 못했습니다. 로그를 확인해 주세요." \
                        if chat == self.sec.chat_id else None
                if reply:
                    await self.notify(reply)

    # ---- 관리: 재판정, 묶음, 정리 ----
    async def run_maintenance(self) -> None:
        while True:
            try:
                await self.maintain_once(_now())
            except Exception:
                log.exception("관리 작업 실패")
            await asyncio.sleep(30)

    async def maintain_once(self, now: datetime) -> None:
        recheck_watching(self.store, self.s, self.by_name, now)
        slot = due_digest_slot(now, self.s.digest_times, self.store.get_state("last_digest_slot"))
        if slot:
            self.store.set_state("last_digest_slot", slot)
            if is_paused(self.store, now):
                log.info("일시정지 중이라 %s 묶음을 다음으로 미룹니다", slot)
            else:
                items = self.store.pop_digest()
                for part in format_digest(items, now):
                    await self.notify(part)
                log.info("묶음 %s: %d건", slot, len(items))
        today = now.astimezone(KST).date().isoformat()
        if self.store.get_state("last_cleanup") != today:
            self.store.cleanup(now, self.s.retention_days)
            self.store.set_state("last_cleanup", today)

    async def run(self) -> None:
        await self.notify(f"🟢 딜 모니터 시작 (소스 {len(self.sources)}개)")
        tasks = [asyncio.create_task(self.run_source(src)) for src in self.sources]
        tasks += [asyncio.create_task(self.run_bot()), asyncio.create_task(self.run_maintenance())]
        await asyncio.gather(*tasks)


async def _main() -> None:
    s = load_settings()
    sec = load_secrets()
    setup_logging(s)
    async with httpx.AsyncClient() as client:
        app = App(s, sec, client)
        loop = asyncio.get_running_loop()
        main_task = asyncio.current_task()
        if hasattr(signal, "SIGTERM"):
            try:
                loop.add_signal_handler(signal.SIGTERM, main_task.cancel)
            except NotImplementedError:  # Windows
                pass
        try:
            await app.run()
        except asyncio.CancelledError:
            pass
        finally:
            app.store.close()
            log.info("딜 모니터 종료")


def run() -> None:
    try:
        asyncio.run(_main())
    except KeyboardInterrupt:
        pass
