# 딜 모니터 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 공개 커뮤니티와 유튜브 채널을 감시하다가 조건에 맞는 글의 링크를 텔레그램으로 보내는 Python 상주 프로그램을 만든다.

**Architecture:** 하나의 asyncio 프로세스가 두 루프를 돈다. 수집 루프는 소스별 주기로 가져오기 → 파싱 → 분류 → 판정 → 발송을 한다. 봇 루프는 텔레그램 `getUpdates` 롱폴링으로 명령을 받는다. 파서, 분류, 판정, 메시지 서식은 네트워크 없는 순수 함수로 두고 저장된 샘플로 테스트한다. 상태는 SQLite 파일 하나에 둔다.

**Tech Stack:** Python 3.11+ (PC는 3.13), httpx, beautifulsoup4 + lxml, PyYAML, python-dotenv, tzdata(Windows의 `zoneinfo`용), pytest. 텔레그램은 라이브러리 없이 httpx로 Bot API를 직접 호출한다.

**Spec:** `docs/superpowers/specs/2026-11-13-deal-monitor-design.md`

## Global Constraints

- 저장소 루트: `c:\Users\tmfrl\Desktop\ai연습하기\deal-monitor\`. 별도 git 저장소이고, 상위 저장소 `.gitignore`에 `deal-monitor/`를 추가한다.
- `.env`(`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`), `*.db`, `logs/`는 커밋하지 않는다.
- Windows와 Linux에서 같은 코드로 돈다. 경로는 `pathlib`를 쓰고, 시간대는 `zoneinfo.ZoneInfo("Asia/Seoul")`를 쓴다.
- 같은 호스트에 대한 요청 간격은 최소 2초다. User-Agent는 일반 브라우저 문자열이다.
- 403 또는 429를 받으면 해당 소스를 30분 쉰다. 3번 연속 실패하면 1번 알리고, 회복하면 1번 알린다.
- 모든 기준 숫자, 키워드 사전, 소스 목록은 `settings.yaml`에 둔다. 코드에 숫자를 박지 않는다.
- 사용자에게 보이는 문구는 한국어다.
- 커밋 메시지는 한국어 conventional 형식으로 쓰고, 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`을 붙인다.

## Review Focus

1. **반응 수가 갱신된 글의 중복 발송:** 같은 글이 매 수집마다 다시 나와도 알림은 1번만 가야 한다. 즉시알림 뒤 반응 기준을 넘어도 묶음에 다시 들어가면 안 된다. → Task 2, Task 6 테스트
2. **처음 보는 소스의 글 폭주:** 첫 실행뿐 아니라, 나중에 settings.yaml에 소스를 추가했을 때도 그 소스의 기존 글은 알리지 않아야 한다. → Task 7 테스트
3. **서버 시간대:** Oracle 서버는 UTC다. 묶음 시각은 KST 08:00, 12:30, 19:00에 정확히 1번씩 나가야 하고, 프로그램을 재시작해도 같은 묶음을 두 번 보내면 안 된다. → Task 7 테스트
4. **소유자 외 명령:** 다른 chat_id가 보낸 명령은 응답 없이 무시하고 상태도 바꾸지 않아야 한다. → Task 5 테스트
5. **할인율 오탐:** 핫딜 제목의 "50% 할인"이 상품권 즉시알림을 일으키면 안 된다. 할인율 기준은 상품권 카테고리에만 적용한다. "5.5%" 같은 소수도 읽어야 한다. → Task 3 테스트

---

## 파일 구조

```
deal-monitor/
  monitor/
    __init__.py
    __main__.py        # python -m monitor → main.run()
    models.py          # Post, Source, Verdict 데이터 클래스
    config.py          # settings.yaml + .env 읽기
    store.py           # SQLite 상태
    sources/
      __init__.py      # parse(kind, text) 분기
      ppomppu.py       # 뽐뿌 RSS 파서
      youtube.py       # 유튜브 RSS 파서
      clien.py         # 클리앙 목록 HTML 파서
      fmkorea.py       # FM코리아 핫딜 HTML 파서
    rules.py           # 카테고리 지정 + 판정
    messages.py        # 텔레그램 메시지 서식, 4096자 분할
    commands.py        # 명령 해석과 실행 (네트워크 없음)
    telegram.py        # Bot API 호출 (sendMessage, getUpdates)
    fetcher.py         # httpx 가져오기, 호스트별 간격, 백오프
    main.py            # 루프와 스케줄
  settings.yaml
  requirements.txt
  .env.example
  .gitignore
  README.md
  deploy/deal-monitor.service
  tests/
    fixtures/          # 실제 응답 샘플 (Task 3, 4에서 저장)
    test_*.py
```

## 공통 타입 (Task 1에서 정의, 모든 Task가 사용)

```python
# monitor/models.py
@dataclass(frozen=True)
class Source:
    name: str             # "ppomppu:coupon", "clien:jirum", "fmkorea:hotdeal", "youtube:jgb"
    kind: str             # "ppomppu_rss" | "clien" | "fmkorea" | "youtube"
    url: str
    interval_sec: int
    categories: tuple[str, ...]   # 기본 카테고리, 첫 번째가 기본값

@dataclass(frozen=True)
class Post:
    source: str           # Source.name
    post_id: str          # 소스 안에서 유일 (글번호, 영상 ID)
    title: str
    url: str
    posted_at: datetime   # tz-aware UTC
    recommends: int | None
    comments: int | None
    ended: bool = False   # 사이트가 붙인 종료/품절 표시

@dataclass(frozen=True)
class Verdict:
    action: str           # "drop" | "instant" | "queue" | "wait"
    category: str         # "재테크" | "알뜰폰" | "신용카드" | "핫딜" | "상품권"
    fire: bool = False    # 🔥 표시
    label: str = ""       # 메시지의 카테고리 라벨, 예: "신용카드·단종"
```

---

### Task 1: 뼈대, 설정, 모델

**Files:**
- Create: `monitor/__init__.py`, `monitor/__main__.py`, `monitor/models.py`, `monitor/config.py`, `settings.yaml`, `requirements.txt`, `.env.example`, `.gitignore`
- Modify: 상위 저장소 `c:\Users\tmfrl\Desktop\ai연습하기\.gitignore` (`deal-monitor/` 추가)
- Test: `tests/test_config.py`

**Interfaces:**
- Produces: `models.py`의 세 클래스(위). `config.load_settings(path: Path) -> Settings`. `config.load_secrets() -> Secrets(bot_token: str, chat_id: int)`. 필수 값이 없으면 `SystemExit("TELEGRAM_BOT_TOKEN이 .env에 없습니다")` 형식으로 끝낸다.
- `Settings` 필드: `sources: list[Source]`, `keywords: dict[str, list[str]]`(카테고리 → 키워드), `card_keywords`, `discontinue_keywords`, `default_instant: list[str]`, `default_exclude: list[str]`, `giftcard_min_discount: float`, `reaction: dict[str, ReactionRule]`, `watch_hours: int`, `digest_times: list[time]`, `category_priority: list[str]`, `user_agent: str`, `host_gap_sec: float`, `backoff_min: int`, `fail_alert_after: int`, `retention_days: int`, `db_path: Path`, `log_dir: Path`.
- `ReactionRule(window_min: int, recommends: int | None, comments: int | None)`.

- [ ] **Step 1:** `git init`을 하고, 상위 `.gitignore`에 `deal-monitor/`를 추가한다. `.gitignore`에는 `.env`, `*.db`, `logs/`, `.venv/`, `__pycache__/`, `.pytest_cache/`를 넣는다. 설계서와 이 계획을 첫 커밋으로 올린다.
- [ ] **Step 2:** `settings.yaml`을 설계서 값 그대로 작성한다.
  - 소스 9개: 뽐뿌 `coupon`, `phone`, `ppomppu`는 120초, 뽐뿌 `money`, `event`, `stock`, `pmarket`, 클리앙 `jirum`, FM코리아 `hotdeal`은 600초, 유튜브는 1800초다. URL은 설계서 3절을 따른다.
  - 키워드 사전은 설계서 6.2와 6.6을 따른다.
  - `default_instant: [쌀먹]`
  - `default_exclude: [종료, 품절, 마감, 질문, 후기]`
  - `giftcard_min_discount: 5`
  - reaction:
    - 핫딜: `{window_min: 30, recommends: 5, comments: 20}`
    - 재테크, 알뜰폰: `{60, 3, 15}`
    - 상품권: `{30, 3, null}`
  - `watch_hours: 6`
  - `digest_times: ["08:00", "12:30", "19:00"]`
  - `category_priority: [상품권, 알뜰폰, 재테크, 핫딜]`
  - `host_gap_sec: 2`, `backoff_min: 30`, `fail_alert_after: 3`, `retention_days: 90`
- [ ] **Step 3: 실패하는 테스트 작성**
  - `test_load_settings_reads_sources`: 소스가 9개이고, `ppomppu:coupon`의 `interval_sec == 120`, `youtube:jgb`의 `interval_sec == 1800`, `categories[0] == "신용카드"`인지 확인한다.
  - `test_load_settings_parses_digest_times`: `digest_times == [time(8,0), time(12,30), time(19,0)]`인지 확인한다.
  - `test_load_secrets_missing_token_exits`: `monkeypatch.delenv`로 토큰을 지웠을 때 `SystemExit`이 나는지 확인한다.
- [ ] **Step 4:** `pytest tests/test_config.py -v`를 실행해 FAIL을 확인한다.
- [ ] **Step 5:** `config.py`와 `models.py`를 구현한다. `.env`는 저장소 루트에서 `python-dotenv`로 읽는다.
- [ ] **Step 6:** 같은 명령으로 PASS를 확인한다.
- [ ] **Step 7:** 커밋한다. `feat: 설정 파일과 기본 모델`

### Task 2: SQLite 상태 저장소

**Files:**
- Create: `monitor/store.py`
- Test: `tests/test_store.py`

**Interfaces:**
- Produces: `Store(path: Path)`, 그리고 다음 메서드. 모든 시각은 UTC aware `datetime`이다.
  - 글 관측
    - `upsert_post(p: Post, now: datetime) -> bool`: 처음 본 글이면 True를 돌려준다. 반응 수와 제목은 갱신한다.
    - `get_post(source: str, post_id: str) -> Post | None`
    - `posts_watching(now: datetime, watch_hours: int) -> list[Post]`: 보내지 않았고 버리지 않았으며, 올라온 지 `watch_hours` 이내인 글이다.
  - 처리 결과
    - `mark_sent(source, post_id, how: str, now)`: `how`는 `"instant"` 또는 `"digest"`다.
    - `is_handled(source, post_id) -> bool`: 보냈거나, 묶음에 넣었거나, 버린 글이면 True다.
    - `mark_dropped(source, post_id)`
  - 묶음
    - `enqueue_digest(source, post_id, category: str, now)`
    - `pop_digest() -> list[tuple[Post, str]]`: 꺼낸 항목은 `mark_sent(..., "digest")`로 기록한다.
  - 처음 보는 소스
    - `source_seeded(source: str) -> bool`
    - `mark_seeded(source: str)`
  - 키워드
    - `add_keyword(kind: str, word: str, category: str | None)`: `kind`는 `"instant"` 또는 `"exclude"`다.
    - `remove_keyword(word: str) -> int`: 지운 개수를 돌려준다.
    - `keywords(kind: str) -> list[tuple[str, str | None]]`
    - 처음 만들 때 settings의 `default_instant`와 `default_exclude`를 넣는다(`seed_keywords(instant, exclude)`). 이후에는 DB가 기준이다.
  - 기타 상태
    - `get_state(key: str) -> str | None`
    - `set_state(key: str, value: str)`: 일시정지 종료 시각, 텔레그램 offset, 마지막 묶음 슬롯, 소스별 실패 횟수와 백오프 종료 시각을 저장한다.
  - 정리
    - `cleanup(now, retention_days: int)`

- [ ] **Step 1: 실패하는 테스트 작성** (`tmp_path`의 DB 사용)
  - `test_upsert_returns_true_only_first_time`
  - `test_upsert_updates_reactions`: 두 번째 upsert 뒤 `get_post`의 추천 수가 새 값인지 확인한다.
  - `test_sent_post_not_watching_and_handled`
  - `test_digest_pop_marks_sent_and_empties`: pop 뒤 `is_handled`가 True이고, 다시 pop하면 `[]`인지 확인한다.
  - `test_keywords_add_remove_and_seed_once`: `seed_keywords`를 두 번 불러도 "쌀먹"이 1개뿐인지 확인한다. `remove_keyword("쌀먹") == 1`인지도 확인한다.
  - `test_cleanup_removes_old_posts`
- [ ] **Step 2:** `pytest tests/test_store.py -v`를 실행해 FAIL을 확인한다.
- [ ] **Step 3:** 구현한다. 테이블은 `posts`, `handled`, `digest_queue`, `keywords`, `state`, `seeded_sources`다. 키는 `(source, post_id)`다. 스키마는 빌더 재량이다.
- [ ] **Step 4:** PASS를 확인한다.
- [ ] **Step 5:** 커밋한다. `feat: SQLite 상태 저장소`

### Task 3: 분류와 판정 규칙

**Files:**
- Create: `monitor/rules.py`
- Test: `tests/test_rules.py`

**Interfaces:**
- Consumes: `Post`, `Source`, `Settings`, `ReactionRule`, `Verdict`
- Produces:
  - `parse_discount(title: str) -> float | None`: 제목의 `n%` 또는 `n.n%` 중 가장 큰 값.
  - `categorize(post: Post, source: Source, s: Settings) -> str`
  - `judge(post: Post, source: Source, s: Settings, instant: list[tuple[str, str|None]], exclude: list[tuple[str, str|None]], now: datetime) -> Verdict`
  - `judge_youtube(post: Post, s: Settings, exclude: list[...]) -> Verdict`

**판정 규칙 (설계서 6.2, 6.3, 6.6)**
- 키워드 비교: 소문자로 바꾸고 공백을 없앤 뒤 부분 일치로 본다.
- categorize:
  1. 카드 단종 조건(단종 키워드와 카드 키워드가 함께 있음)이면 `"신용카드"`다.
  2. 아니면 `source.categories` 가운데 제목 키워드가 맞는 카테고리를 모은다. 여기에 `source.categories`가 아닌 상품권·알뜰폰 키워드 일치도 더한다. 상품권 대란은 어느 게시판에든 올라오기 때문이다.
  3. 모은 것 중 `category_priority`의 순서로 하나를 고른다. 아무것도 맞지 않으면 `source.categories[0]`이다.
- judge 순서:
  1. `post.ended`이거나, 제외 키워드(카테고리가 없거나 카테고리가 같은 것)에 맞으면 `drop`이다.
  2. 즉시알림 키워드에 맞으면 `instant`다. 키워드가 "쌀먹"이면 `fire=True`다.
  3. 카테고리가 상품권이고 `parse_discount >= giftcard_min_discount`이면 `instant`다.
  4. 카드 단종 조건이면 `instant`이고 `fire=True`, `label="신용카드·단종"`이다.
  5. 반응 기준:
     - 나이가 `window_min` 이하이고 추천 또는 댓글이 기준 이상이면 `queue`다.
     - 나이가 `window_min`을 넘고 `watch_hours` 이하이면, 기준의 **2배** 이상일 때 `queue`다. 늦게 뜨는 글은 더 확실할 때만 보낸다(빌더 판단, 설계서의 "6시간까지 지켜봄"을 구체화한 것).
     - 그 밖에는 `wait`이고, `watch_hours`를 넘으면 `drop`이다.
     - 반응 수가 `None`이면 해당 조건은 거짓으로 본다.
- judge_youtube: 단종 키워드가 있으면 `instant`, `fire`, `"신용카드·단종"`이다. 카드 키워드가 있으면 `instant`, `"신용카드"`다. 둘 다 아니면, 제외 키워드에 맞을 때 `drop`이고 아닐 때 `queue`, `"핫딜"`이다.

- [ ] **Step 1: 실패하는 테스트 작성**
  - `test_parse_discount`
    - `"[컬쳐랜드] 문화상품권 5.5% 할인" -> 5.5`
    - `"10% 쿠폰 + 3%" -> 10`
    - `"할인 없음" -> None`
  - `test_ssalmeok_is_instant_with_fire`: 출처 `ppomppu:phone`, 제목 `"○○모바일 0원 요금제 쌀먹"`이면 `Verdict("instant","알뜰폰",fire=True)`다.
  - `test_giftcard_discount_instant`: 출처 `ppomppu:ppomppu`, 제목 `"[G마켓] 신세계상품권 6% 할인"`이면 `instant`, `상품권`이다.
  - `test_hotdeal_50pct_not_instant`: 제목 `"[쿠팡] 니트 50% 할인"`, 추천 0, 1분 전이면 `wait`, `핫딜`이다. (Review Focus 5)
  - `test_exclude_beats_instant`: 제목 `"쌀먹 요금제 종료"`이면 `drop`이다.
  - `test_category_scoped_keyword`: 즉시알림 `("컬쳐랜드","상품권")`은 상품권 글에만 맞는다. 카테고리가 핫딜인 글에서는 맞지 않는다.
  - `test_card_discontinue_community_instant`: 제목 `"○○카드 단종 확정"`이면 `instant`, `신용카드`, `fire=True`다.
  - `test_reaction_within_window_queues`: 핫딜, 20분 전, 추천 5이면 `queue`다.
  - `test_reaction_late_needs_double`: 핫딜, 2시간 전, 추천 5이면 `wait`이고, 추천 10이면 `queue`다.
  - `test_reaction_expired_drops`: 7시간 전이면 `drop`이다.
  - `test_youtube_three_way`: `"○○카드 단종, 막차"`는 instant·fire, `"이번달 신용카드 추천"`은 instant, `"뚜레쥬르 50% 할인"`은 queue·핫딜이다.
  - `test_youtube_discontinue_ignores_exclude`: 제외 키워드 "종료"가 있어도 `"○○카드 발급중단 종료 임박"`은 instant다.
- [ ] **Step 2:** `pytest tests/test_rules.py -v`를 실행해 FAIL을 확인한다.
- [ ] **Step 3:** 구현한다.
- [ ] **Step 4:** PASS를 확인한다.
- [ ] **Step 5:** 커밋한다. `feat: 카테고리 지정과 알림 판정 규칙`

### Task 4: 소스 파서와 샘플

**Files:**
- Create: `monitor/sources/__init__.py`, `ppomppu.py`, `youtube.py`, `clien.py`, `fmkorea.py`, `tests/fixtures/*`
- Test: `tests/test_sources.py`

**Interfaces:**
- Produces: `sources.parse(source: Source, text: str) -> list[Post]`. 각 모듈은 `parse(source_name: str, text: str) -> list[Post]`을 가진다. 파싱에 실패한 항목은 건너뛴다. 전체가 0건이면 `ParseError`를 던진다. 0건은 사이트 구조가 바뀌었다는 신호이기 때문이다.

- [ ] **Step 1: 샘플 저장.** 실제 응답을 `tests/fixtures/`에 저장한다.
  - `ppomppu_coupon.xml`, `ppomppu_ppomppu.xml`
  - `youtube_jgb.xml` (`channel_id=UC1KHW0JH7zToxC2H69axgzw`)
  - `clien_jirum.html`, `fmkorea_hotdeal.html`
  - FM코리아가 JS 챌린지 페이지를 주면 그 사실을 README에 적는다. 이 경우 settings에서 `enabled: false`로 두고 파서 테스트는 건너뛴다(`pytest.skip`).
- [ ] **Step 2: 뽐뿌 `hits` 순서 확정.** RSS 항목 2~3개의 실제 글 페이지(view.php)를 열어 댓글, 조회, 추천 값과 대조한다. 확정한 순서를 `ppomppu.py`에 주석으로 남긴다. 페이지가 403이면 브라우저 확인을 사용자에게 요청하고, 그동안은 `[댓글|조회|추천|비추천]`으로 둔다.
- [ ] **Step 3: 실패하는 테스트 작성** (샘플마다)
  - `test_ppomppu_rss_fields`: 첫 항목의 title, url, `post_id`, `posted_at`, `recommends`, `comments`를 확인한다. `url`의 `&amp;`가 풀렸는지, `post_id`가 url의 `no` 값인지, `posted_at.tzinfo`가 UTC인지 본다. 기대값은 저장한 샘플에서 읽어 적는다.
  - `test_youtube_rss_fields`: `post_id`가 `yt:videoId`이고, url이 `https://www.youtube.com/watch?v=`로 시작하고, recommends가 None인지 확인한다.
  - `test_clien_list_fields`, `test_fmkorea_list_fields`: 공지 글을 제외하고 첫 글의 필드를 확인한다. 상대 시각("3분 전", "14:22")은 KST로 해석한 뒤 UTC로 바꾼다.
  - `test_empty_raises_parse_error`
- [ ] **Step 4:** FAIL을 확인한다.
- [ ] **Step 5:** 구현한다. RSS는 `xml.etree.ElementTree`, HTML은 BeautifulSoup + lxml로 읽는다. 선택자는 샘플을 보고 빌더가 정한다.
- [ ] **Step 6:** PASS를 확인한다.
- [ ] **Step 7:** 커밋한다. `feat: 뽐뿌·유튜브·클리앙·FM코리아 파서`

### Task 5: 텔레그램 명령과 메시지 서식

**Files:**
- Create: `monitor/messages.py`, `monitor/commands.py`, `monitor/telegram.py`
- Test: `tests/test_messages.py`, `tests/test_commands.py`

**Interfaces:**
- Consumes: `Store`, `Post`, `Verdict`
- Produces:
  - `messages.format_instant(post: Post, v: Verdict, source_label: str, now: datetime) -> str`
  - `messages.format_digest(items: list[tuple[Post, str]], labels: dict[str,str], now) -> list[str]`: 카테고리별로 묶고, 4096자를 넘으면 나눈다.
  - `commands.handle(text: str, chat_id: int, owner_id: int, store: Store, status: Callable[[], str], now: datetime) -> str | None`: 소유자가 아니거나 명령이 아니면 None이다.
  - `telegram.Bot(token: str, client: httpx.AsyncClient)`
    - `send(chat_id: int, text: str) -> None`: `disable_web_page_preview=True`로 보낸다. 실패하면 3번 재시도하고, 429면 `retry_after`를 지킨다.
    - `updates(offset: int, timeout: int = 30) -> list[dict]`
- 메시지 서식 (설계서 6.4, 6.6):
  ```
  🔥 [알뜰폰] <제목>
  뽐뿌 휴대폰포럼 · 3분 전 · 추천 2 · 댓글 11
  <url>
  ```
  - 🔥는 `fire`일 때만 붙인다.
  - 반응 수가 None이면 그 항목을 뺀다.
  - 라벨은 `v.label`이 있으면 그것을, 없으면 `v.category`를 쓴다.
  - 묶음 메시지 첫 줄은 `📬 묶음 알림 (12:30)`이다. 카테고리 제목은 `■ 핫딜 (5)`이고, 그 아래 글마다 2줄(제목 줄, url 줄)을 둔다.
- 명령 (설계서 6.5): `/추가`, `/제외`, `/삭제`, `/목록`, `/일시정지`, `/재개`, `/상태`, `/도움말`과 영문 별칭 `/add /except /del /list /pause /resume /status /help`.
  - 카테고리 인자는 재테크, 알뜰폰, 카드(=신용카드), 핫딜, 상품권 중 하나일 때만 카테고리로 본다. 그 밖의 단어는 키워드의 일부로 본다.
  - 일시정지 시간 형식은 `30분`, `2시간`, `1시간30분`, `90m`, `2h`다. 형식이 틀리면 사용법을 답한다.
  - 응답 문구는 설계 대화의 예시를 따른다. 예: `✅ 즉시알림 키워드 추가: 컬쳐랜드 (상품권만)`, `🚫 제외 키워드 추가: 품절`, `🗑 삭제됨: 컬쳐랜드`.

- [ ] **Step 1: 실패하는 테스트 작성**
  - `test_format_instant_fire_and_counts`
  - `test_format_instant_omits_none_counts`
  - `test_digest_splits_over_4096`: 긴 제목 글 100개를 넣고, 모든 조각이 4096자 이하인지, 모든 url이 빠짐없이 들어 있는지 확인한다.
  - `test_add_global_and_scoped`
  - `test_add_with_category_alias_card`: `/추가 카드 단종`은 카테고리가 신용카드여야 한다.
  - `test_remove_and_list`
  - `test_pause_sets_until_and_resume_clears`: `/일시정지 2시간` 뒤 state `pause_until`이 now+2h인지 확인한다.
  - `test_pause_bad_format_returns_usage`
  - `test_non_owner_ignored`: 반환값이 None이고, 키워드 수가 그대로인지 확인한다. (Review Focus 4)
  - `test_english_alias`: `/add 쌀먹`이 `/추가 쌀먹`과 같게 동작하는지 확인한다.
- [ ] **Step 2:** FAIL을 확인한다.
- [ ] **Step 3:** 구현한다. `telegram.py`는 단위 테스트를 하지 않고 Task 8에서 실제로 확인한다.
- [ ] **Step 4:** PASS를 확인한다.
- [ ] **Step 5:** 커밋한다. `feat: 텔레그램 명령과 알림 서식`

### Task 6: 수집 처리 한 사이클

**Files:**
- Create: `monitor/fetcher.py`, `monitor/pipeline.py`
- Test: `tests/test_pipeline.py`, `tests/test_fetcher.py`

**Interfaces:**
- Consumes: `sources.parse`, `rules.judge`, `rules.judge_youtube`, `Store`, `messages.format_instant`
- Produces:
  - `fetcher.Fetcher(client: httpx.AsyncClient, user_agent: str, host_gap_sec: float)`
    - `get(url: str) -> str`: 같은 호스트의 직전 요청과 `host_gap_sec` 간격을 둔다. 403이나 429면 `Blocked(status)`를, 그 밖의 실패면 `FetchError`를 던진다.
  - `pipeline.process(source: Source, posts: list[Post], store: Store, s: Settings, now: datetime) -> list[str]`
    - 지금 보낼 즉시알림 메시지 목록을 돌려준다.
    - 판정한 결과대로 store에 기록한다(`mark_sent instant`, `enqueue_digest`, `mark_dropped`).
    - 이미 `is_handled`인 글은 반응만 갱신하고 판정하지 않는다.
    - `source_seeded`가 False면 모든 글을 `mark_dropped`하고 `mark_seeded`한 뒤 `[]`를 돌려준다.
    - 일시정지 중이면 즉시알림은 `mark_sent(..., "paused")`로 버리고 빈 목록을 돌려준다. 묶음 대상은 그대로 넣는다.
  - `pipeline.recheck_watching(store, s, sources: dict[str, Source], now) -> None`: 이번 사이클에 가져오지 않은 소스의 대기 글도 나이 기준으로 판정한다. 6시간이 지나면 `drop`한다.

- [ ] **Step 1: 실패하는 테스트 작성** (`tmp_path` DB, Post를 직접 만들어 사용)
  - `test_first_cycle_seeds_without_sending`: 결과가 `[]`이고, 이후 같은 글이 다시 와도 `[]`인지 확인한다.
  - `test_new_source_added_later_is_seeded`: 소스 A가 이미 seeded인 상태에서 소스 B의 첫 사이클을 돌리면 `[]`여야 한다. (Review Focus 2)
  - `test_instant_sent_once`: seeded 뒤 "쌀먹" 새 글이 오면 메시지가 1개다. 같은 글이 반응이 늘어난 채로 다시 와도 `[]`이고, 묶음 큐도 비어 있어야 한다. (Review Focus 1)
  - `test_wait_then_queue_when_reactions_grow`: 첫 사이클은 wait이다. 추천이 기준을 넘으면 `pop_digest`에 1건이 나온다.
  - `test_paused_drops_instant_keeps_queue`
  - `test_youtube_source_uses_judge_youtube`
  - `test_fetcher_host_gap`: 같은 호스트에 2번 요청하면 두 번째 요청이 `host_gap_sec` 뒤에 나가는지 확인한다. 가짜 시계나 `httpx.MockTransport`를 쓴다.
  - `test_fetcher_403_raises_blocked`
- [ ] **Step 2:** FAIL을 확인한다.
- [ ] **Step 3:** 구현한다.
- [ ] **Step 4:** PASS를 확인한다.
- [ ] **Step 5:** 커밋한다. `feat: 수집 사이클 처리와 요청 간격`

### Task 7: 스케줄러와 실행 진입점

**Files:**
- Create: `monitor/main.py`, `monitor/schedule.py`
- Modify: `monitor/__main__.py`
- Test: `tests/test_schedule.py`

**Interfaces:**
- Consumes: Task 1~6 전부
- Produces:
  - `schedule.due_digest_slot(now_utc: datetime, times: list[time], last_slot: str | None) -> str | None`: KST 기준으로 가장 최근에 지난 슬롯을 계산한다. 예: `"2026-11-14T12:30"`. 그 슬롯이 `last_slot`과 다르고, 지난 지 **30분 이내**일 때만 슬롯을 돌려준다. 30분이 지난 묶음은 건너뛴다. 재시작이 길어져 엉뚱한 시각에 보내는 것을 막기 위해서다(빌더 판단).
  - `schedule.SourceHealth`: 연속 실패 수, 백오프 종료 시각, 알림 여부를 관리하는 순수 상태 기계.
    - `on_success() -> str | None`: 회복 알림 문구 또는 None.
    - `on_failure(err, now) -> str | None`: 3번째 연속 실패일 때 문구를 돌려준다. Blocked면 30분 백오프를 건다.
    - `ready(now) -> bool`
  - `main.run()`: 로그 설정(`logs/`, 날짜별 교체, 7일 보관), Store 초기화와 키워드 seed, asyncio 태스크 3개를 실행한다.
    1. 소스 루프: 1초마다 소스별로 `last_run + interval` 도달 여부와 `ready`를 확인하고 → fetch → parse → process → 즉시알림을 send한다.
    2. 봇 루프: `updates` → `commands.handle` → 답장. offset은 state에 저장한다.
    3. 관리 루프: 30초마다 `recheck_watching`, 묶음 슬롯 확인 → `pop_digest` → `format_digest` → send하고, 하루 1번 `cleanup`을 한다.
  - 시작할 때 소유자에게 `🟢 딜 모니터 시작 (소스 9개)`를 보낸다. 종료(Ctrl+C, SIGTERM) 때는 DB를 닫는다.
  - `ParseError`도 실패로 센다.

- [ ] **Step 1: 실패하는 테스트 작성**
  - `test_digest_slot_kst_on_utc_clock`: `now_utc=2026-11-14 03:31Z`(KST 12:31)이면 `"2026-11-14T12:30"`이다. (Review Focus 3)
  - `test_digest_slot_not_repeated`: `last_slot`이 같으면 None이다.
  - `test_digest_slot_skips_stale`: KST 13:10이면 None이다.
  - `test_digest_slot_crosses_midnight`: KST 00:10에 마지막 슬롯은 전날 19:00이고, 30분이 지났으므로 None이다.
  - `test_health_alert_on_third_failure_only_once`
  - `test_health_blocked_backoff_30min`
  - `test_health_recovery_message`
- [ ] **Step 2:** FAIL을 확인한다.
- [ ] **Step 3:** 구현한다.
- [ ] **Step 4:** `pytest -v`로 전체 PASS를 확인한다.
- [ ] **Step 5:** 커밋한다. `feat: 스케줄러와 실행 진입점`

### Task 8: PC 실행 확인, README, 배포 파일

**Files:**
- Create: `README.md`, `deploy/deal-monitor.service`, `deploy/oracle-setup.md`
- Modify: 실행 중 발견한 문제의 해당 파일

- [ ] **Step 1:** `README.md`를 쓴다.
  - BotFather로 봇을 만들고 토큰을 받는 방법
  - chat_id를 알아내는 방법: 봇에게 아무 말이나 보낸 뒤 `https://api.telegram.org/bot<토큰>/getUpdates`를 연다.
  - `.env` 작성
  - `python -m venv .venv`와 `pip install -r requirements.txt`
  - `python -m monitor` 실행
  - 기준 숫자를 조정하는 곳(`settings.yaml`)
- [ ] **Step 2:** `deploy/deal-monitor.service`를 쓴다. systemd 서비스이고, `Restart=always`, `WorkingDirectory=/home/ubuntu/deal-monitor`, `ExecStart=/home/ubuntu/deal-monitor/.venv/bin/python -m monitor`으로 둔다. `oracle-setup.md`에는 다음 순서를 적는다.
  1. VM 생성 (Ubuntu, Always Free)
  2. `scp`로 코드, `.env`, DB 복사
  3. venv 만들기
  4. 서비스 등록
  5. PC 프로세스 종료
- [ ] **Step 3:** 사용자 PC에서 실제로 실행한다. 사용자가 봇 토큰과 chat_id를 `.env`에 넣어야 한다. 넣지 않았으면 이 단계 앞에서 멈추고 요청한다. 확인할 것은 다음과 같다.
  - 시작 메시지가 온다.
  - `/상태`에 모든 소스가 "성공"으로 나온다.
  - `/추가 테스트`, `/목록`, `/삭제 테스트`가 동작한다.
  - 30분 이상 돌려서 오류 로그가 없다.
  - 첫 사이클에 알림이 쏟아지지 않는다.
- [ ] **Step 4:** 커밋한다. `docs: 실행 안내와 Oracle 배포 파일`
