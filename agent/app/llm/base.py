from dataclasses import dataclass
from decimal import Decimal
from typing import Any, Literal, Protocol

ProviderName = Literal["gemini", "mindlogic-claude"]


@dataclass(frozen=True, slots=True)
class ProviderUsage:
    input_tokens: int = 0
    output_tokens: int = 0
    cost_usd: Decimal = Decimal("0")
    credits: Decimal = Decimal("0")

    def __add__(self, other: "ProviderUsage") -> "ProviderUsage":
        return ProviderUsage(
            input_tokens=self.input_tokens + other.input_tokens,
            output_tokens=self.output_tokens + other.output_tokens,
            cost_usd=self.cost_usd + other.cost_usd,
            credits=self.credits + other.credits,
        )


@dataclass(frozen=True, slots=True)
class ProviderResponse:
    text: str
    provider: ProviderName
    model: str
    usage: ProviderUsage


class AnalyzeProvider(Protocol):
    def generate(
        self,
        *,
        system_instruction: str,
        prompt: str,
        response_schema: dict[str, Any],
    ) -> ProviderResponse: ...
