"""유튜브 채널 RSS (https://www.youtube.com/feeds/videos.xml?channel_id=...)."""
from __future__ import annotations

import xml.etree.ElementTree as ET
from datetime import datetime, timezone

from monitor.models import Post

NS = {"a": "http://www.w3.org/2005/Atom", "yt": "http://www.youtube.com/xml/schemas/2015"}


def parse(source_name: str, text: str, now: datetime) -> list[Post]:
    root = ET.fromstring(text)
    posts = []
    for e in root.findall("a:entry", NS):
        vid = e.findtext("yt:videoId", namespaces=NS)
        title = (e.findtext("a:title", namespaces=NS) or "").strip()
        published = e.findtext("a:published", namespaces=NS)
        if not vid or not title or not published:
            continue
        posts.append(Post(
            source=source_name, post_id=vid, title=title,
            url=f"https://www.youtube.com/watch?v={vid}",
            posted_at=datetime.fromisoformat(published).astimezone(timezone.utc),
            recommends=None, comments=None,
        ))
    return posts
