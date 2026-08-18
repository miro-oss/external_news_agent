package com.example.be.domain.analysis.entity;

import java.util.List;
import java.util.Set;

/** finding 분류의 API 값과 표시 순서를 한 곳에서 관리한다. */
public final class FindingCategory {

    public static final String PRODUCT_PROCESS = "제품/공정";
    public static final String COMPANY = "기업";
    public static final String POLICY = "정책";
    public static final String SUPPLY_CHAIN = "공급망";

    public static final List<String> ORDERED_VALUES = List.of(
            PRODUCT_PROCESS, COMPANY, POLICY, SUPPLY_CHAIN);
    public static final Set<String> ALLOWED_VALUES = Set.copyOf(ORDERED_VALUES);

    private FindingCategory() {
    }
}
