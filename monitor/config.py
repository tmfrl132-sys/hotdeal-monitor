"""settings.yaml과 .env를 읽는다."""
from __future__ import annotations

import os
from dataclasses import dataclass
from datetime import time
from pathlib import Path

import yaml
from dotenv import load_dotenv

from monitor.models import Source

ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class ReactionRule:
    window_min: int
    recommends: int | None
    comments: int | None


@dataclass(frozen=True)
class Settings:
    sources: list[Source]
    keywords: dict[str, list[str]]
    card_keywords: list[str]
    discontinue_keywords: list[str]
    default_instant: list[str]
    default_exclude: list[str]
    giftcard_min_discount: float
    reaction: dict[str, ReactionRule]
    watch_hours: int
    digest_times: list[time]
    category_priority: list[str]
    user_agent: str
    host_gap_sec: float
    backoff_min: int
    fail_alert_after: int
    retention_days: int
    db_path: Path
    log_dir: Path
    noise_keywords: tuple[str, ...] = ()
    noise_max_won: int = 0


@dataclass(frozen=True)
class Secrets:
    bot_token: str
    chat_id: int


def _parse_time(text: str) -> time:
    hour, minute = text.split(":")
    return time(int(hour), int(minute))


def _resolve(path: str, base: Path) -> Path:
    p = Path(path)
    return p if p.is_absolute() else base / p


def load_settings(path: Path = ROOT / "settings.yaml") -> Settings:
    raw = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    base = Path(path).resolve().parent
    sources = [
        Source(
            name=s["name"],
            kind=s["kind"],
            url=s["url"],
            interval_sec=int(s["interval_sec"]),
            categories=tuple(s["categories"]),
            label=s.get("label", s["name"]),
            enabled=bool(s.get("enabled", True)),
        )
        for s in raw["sources"]
    ]
    return Settings(
        sources=sources,
        keywords={k: list(v or []) for k, v in raw["keywords"].items()},
        card_keywords=list(raw["card_keywords"]),
        discontinue_keywords=list(raw["discontinue_keywords"]),
        default_instant=list(raw["default_instant"]),
        default_exclude=list(raw["default_exclude"]),
        giftcard_min_discount=float(raw["giftcard_min_discount"]),
        reaction={
            k: ReactionRule(int(v["window_min"]), v.get("recommends"), v.get("comments"))
            for k, v in raw["reaction"].items()
        },
        watch_hours=int(raw["watch_hours"]),
        digest_times=[_parse_time(t) for t in raw["digest_times"]],
        category_priority=list(raw["category_priority"]),
        user_agent=raw["user_agent"],
        host_gap_sec=float(raw["host_gap_sec"]),
        backoff_min=int(raw["backoff_min"]),
        fail_alert_after=int(raw["fail_alert_after"]),
        retention_days=int(raw["retention_days"]),
        db_path=_resolve(raw["db_path"], base),
        log_dir=_resolve(raw["log_dir"], base),
        noise_keywords=tuple(raw.get("noise_keywords") or ()),
        noise_max_won=int(raw.get("noise_max_won", 0)),
    )


def load_secrets_optional(env_file: Path = ROOT / ".env") -> Secrets | None:
    """토큰이 없으면 None (로컬 모드). 토큰이 있는데 값이 잘못됐으면 종료한다."""
    load_dotenv(env_file, override=False)
    if not os.environ.get("TELEGRAM_BOT_TOKEN", "").strip():
        return None
    return load_secrets(env_file)


def load_secrets(env_file: Path = ROOT / ".env") -> Secrets:
    load_dotenv(env_file, override=False)
    token = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()
    chat_id = os.environ.get("TELEGRAM_CHAT_ID", "").strip()
    if not token:
        raise SystemExit("TELEGRAM_BOT_TOKEN이 .env에 없습니다")
    if not chat_id.lstrip("-").isdigit():
        raise SystemExit("TELEGRAM_CHAT_ID가 .env에 없거나 숫자가 아닙니다")
    return Secrets(bot_token=token, chat_id=int(chat_id))
