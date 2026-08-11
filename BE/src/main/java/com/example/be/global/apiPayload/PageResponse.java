package com.example.be.global.apiPayload;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonPropertyOrder({"content", "page", "size", "totalElements", "totalPages", "hasNext"})
@Schema(name = "PageResponse", description = "공통 목록 응답 규격")
public class PageResponse<T> {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 100;

    @Schema(description = "조회된 목록")
    private final List<T> content;

    @Schema(description = "페이지 번호 (0부터 시작)", example = "0")
    private final int page;

    @Schema(description = "페이지 크기", example = "20")
    private final int size;

    @Schema(description = "전체 건수", example = "1")
    private final long totalElements;

    @Schema(description = "전체 페이지 수", example = "1")
    private final int totalPages;

    @Schema(description = "다음 페이지 존재 여부", example = "false")
    private final boolean hasNext;

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(content, page, size, totalElements, totalPages, page + 1 < totalPages);
    }
}
