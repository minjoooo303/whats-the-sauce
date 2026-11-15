"""What's the Sauce? — CREW backend.

Endpoints
  GET  /health          상태 확인 (LLM 사용 가능 여부 포함)
  GET  /stories         사연 목록 (앱과 같은 데이터)
  POST /crew/chat       사연 청취 중 질문 → CREW 답변
  POST /crew/order      "달달한 썰 줘" 같은 주문 발화 → 소스 / 맛 조합

ANTHROPIC_API_KEY 가 설정돼 있으면 Claude 로 답하고, 없거나 호출이 실패하면
규칙 기반 응답으로 대체한다. 앱은 이 서버 없이도 동작하도록 같은 대체 로직을 갖고 있다.
"""

from __future__ import annotations

import json
import logging
import os
import re
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

log = logging.getLogger("crew")
logging.basicConfig(level=logging.INFO)

DATA_PATH = Path(__file__).parent / "data" / "stories.json"
STORIES: dict[str, dict] = {
    s["id"]: s for s in json.loads(DATA_PATH.read_text(encoding="utf-8"))["stories"]
}

SAUCES = {
    "RANCH": {"name": "Ranch", "desc": "담백하고 은은하게 고소한 맛", "sweet": 20, "spicy": 5, "savory": 65, "sour": 10, "bitter": 10},
    "SWEET_CHILI": {"name": "Sweet Chili", "desc": "달콤하다가 끝맛이 매콤한 맛", "sweet": 75, "spicy": 35, "savory": 20, "sour": 55, "bitter": 10},
    "HOT_SAUCE": {"name": "Hot Sauce", "desc": "혀가 얼얼할 만큼 매운맛", "sweet": 10, "spicy": 90, "savory": 10, "sour": 45, "bitter": 40},
    "CHEESE_DIP": {"name": "Cheese Dip", "desc": "진하고 짭짤하게 고소한 맛", "sweet": 45, "spicy": 10, "savory": 90, "sour": 15, "bitter": 10},
    "WASABI_MAYO": {"name": "Wasabi Mayo", "desc": "코끝이 찡하게 톡 쏘는 새콤한 맛", "sweet": 20, "spicy": 45, "savory": 30, "sour": 75, "bitter": 20},
    "BLACK_PEPPER": {"name": "Black Pepper", "desc": "알싸하고 씁쓸한 맛", "sweet": 10, "spicy": 45, "savory": 15, "sour": 25, "bitter": 80},
}

MODEL = os.environ.get("CREW_MODEL", "claude-haiku-5-5")

_client = None
if os.environ.get("ANTHROPIC_API_KEY"):
    try:
        import anthropic

        _client = anthropic.Anthropic()
    except Exception as e:  # pragma: no cover - 설치 안 된 경우
        log.warning("anthropic client unavailable: %s", e)

CREW_PERSONA = (
    "너는 드라이브스루 감자튀김 가게 'What's the Sauce?'의 직원 CREW야. "
    "모자를 쓴 햄스터 캐릭터이고, 손님(운전자)에게 사연을 '메뉴'처럼 들려주는 라디오 진행자 역할을 해. "
    "말투는 밝고 친근한 존댓말. 운전 중인 손님이 귀로 듣기 때문에 답은 한두 문장, 길어도 70자 안팎으로 짧게 해. "
    "이모지, 괄호, 목록, 마크다운은 쓰지 마."
)


class Turn(BaseModel):
    role: Literal["user", "crew"]
    text: str


class ChatRequest(BaseModel):
    story_id: str
    heard_until: int = Field(ge=0, description="손님이 지금까지 들은 마지막 문장 인덱스")
    question: str
    history: list[Turn] = []


class ChatResponse(BaseModel):
    answer: str
    source: Literal["llm", "fallback"]


class Mix(BaseModel):
    sweet: int = 50
    spicy: int = 50
    savory: int = 50
    sour: int = 50
    bitter: int = 50


class OrderRequest(BaseModel):
    utterance: str
    current: Mix | None = None


class OrderResponse(BaseModel):
    sauce: str | None
    mix: Mix
    reply: str
    source: Literal["llm", "fallback"]


app = FastAPI(title="What's the Sauce? CREW")


@app.get("/health")
def health():
    return {"ok": True, "llm": _client is not None, "model": MODEL if _client else None}


@app.get("/stories")
def stories():
    return {"stories": list(STORIES.values())}


# ---------------------------------------------------------------- chat

def _bigrams(text: str) -> set[str]:
    t = re.sub(r"[^0-9A-Za-z가-힣]", "", text)
    return {t[i : i + 2] for i in range(len(t) - 1)}


def fallback_answer(story: dict, heard_until: int, question: str) -> str:
    heard = story["sentences"][: heard_until + 1]
    q = _bigrams(question)
    best, score = None, 0
    for s in heard:
        sc = len(q & _bigrams(s))
        if sc > score:
            best, score = s, sc
    if best and score >= 2:
        return f"사연에 따르면요, {best}"
    return "그 부분은 사연에 아직 안 나왔어요. 조금만 더 들어보시면 나올지도 몰라요!"


def llm_answer(story: dict, heard_until: int, question: str, history: list[Turn]) -> str:
    heard = " ".join(story["sentences"][: heard_until + 1])
    rest = " ".join(story["sentences"][heard_until + 1 :])
    system = (
        CREW_PERSONA
        + "\n\n지금 손님은 사연을 듣다가 잠깐 멈추고 질문했어. 아래 사연 내용만 근거로 답해. "
        "사연에 없는 사실은 지어내지 말고, 모르면 사연에는 안 나온다고 솔직하게 말해. "
        "손님이 아직 듣지 않은 뒷부분은 손님이 결말을 직접 물을 때만 살짝 힌트를 줘. "
        "답한 뒤에는 '그럼 이어서 들려드릴게요' 같은 말은 붙이지 마. 앱이 알아서 이어 재생해."
        f"\n\n[사연 제목] {story['title']}"
        f"\n[손님이 지금까지 들은 부분] {heard}"
        f"\n[아직 안 들은 뒷부분] {rest or '(없음, 사연 끝)'}"
    )
    messages = []
    for t in history[-6:]:
        messages.append({"role": "user" if t.role == "user" else "assistant", "content": t.text})
    messages.append({"role": "user", "content": question})
    resp = _client.messages.create(model=MODEL, max_tokens=200, system=system, messages=messages)
    return "".join(b.text for b in resp.content if getattr(b, "type", "") == "text").strip()


@app.post("/crew/chat", response_model=ChatResponse)
def crew_chat(req: ChatRequest):
    story = STORIES.get(req.story_id)
    if not story:
        raise HTTPException(404, "unknown story")
    heard_until = min(req.heard_until, len(story["sentences"]) - 1)
    if _client:
        try:
            ans = llm_answer(story, heard_until, req.question, req.history)
            if ans:
                return ChatResponse(answer=ans, source="llm")
        except Exception as e:
            log.warning("llm chat failed: %s", e)
    return ChatResponse(answer=fallback_answer(story, heard_until, req.question), source="fallback")


# ---------------------------------------------------------------- order

TASTES = ("sweet", "spicy", "savory", "sour", "bitter")
TASTE_LABEL = {"sweet": "단맛", "spicy": "매운맛", "savory": "고소함", "sour": "새콤함", "bitter": "씁쓸함"}

# 실제 맛 표현 + 분위기 표현
KEYWORDS: list[tuple[str, str]] = [
    (r"랜치|담백|잔잔|소소|일상|순한", "RANCH"),
    (r"스위트|칠리|달달|달콤|단맛|달게|설레|설렘|연애", "SWEET_CHILI"),
    (r"핫소스|핫 소스|매운|맵|얼얼|화끈|자극|갈등", "HOT_SAUCE"),
    (r"치즈|고소|짭짤|짠|진한|따뜻|힐링|훈훈", "CHEESE_DIP"),
    (r"와사비|마요|새콤|톡쏘|시큼|황당|웃긴|웃기|반전", "WASABI_MAYO"),
    (r"후추|페퍼|씁쓸|쌉싸|알싸|미스터리|무서|소름|긴장", "BLACK_PEPPER"),
]

TASTE_WORDS: list[tuple[str, str]] = [
    (r"단맛|달게|달달|달콤|달", "sweet"),
    (r"매운|맵게|맵|매콤", "spicy"),
    (r"고소|짭짤|짜게", "savory"),
    (r"새콤|시게|톡", "sour"),
    (r"씁쓸|쓰게|쌉싸", "bitter"),
]


def clamp(v: int) -> int:
    return max(0, min(100, v))


def fallback_order(utterance: str, current: Mix | None) -> OrderResponse:
    u = utterance.replace(" ", "")
    base = current or Mix()
    more = bool(re.search(r"(더|좀더|조금더)", u))
    less = "덜" in u
    if more or less:
        hit = next((k for pat, k in TASTE_WORDS if re.search(pat, u)), None)
        if hit:
            vals = base.model_dump()
            vals[hit] = clamp(vals[hit] + (-20 if less else 20))
            verb = "줄여서" if less else "더해서"
            return OrderResponse(sauce=None, mix=Mix(**vals), reply=f"네, {TASTE_LABEL[hit]}을 {verb} 다시 담아볼게요!", source="fallback")

    hits = list(dict.fromkeys(sauce for pat, sauce in KEYWORDS if re.search(pat, u)))
    if not hits:
        return OrderResponse(
            sauce=None, mix=base, reply="어떤 맛이 좋을지 한 번만 더 말씀해주세요. 달콤한 맛, 매운맛, 고소한 맛이 있어요!",
            source="fallback",
        )
    mix = Mix(**{k: clamp(sum(SAUCES[h][k] for h in hits) // len(hits)) for k in TASTES})
    names = " + ".join(SAUCES[h]["name"] for h in hits[:2])
    sauce = hits[0] if len(hits) == 1 else None
    return OrderResponse(sauce=sauce, mix=mix, reply=f"그럼 {names} 어떠세요?", source="fallback")


def llm_order(utterance: str, current: Mix | None) -> OrderResponse:
    sauce_desc = "\n".join(
        f"- {k} ({v['name']}, {v['desc']}): " + ", ".join(f"{TASTE_LABEL[t]} {v[t]}" for t in TASTES)
        for k, v in SAUCES.items()
    )
    system = (
        CREW_PERSONA
        + "\n\n손님이 듣고 싶은 사연의 맛을 말로 주문했어. 아래 소스 중 가장 어울리는 걸 고르고, "
        "맛 수치(0~100)를 정해. '더 맵게'처럼 현재 맛을 조절하는 주문이면 sauce는 null로 두고 현재 수치를 조정해. "
        "reply는 손님에게 할 한 문장 답(소스 이름을 영어 그대로 넣어). "
        '반드시 JSON 한 줄만 출력: {"sauce": "SWEET_CHILI" 또는 null, "sweet": 0, "spicy": 0, "savory": 0, "sour": 0, "bitter": 0, "reply": "..."}'
        f"\n\n[소스]\n{sauce_desc}"
        f"\n[현재 맛] {(current or Mix()).model_dump()}"
    )
    resp = _client.messages.create(
        model=MODEL, max_tokens=200, system=system, messages=[{"role": "user", "content": utterance}]
    )
    raw = "".join(b.text for b in resp.content if getattr(b, "type", "") == "text")
    m = re.search(r"\{.*\}", raw, re.S)
    data = json.loads(m.group(0))
    sauce = data.get("sauce")
    if sauce not in SAUCES:
        sauce = None
    return OrderResponse(
        sauce=sauce,
        mix=Mix(**{k: clamp(int(data.get(k, 50))) for k in TASTES}),
        reply=str(data.get("reply", "")).strip() or "네, 준비해드릴게요!",
        source="llm",
    )


@app.post("/crew/order", response_model=OrderResponse)
def crew_order(req: OrderRequest):
    if _client:
        try:
            return llm_order(req.utterance, req.current)
        except Exception as e:
            log.warning("llm order failed: %s", e)
    return fallback_order(req.utterance, req.current)


# ---------------------------------------------------------------- Sauce Factory (썰 제보)

FACTORY_PATH = Path(__file__).parent / "data" / "submissions.jsonl"


class SubmitRequest(BaseModel):
    text: str = Field(min_length=10, max_length=4000)
    sauce: str | None = None
    nickname: str | None = Field(default=None, max_length=30)


class SubmitResponse(BaseModel):
    id: str
    status: Literal["received"]


@app.post("/factory/submit", response_model=SubmitResponse)
def factory_submit(req: SubmitRequest):
    """사용자 제보를 접수만 한다. 운영자가 검수한 뒤 AI 편집(문장 분리, 개인정보 제거, 소스 태깅)을 거쳐 메뉴에 올린다."""
    import time
    import uuid

    sid = uuid.uuid4().hex[:8]
    rec = {
        "id": sid,
        "at": int(time.time()),
        "text": req.text.strip(),
        "sauce": req.sauce if req.sauce in SAUCES else None,
        "nickname": (req.nickname or "").strip() or None,
        "status": "received",
    }
    FACTORY_PATH.parent.mkdir(parents=True, exist_ok=True)
    with FACTORY_PATH.open("a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    return SubmitResponse(id=sid, status="received")
