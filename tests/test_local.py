import asyncio
from datetime import datetime, timezone

from monitor.config import load_secrets_optional
from monitor.local import LocalNotifier, run_cli
from monitor.store import Store

NOW = datetime(2026, 11, 17, 3, 0, tzinfo=timezone.utc)  # KST 12:00


def test_secrets_optional_none_without_token(monkeypatch, tmp_path):
    monkeypatch.delenv("TELEGRAM_BOT_TOKEN", raising=False)
    monkeypatch.delenv("TELEGRAM_CHAT_ID", raising=False)
    assert load_secrets_optional(env_file=tmp_path / "none.env") is None


def test_secrets_optional_reads_token(monkeypatch, tmp_path):
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "abc")
    monkeypatch.setenv("TELEGRAM_CHAT_ID", "5")
    assert load_secrets_optional(env_file=tmp_path / "none.env").chat_id == 5


def send(n, text, at=NOW):
    asyncio.run(n.send(text, at))


def test_local_notifier_writes_page_newest_first(tmp_path, capsys):
    n = LocalNotifier(tmp_path, open_browser=False)
    send(n, "[핫딜] 첫 글\n뽐뿌게시판 · 1분 전\nhttps://a.com/1")
    send(n, "🔥 [알뜰폰] 쌀먹 <b>요금제</b>\n뽐뿌 휴대폰포럼\nhttps://a.com/2")
    html = (tmp_path / "alerts.html").read_text(encoding="utf-8")
    assert html.index("https://a.com/2") < html.index("https://a.com/1")
    assert '<a href="https://a.com/2" target="_blank"' in html
    assert "&lt;b&gt;요금제" in html          # 제목의 HTML은 그대로 보이게
    assert 'class="alert fire"' in html        # 🔥 강조
    assert "11/17 12:00" in html                # KST 시각
    assert "쌀먹" in capsys.readouterr().out    # 콘솔에도 출력


def test_local_notifier_keeps_history_across_restart(tmp_path):
    send(LocalNotifier(tmp_path, open_browser=False), "[핫딜] A\nhttps://a.com/1")
    send(LocalNotifier(tmp_path, open_browser=False), "[핫딜] B\nhttps://a.com/2")
    html = (tmp_path / "alerts.html").read_text(encoding="utf-8")
    assert "https://a.com/1" in html and "https://a.com/2" in html


def test_local_notifier_limits_history(tmp_path):
    n = LocalNotifier(tmp_path, open_browser=False, keep=3)
    for i in range(5):
        send(n, f"[핫딜] {i}\nhttps://a.com/{i}")
    html = (tmp_path / "alerts.html").read_text(encoding="utf-8")
    assert "https://a.com/4" in html and "https://a.com/1" not in html


def test_cli_add_and_list(tmp_path):
    store = Store(tmp_path / "t.db")
    store.seed_keywords(["쌀먹"], [])
    assert run_cli(["추가", "상품권", "컬쳐랜드"], store, NOW) == "✅ 즉시알림 키워드 추가: 컬쳐랜드 (상품권만)"
    assert "컬쳐랜드(상품권)" in run_cli(["목록"], store, NOW)
    store.close()


def test_cli_help_when_empty(tmp_path):
    store = Store(tmp_path / "t.db")
    assert "추가" in run_cli([], store, NOW)
    store.close()
