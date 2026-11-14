"""SQLite 상태 저장소. 모든 시각은 UTC ISO 문자열로 저장한다."""
from __future__ import annotations

import sqlite3
from datetime import datetime, timedelta
from pathlib import Path

from monitor.models import Post

SCHEMA = """
CREATE TABLE IF NOT EXISTS posts (
    source TEXT NOT NULL, post_id TEXT NOT NULL,
    title TEXT NOT NULL, url TEXT NOT NULL, posted_at TEXT NOT NULL,
    recommends INTEGER, comments INTEGER, ended INTEGER NOT NULL DEFAULT 0,
    first_seen TEXT NOT NULL,
    PRIMARY KEY (source, post_id)
);
CREATE TABLE IF NOT EXISTS handled (
    source TEXT NOT NULL, post_id TEXT NOT NULL,
    how TEXT NOT NULL, at TEXT,
    PRIMARY KEY (source, post_id)
);
CREATE TABLE IF NOT EXISTS digest_queue (
    source TEXT NOT NULL, post_id TEXT NOT NULL,
    category TEXT NOT NULL, queued_at TEXT NOT NULL,
    PRIMARY KEY (source, post_id)
);
CREATE TABLE IF NOT EXISTS keywords (
    kind TEXT NOT NULL, word TEXT NOT NULL, category TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (kind, word, category)
);
CREATE TABLE IF NOT EXISTS seeded_sources (source TEXT PRIMARY KEY);
CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT NOT NULL);
"""


def _iso(dt: datetime) -> str:
    return dt.isoformat()


def _dt(text: str) -> datetime:
    return datetime.fromisoformat(text)


class Store:
    def __init__(self, path: Path):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(str(path))
        self.db.executescript(SCHEMA)
        self.db.commit()

    def close(self) -> None:
        self.db.close()

    # ---- 글 관측 ----
    def upsert_post(self, p: Post, now: datetime) -> bool:
        cur = self.db.execute(
            "INSERT OR IGNORE INTO posts VALUES (?,?,?,?,?,?,?,?,?)",
            (p.source, p.post_id, p.title, p.url, _iso(p.posted_at),
             p.recommends, p.comments, int(p.ended), _iso(now)),
        )
        is_new = cur.rowcount == 1
        if not is_new:
            self.db.execute(
                "UPDATE posts SET title=?, url=?, recommends=?, comments=?, ended=? "
                "WHERE source=? AND post_id=?",
                (p.title, p.url, p.recommends, p.comments, int(p.ended), p.source, p.post_id),
            )
        self.db.commit()
        return is_new

    @staticmethod
    def _row_to_post(r) -> Post:
        return Post(r[0], r[1], r[2], r[3], _dt(r[4]), r[5], r[6], bool(r[7]))

    _COLS = "p.source, p.post_id, p.title, p.url, p.posted_at, p.recommends, p.comments, p.ended"

    def get_post(self, source: str, post_id: str) -> Post | None:
        r = self.db.execute(
            f"SELECT {self._COLS} FROM posts p WHERE source=? AND post_id=?", (source, post_id)
        ).fetchone()
        return self._row_to_post(r) if r else None

    def posts_watching(self, now: datetime, watch_hours: int) -> list[Post]:
        rows = self.db.execute(
            f"SELECT {self._COLS} FROM posts p LEFT JOIN handled h "
            "ON h.source=p.source AND h.post_id=p.post_id WHERE h.source IS NULL"
        ).fetchall()
        limit = now - timedelta(hours=watch_hours)
        return [p for p in map(self._row_to_post, rows) if p.posted_at >= limit]

    # ---- 처리 결과 ----
    def mark_sent(self, source: str, post_id: str, how: str, now: datetime) -> None:
        self.db.execute("INSERT OR REPLACE INTO handled VALUES (?,?,?,?)",
                        (source, post_id, how, _iso(now)))
        self.db.commit()

    def mark_dropped(self, source: str, post_id: str) -> None:
        self.db.execute("INSERT OR IGNORE INTO handled VALUES (?,?,'dropped',NULL)",
                        (source, post_id))
        self.db.commit()

    def is_handled(self, source: str, post_id: str) -> bool:
        return self.db.execute(
            "SELECT 1 FROM handled WHERE source=? AND post_id=?", (source, post_id)
        ).fetchone() is not None

    # ---- 묶음 ----
    def enqueue_digest(self, source: str, post_id: str, category: str, now: datetime) -> None:
        self.db.execute("INSERT OR IGNORE INTO digest_queue VALUES (?,?,?,?)",
                        (source, post_id, category, _iso(now)))
        self.db.execute("INSERT OR IGNORE INTO handled VALUES (?,?,'queued',?)",
                        (source, post_id, _iso(now)))
        self.db.commit()

    def pop_digest(self) -> list[tuple[Post, str]]:
        rows = self.db.execute(
            f"SELECT {self._COLS}, q.category, q.queued_at FROM digest_queue q "
            "JOIN posts p ON p.source=q.source AND p.post_id=q.post_id ORDER BY q.queued_at"
        ).fetchall()
        items = [(self._row_to_post(r), r[8]) for r in rows]
        for p, _ in items:
            self.db.execute("UPDATE handled SET how='digest' WHERE source=? AND post_id=?",
                            (p.source, p.post_id))
        self.db.execute("DELETE FROM digest_queue")
        self.db.commit()
        return items

    def count_sent_since(self, since: datetime) -> int:
        """since 이후 실제로 보낸 글 수 (즉시 + 묶음)."""
        return self.db.execute(
            "SELECT COUNT(*) FROM handled WHERE how IN ('instant','digest') AND at >= ?",
            (_iso(since),),
        ).fetchone()[0]

    # ---- 처음 보는 소스 ----
    def source_seeded(self, source: str) -> bool:
        return self.db.execute("SELECT 1 FROM seeded_sources WHERE source=?",
                               (source,)).fetchone() is not None

    def mark_seeded(self, source: str) -> None:
        self.db.execute("INSERT OR IGNORE INTO seeded_sources VALUES (?)", (source,))
        self.db.commit()

    # ---- 키워드 ----
    def seed_keywords(self, instant: list[str], exclude: list[str]) -> None:
        if self.get_state("keywords_seeded"):
            return
        for w in instant:
            self.add_keyword("instant", w, None)
        for w in exclude:
            self.add_keyword("exclude", w, None)
        self.set_state("keywords_seeded", "1")

    def add_keyword(self, kind: str, word: str, category: str | None) -> None:
        self.db.execute("INSERT OR IGNORE INTO keywords VALUES (?,?,?)",
                        (kind, word, category or ""))
        self.db.commit()

    def remove_keyword(self, word: str) -> int:
        n = self.db.execute("DELETE FROM keywords WHERE word=?", (word,)).rowcount
        self.db.commit()
        return n

    def keywords(self, kind: str) -> list[tuple[str, str | None]]:
        rows = self.db.execute(
            "SELECT word, category FROM keywords WHERE kind=? ORDER BY rowid", (kind,)
        ).fetchall()
        return [(w, c or None) for w, c in rows]

    # ---- 기타 상태 ----
    def get_state(self, key: str) -> str | None:
        r = self.db.execute("SELECT value FROM state WHERE key=?", (key,)).fetchone()
        return r[0] if r else None

    def set_state(self, key: str, value: str) -> None:
        self.db.execute("INSERT OR REPLACE INTO state VALUES (?,?)", (key, value))
        self.db.commit()

    def delete_state(self, key: str) -> None:
        self.db.execute("DELETE FROM state WHERE key=?", (key,))
        self.db.commit()

    # ---- 정리 ----
    def cleanup(self, now: datetime, retention_days: int) -> None:
        limit = _iso(now - timedelta(days=retention_days))
        old = self.db.execute("SELECT source, post_id FROM posts WHERE first_seen < ?",
                              (limit,)).fetchall()
        for key in old:
            self.db.execute("DELETE FROM handled WHERE source=? AND post_id=?", key)
            self.db.execute("DELETE FROM digest_queue WHERE source=? AND post_id=?", key)
        self.db.execute("DELETE FROM posts WHERE first_seen < ?", (limit,))
        self.db.commit()
