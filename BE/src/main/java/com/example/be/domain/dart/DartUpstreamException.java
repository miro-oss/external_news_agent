package com.example.be.domain.dart;

/** Never retain the HTTP exception, query string, provider message or response body (the key is a query). */
public final class DartUpstreamException extends RuntimeException {
    private final String status;

    public DartUpstreamException(String status) {
        super(safeMessage(status));
        this.status = status;
    }

    public String getStatus() { return status; }

    private static String safeMessage(String status) {
        return switch (status) {
            case "DISABLED" -> "DART 연동 설정이 준비되지 않았습니다.";
            case "010", "011", "012", "901" -> "DART 인증 설정을 확인해 주세요.";
            case "020" -> "DART 요청 한도에 도달했습니다. 잠시 후 다시 시도해 주세요.";
            case "013" -> "DART에 제공된 데이터가 없습니다.";
            case "BAD_RESPONSE" -> "DART 응답을 확인할 수 없습니다.";
            default -> "DART 정보를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.";
        };
    }
}
