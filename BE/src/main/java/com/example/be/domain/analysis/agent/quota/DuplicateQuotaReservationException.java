package com.example.be.domain.analysis.agent.quota;

public class DuplicateQuotaReservationException extends IllegalStateException {

    public DuplicateQuotaReservationException(String idempotencyKey, String status) {
        super("이미 사용된 quota idempotencyKey는 재사용할 수 없습니다. key="
                + idempotencyKey + " status=" + status);
    }
}
