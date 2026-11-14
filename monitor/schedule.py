"""묶음 알림 시각 계산과 소스 상태 관리. 순수 로직만 둔다."""
from __future__ import annotations

from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

from monitor.fetcher import Blocked

KST = ZoneInfo("Asia/Seoul")
STALE_AFTER = timedelta(minutes=30)  # 이보다 늦은 묶음은 건너뛴다


def due_digest_slot(now_utc: datetime, times: list[time], last_slot: str | None) -> str | None:
    now = now_utc.astimezone(KST)
    candidates = []
    for day in (now.date() - timedelta(days=1), now.date()):
        for t in times:
            at = datetime.combine(day, t, tzinfo=KST)
            if at <= now:
                candidates.append(at)
    if not candidates:
        return None
    latest = max(candidates)
    slot = latest.strftime("%Y-%m-%dT%H:%M")
    if slot == last_slot or now - latest > STALE_AFTER:
        return None
    return slot


class SourceHealth:
    def __init__(self, label: str, fail_alert_after: int, backoff_min: int):
        self.label = label
        self.fail_alert_after = fail_alert_after
        self.backoff = timedelta(minutes=backoff_min)
        self.failures = 0
        self.alerted = False
        self.blocked_until: datetime | None = None
        self.last_success: datetime | None = None
        self.last_error: str | None = None

    def ready(self, now: datetime) -> bool:
        return self.blocked_until is None or now >= self.blocked_until

    def on_success(self, now: datetime) -> str | None:
        was_alerted = self.alerted
        self.failures, self.alerted, self.blocked_until = 0, False, None
        self.last_success, self.last_error = now, None
        return f"✅ {self.label} 수집이 회복됐습니다" if was_alerted else None

    def on_failure(self, err: Exception, now: datetime) -> str | None:
        self.failures += 1
        self.last_error = str(err)
        if isinstance(err, Blocked):
            self.blocked_until = now + self.backoff
        if self.failures >= self.fail_alert_after and not self.alerted:
            self.alerted = True
            extra = f" ({int(self.backoff.total_seconds() // 60)}분 쉬었다 재시도)" \
                if isinstance(err, Blocked) else ""
            return f"⚠️ {self.label} 수집 실패 {self.failures}회 연속: {err}{extra}"
        return None
