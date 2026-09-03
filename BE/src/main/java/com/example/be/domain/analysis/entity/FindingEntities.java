package com.example.be.domain.analysis.entity;

import java.util.List;
import java.util.stream.Stream;

/** Agent가 기사 원문에서 식별한 엔티티 묶음. */
public record FindingEntities(
        List<String> companies,
        List<String> products,
        List<String> technologies
) {

    public FindingEntities {
        companies = companies == null ? List.of() : List.copyOf(companies);
        products = products == null ? List.of() : List.copyOf(products);
        technologies = technologies == null ? List.of() : List.copyOf(technologies);
    }

    public static FindingEntities empty() {
        return new FindingEntities(List.of(), List.of(), List.of());
    }

    public List<String> allNames() {
        return Stream.of(companies, products, technologies)
                .flatMap(List::stream)
                .toList();
    }
}
