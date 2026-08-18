package com.example.be.domain.analysis.entity;

/** DB의 sections JSON 한 칸. index는 key point evidence와 같은 번호 공간을 쓴다. */
public record FindingSection(int index, String text) {
}
