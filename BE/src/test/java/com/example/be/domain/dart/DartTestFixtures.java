package com.example.be.domain.dart;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class DartTestFixtures {
    static final String CORP = "00126380";
    static final DartModels.Company COMPANY = new DartModels.Company(CORP, "삼성전자", "005930");
    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode financial(DartFinancialNormalizer.Period period, String receipt,
                              String revenue, String revenueYtd, String profit, String profitYtd) {
        return JSON.readTree("""
                {"status":"000","message":"정상","list":[
                  {"corp_code":"%s","bsns_year":"%s","reprt_code":"%s","rcept_no":"%s",
                   "sj_div":"IS","account_id":"ifrs-full_Revenue","account_nm":"매출액","currency":"KRW",
                   "thstrm_amount":"%s","thstrm_add_amount":"%s"},
                  {"corp_code":"%s","bsns_year":"%s","reprt_code":"%s","rcept_no":"%s",
                   "sj_div":"IS","account_id":"dart_OperatingIncomeLoss","account_nm":"영업이익(손실)","currency":"KRW",
                   "thstrm_amount":"%s","thstrm_add_amount":"%s"}
                ]}
                """.formatted(CORP, period.year(), period.reportCode(), receipt, revenue, revenueYtd,
                CORP, period.year(), period.reportCode(), receipt, profit, profitYtd));
    }

    static DartModels.Filing filing(String receipt, String name) {
        return new DartModels.Filing(receipt, name, "2026-03-10",
                "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + receipt, name.contains("정정"));
    }

    static JsonNode filingList(List<DartModels.Filing> filings) {
        var root = JSON.createObjectNode().put("status", "000").put("total_page", 1);
        var list = root.putArray("list");
        filings.forEach(f -> list.addObject().put("corp_code", CORP).put("rcept_no", f.rceptNo())
                .put("report_nm", f.reportName()).put("rcept_dt", f.filedAt().replace("-", "")).put("rm", ""));
        return root;
    }

    static JsonNode noData() { return JSON.readTree("{\"status\":\"013\",\"message\":\"조회된 데이타가 없습니다.\"}"); }

    static byte[] zip(String name, String text) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(text.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
