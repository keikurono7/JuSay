import json
from typing import Any

from fastapi import FastAPI, Header, HTTPException
from groq import Groq
from pydantic import BaseModel, Field

app = FastAPI(title="Zero-Touch Voice OS Controller Backend")

SYSTEM_PROMPT = (
    "You are an intent-to-action planner for mobile UI automation. "
    "You will receive: (1) a JSON screen UI tree and (2) a spoken user intent. "
    "Analyze both and return ONLY a JSON object with this exact shape: "
    '{"action": "click|type|scroll|wait", "target_id": "view_id_or_text", '
    '"input_text": "text_to_type_if_any"}. '
    "Do not include markdown, explanations, code fences, or extra keys."
)


class IntentRequest(BaseModel):
    ui_tree: Any = Field(..., description="JSON screen UI tree")
    spoken_intent: str = Field(..., description="User spoken intent")


@app.post("/process_intent")
async def process_intent(
    payload: IntentRequest,
    x_groq_api_key: str | None = Header(default=None, alias="X-Groq-Api-Key"),
) -> dict[str, Any]:
    if not x_groq_api_key:
        raise HTTPException(
            status_code=400,
            detail="Missing required header: X-Groq-Api-Key",
        )

    client = Groq(api_key=x_groq_api_key)

    user_message = json.dumps(
        {
            "ui_tree": payload.ui_tree,
            "spoken_intent": payload.spoken_intent,
        },
        ensure_ascii=True,
    )

    try:
        completion = client.chat.completions.create(
            model="llama3-70b-8192",
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_message},
            ],
            temperature=0,
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Groq request failed: {exc}") from exc

    raw_output = (completion.choices[0].message.content or "").strip()

    try:
        parsed = json.loads(raw_output)
    except json.JSONDecodeError as exc:
        raise HTTPException(
            status_code=502,
            detail={
                "error": "Groq returned non-JSON output",
                "raw_output": raw_output,
            },
        ) from exc

    return parsed
