package com.example.be.domain.notifications.service;

import com.example.be.domain.dart.model.DartModels.Filing;
import com.example.be.domain.dart.model.DartModels.FinancialData;
import com.example.be.domain.dart.model.DartModels.Quarter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** One period and unit policy for the compact email and PDF company appendix. */
final class CompanyFinancialBrief {
    private CompanyFinancialBrief() { }

    static List<Quarter> quarters(FinancialData data) {
        var ordered = data.quarters().stream().filter(quarter -> quarter != null && quarter.year() > 0
                        && quarter.quarter() >= 1 && quarter.quarter() <= 4)
                .sorted(Comparator.comparingInt(Quarter::year).thenComparingInt(Quarter::quarter)).toList();
        return ordered.subList(Math.max(0, ordered.size() - 4), ordered.size());
    }

    static Filing latestFiling(FinancialData data) {
        return data.disclosures().stream().filter(filing -> filing != null)
                .max(Comparator.comparing(Filing::filedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    static String basis(String value) {
        return "CFS".equals(value) ? "연결재무제표" : "OFS".equals(value) ? "별도재무제표" : "재무 기준 미확인";
    }

    static String unit(String currency) {
        return "KRW".equals(currency) ? "억원 (KRW)"
                : currency != null && currency.matches("[A-Z]{3}") ? currency : "통화 미확인";
    }

    static BigDecimal value(String value) {
        return value == null || value.length() > 128 || !value.matches("-?[0-9]+(?:\\.[0-9]+)?")
                ? null : new BigDecimal(value);
    }

    static String number(String value, String currency) {
        BigDecimal amount = value(value);
        if (amount == null) return "자료 없음";
        if ("KRW".equals(currency)) {
            BigDecimal exact = amount.movePointLeft(8);
            BigDecimal rounded = exact.setScale(2, RoundingMode.HALF_UP);
            amount = (rounded.signum() == 0 && exact.signum() != 0 ? exact : rounded).stripTrailingZeros();
        }
        var format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.KOREA));
        format.setMinimumFractionDigits(Math.max(0, amount.scale()));
        format.setMaximumFractionDigits(Math.max(0, amount.scale()));
        return format.format(amount);
    }

    static String amount(String value, String currency) {
        String number = number(value, currency);
        return "자료 없음".equals(number) ? number : number
                + ("KRW".equals(currency) ? "억원" : " " + unit(currency));
    }
}
