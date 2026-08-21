from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import Settings, get_settings
from app.llm.evidence_service import EvidenceVerifierService
from app.schemas.evidence import EvidenceVerifyRequest, EvidenceVerifyResponse

router = APIRouter(tags=["evidence"])


@router.post("/verify-evidence", response_model=EvidenceVerifyResponse)
def verify_evidence(
    request: EvidenceVerifyRequest,
    settings: Annotated[Settings, Depends(get_settings)],
) -> EvidenceVerifyResponse:
    return EvidenceVerifierService(settings).verify(request)
