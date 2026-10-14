package com.example.be.domain.dart;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Values are kept as decimal strings so clients do not lose integer precision. */
public final class DartModels {
    private DartModels() { }

    @Schema(description = "DART 고유번호로 명시적으로 선택한 공시 회사")
    public record Company(
            @Schema(description = "8자리 DART 고유번호", example = "00126380", pattern = "[0-9]{8}") String corpCode,
            @Schema(description = "법인명", example = "삼성전자") String corpName,
            @Schema(description = "숫자와 영문 대문자로 구성된 6자리 종목코드. 비상장은 빈 문자열", example = "005930", pattern = "[0-9A-Z]{6}|") String stockCode) { }

    @Schema(description = "실제 확인한 DART 공시 출처")
    public record Filing(
            @Schema(description = "14자리 접수번호", pattern = "[0-9]{14}") String rceptNo,
            @Schema(description = "공시 보고서명") String reportName,
            @Schema(description = "접수일 (YYYY-MM-DD)", example = "2026-08-14") String filedAt,
            @Schema(description = "DART 공시 원문 뷰어 URL") String url,
            @Schema(description = "정정공시이거나 정정 이력이 표시된 공시") boolean corrected) { }

    @Schema(description = "분기 단독 실적. 중간 누락을 0 또는 다른 분기로 대체하지 않음")
    public record Quarter(
            @Schema(description = "사업연도", example = "2025") int year,
            @Schema(description = "분기", minimum = "1", maximum = "4", example = "2") int quarter,
            @Schema(description = "원 통화 기준 매출 십진 문자열. 자료 미확인 시 null", nullable = true, example = "10000000000") String revenue,
            @Schema(description = "원 통화 기준 영업이익 십진 문자열. 손실은 음수, 자료 미확인 시 null", nullable = true, example = "-1000000000") String operatingProfit,
            @Schema(description = "영업이익률(%), 소수 둘째 자리. 매출 0 또는 금액 누락 시 null", nullable = true, example = "-10.00") BigDecimal operatingMargin,
            @Schema(description = "4분기 금액을 같은 기준의 연간 금액에서 Q3 누적을 차감해 계산했는지 여부") boolean derived,
            @Schema(description = "금액 출처. 4분기 계산값은 연간·Q3의 두 공시를 포함") List<Filing> sources) { }

    @Schema(description = "보고서별 최초 확보 시 고정하는 회사 실적과 공시. 과거 생성 시점의 공시상태를 소급 보장하지 않음")
    public record FinancialData(
            @Schema(description = "전체 시계열의 연결(CFS)/별도(OFS) 기준. 자료 미확인 시 null", allowableValues = {"CFS", "OFS"}, nullable = true) String fsDiv,
            @Schema(description = "금액의 원 통화. 혼합 또는 미확인 시 null", example = "KRW", nullable = true) String currency,
            @Schema(description = "자료를 실제 확보한 시각, ISO-8601 오프셋 포함", example = "2026-10-14T09:00:00+09:00") OffsetDateTime fetchedAt,
            @Schema(description = "최신 확인 가능한 정기보고서 분기까지 연속 12분기, 오래된 순. 회계기간 미지원 시 빈 배열") List<Quarter> quarters,
            @Schema(description = "회사 전체 최근 공시 최대 5건. 뉴스 키워드로 필터하지 않음") List<Filing> disclosures,
            @Schema(description = "누락, 미지원, 비교 기준 불일치 안내") List<String> notes) { }
}
