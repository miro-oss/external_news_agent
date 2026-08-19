from app.core.config import Settings
from app.core.errors import AgentError
from app.llm.base import AnalyzeProvider
from app.llm.gemini_provider import GeminiAnalyzeProvider
from app.llm.mindlogic_provider import MindlogicAnalyzeProvider
from app.schemas.analyze import Plan


def create_analyze_provider(settings: Settings, plan: Plan) -> AnalyzeProvider:
    if plan == "FREE":
        _require(settings.gemini_api_key, settings.gemini_model, provider="Gemini")
        return GeminiAnalyzeProvider(settings)

    _require(
        settings.mindlogic_api_key,
        settings.mindlogic_base_url,
        settings.mindlogic_claude_model,
        provider="Mindlogic",
    )
    return MindlogicAnalyzeProvider(settings)


def _require(*values: str, provider: str) -> None:
    if not all(value.strip() for value in values):
        raise AgentError(
            status_code=503,
            code="API_KEY_MISSING",
            message=f"{provider} provider 설정이 없습니다.",
        )
