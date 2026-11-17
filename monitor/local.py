"""텔레그램 없이 쓰는 로컬 모드: 알림을 콘솔과 브라우저 페이지에 보여주고, 키워드는 명령줄로 관리한다."""
from __future__ import annotations

import html
import json
import webbrowser
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

from monitor.commands import HELP, handle
from monitor.store import Store

KST = ZoneInfo("Asia/Seoul")

PAGE = """<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="60">
<title>딜 모니터</title>
<style>
:root {{ --bg:#f6f7f9; --card:#fff; --text:#1d1f23; --muted:#6b7280; --link:#1d4ed8; --fire:#fff4e5; --fire-line:#f59e0b; --line:#e5e7eb; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#111317; --card:#1b1e24; --text:#e8eaed; --muted:#9aa0a6; --link:#8ab4f8; --fire:#2b2112; --fire-line:#f59e0b; --line:#2d3139; }} }}
body {{ margin:0; background:var(--bg); color:var(--text); font:15px/1.5 -apple-system, "Malgun Gothic", sans-serif; }}
main {{ max-width:760px; margin:0 auto; padding:16px; }}
h1 {{ font-size:20px; margin:8px 0 4px; }}
.sub {{ color:var(--muted); font-size:13px; margin-bottom:16px; }}
.alert {{ background:var(--card); border:1px solid var(--line); border-radius:10px; padding:12px 14px; margin-bottom:10px; overflow-wrap:anywhere; }}
.alert.fire {{ background:var(--fire); border-left:4px solid var(--fire-line); }}
.time {{ color:var(--muted); font-size:12px; margin-bottom:4px; }}
.line {{ white-space:pre-wrap; }}
.line:first-of-type {{ font-weight:600; }}
a {{ color:var(--link); }}
.empty {{ color:var(--muted); text-align:center; padding:40px 0; }}
</style></head>
<body><main>
<h1>딜 모니터</h1>
<div class="sub">최근 알림 {count}건 · 1분마다 새로고침 · 마지막 갱신 {updated}</div>
{body}
</main></body></html>
"""


def _render_alert(at: str, text: str) -> str:
    lines = []
    for line in text.splitlines():
        esc = html.escape(line)
        if line.startswith(("http://", "https://")):
            esc = f'<a href="{esc}" target="_blank" rel="noopener">{esc}</a>'
        lines.append(f'<div class="line">{esc}</div>')
    cls = "alert fire" if text.startswith("🔥") else "alert"
    return f'<div class="{cls}"><div class="time">{html.escape(at)}</div>{"".join(lines)}</div>'


class LocalNotifier:
    def __init__(self, folder: Path, open_browser: bool = True, keep: int = 300):
        self.folder = Path(folder)
        self.folder.mkdir(parents=True, exist_ok=True)
        self.history = self.folder / "alerts.jsonl"
        self.page = self.folder / "alerts.html"
        self.keep = keep
        self._render(datetime.now(timezone.utc))
        if open_browser:
            webbrowser.open(self.page.resolve().as_uri())

    def _load(self) -> list[dict]:
        if not self.history.exists():
            return []
        items = []
        for line in self.history.read_text(encoding="utf-8").splitlines():
            try:
                items.append(json.loads(line))
            except json.JSONDecodeError:
                continue
        return items

    def _render(self, now: datetime) -> None:
        items = self._load()
        body = "\n".join(_render_alert(i["at"], i["text"]) for i in reversed(items)) \
            or '<div class="empty">아직 알림이 없습니다. 새 글이 조건에 맞으면 여기에 쌓입니다.</div>'
        self.page.write_text(PAGE.format(count=len(items), body=body,
                                         updated=f"{now.astimezone(KST):%m/%d %H:%M}"),
                             encoding="utf-8")

    async def send(self, text: str, now: datetime | None = None) -> None:
        now = now or datetime.now(timezone.utc)
        at = f"{now.astimezone(KST):%m/%d %H:%M}"
        items = self._load() + [{"at": at, "text": text}]
        items = items[-self.keep:]
        self.history.write_text("".join(json.dumps(i, ensure_ascii=False) + "\n" for i in items),
                                encoding="utf-8")
        self._render(now)
        try:
            print(f"\n[{at}] {text}", flush=True)
        except UnicodeEncodeError:  # 이모지를 못 쓰는 콘솔
            print(f"\n[{at}] {text}".encode("ascii", "replace").decode(), flush=True)


def run_cli(args: list[str], store: Store, now: datetime) -> str:
    """python -m monitor 추가 쌀먹 처럼 키워드를 관리한다."""
    if not args:
        return HELP.replace("/", "python -m monitor ")
    reply = handle("/" + " ".join(args), 0, 0, store,
                   lambda: "상태는 실행 중인 프로그램의 화면에서 확인하세요.", now)
    return reply or ""
