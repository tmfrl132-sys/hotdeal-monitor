from datetime import time
from pathlib import Path

import pytest

from monitor.config import load_secrets, load_settings

ROOT = Path(__file__).resolve().parents[1]


def test_load_settings_reads_sources():
    s = load_settings(ROOT / "settings.yaml")
    by_name = {src.name: src for src in s.sources}
    assert len(s.sources) == 11
    assert by_name["ppomppu:coupon"].interval_sec == 120
    assert by_name["youtube:jgb"].interval_sec == 1800
    assert by_name["youtube:jgb"].categories[0] == "신용카드"
    assert by_name["ppomppu:phone"].label == "뽐뿌 휴대폰포럼"


def test_load_settings_parses_digest_times():
    s = load_settings(ROOT / "settings.yaml")
    assert s.digest_times == [time(8, 0), time(12, 30), time(19, 0)]


def test_load_settings_reaction_rules():
    s = load_settings(ROOT / "settings.yaml")
    assert s.reaction["핫딜"].recommends == 5
    assert s.reaction["상품권"].comments is None


def test_load_secrets_missing_token_exits(monkeypatch, tmp_path):
    monkeypatch.delenv("TELEGRAM_BOT_TOKEN", raising=False)
    monkeypatch.setenv("TELEGRAM_CHAT_ID", "1")
    with pytest.raises(SystemExit, match="TELEGRAM_BOT_TOKEN"):
        load_secrets(env_file=tmp_path / "none.env")


def test_load_secrets_reads_env(monkeypatch, tmp_path):
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "abc")
    monkeypatch.setenv("TELEGRAM_CHAT_ID", "12345")
    sec = load_secrets(env_file=tmp_path / "none.env")
    assert sec.bot_token == "abc" and sec.chat_id == 12345
