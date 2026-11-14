from datetime import datetime, time, timedelta, timezone

from monitor.fetcher import Blocked, FetchError
from monitor.schedule import SourceHealth, due_digest_slot

TIMES = [time(8, 0), time(12, 30), time(19, 0)]


def utc(y, mo, d, h, mi):
    return datetime(y, mo, d, h, mi, tzinfo=timezone.utc)


def test_digest_slot_kst_on_utc_clock():
    # UTC 03:31 = KST 12:31
    assert due_digest_slot(utc(2026, 11, 14, 3, 31), TIMES, None) == "2026-11-14T12:30"


def test_digest_slot_not_repeated():
    assert due_digest_slot(utc(2026, 11, 14, 3, 31), TIMES, "2026-11-14T12:30") is None


def test_digest_slot_skips_stale():
    # KST 13:10 — 12:30 슬롯이 40분 지났다
    assert due_digest_slot(utc(2026, 11, 14, 4, 10), TIMES, None) is None


def test_digest_slot_crosses_midnight():
    # KST 00:10 — 마지막 슬롯은 전날 19:00, 30분 넘게 지났다
    assert due_digest_slot(utc(2026, 11, 13, 15, 10), TIMES, None) is None


def test_digest_slot_before_first_time_of_day():
    # KST 07:59 — 아직 08:00 전
    assert due_digest_slot(utc(2026, 11, 13, 22, 59), TIMES, None) is None
    # KST 08:00 정각
    assert due_digest_slot(utc(2026, 11, 13, 23, 0), TIMES, None) == "2026-11-14T08:00"


NOW = utc(2026, 11, 14, 3, 0)


def test_health_alert_on_third_failure_only_once():
    h = SourceHealth("뽐뿌 쿠폰게시판", fail_alert_after=3, backoff_min=30)
    assert h.on_failure(FetchError("HTTP 500"), NOW) is None
    assert h.on_failure(FetchError("HTTP 500"), NOW) is None
    msg = h.on_failure(FetchError("HTTP 500"), NOW)
    assert msg.startswith("⚠️ 뽐뿌 쿠폰게시판") and "3회" in msg
    assert h.on_failure(FetchError("HTTP 500"), NOW) is None


def test_health_blocked_backoff_30min():
    h = SourceHealth("x", fail_alert_after=3, backoff_min=30)
    assert h.ready(NOW)
    h.on_failure(Blocked(403), NOW)
    assert not h.ready(NOW + timedelta(minutes=29))
    assert h.ready(NOW + timedelta(minutes=30))


def test_health_plain_failure_no_backoff():
    h = SourceHealth("x", fail_alert_after=3, backoff_min=30)
    h.on_failure(FetchError("timeout"), NOW)
    assert h.ready(NOW)


def test_health_recovery_message():
    h = SourceHealth("x", fail_alert_after=3, backoff_min=30)
    assert h.on_success(NOW) is None
    for _ in range(3):
        h.on_failure(FetchError("e"), NOW)
    assert h.on_success(NOW) == "✅ x 수집이 회복됐습니다"
    assert h.on_success(NOW) is None


def test_health_recovery_silent_if_never_alerted():
    h = SourceHealth("x", fail_alert_after=3, backoff_min=30)
    h.on_failure(FetchError("e"), NOW)
    assert h.on_success(NOW) is None
