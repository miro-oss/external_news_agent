package com.example.be.domain.collection.content;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 저장 원문은 보존하면서 계산 입력에서 뒤쪽 매체·저작권 푸터만 제거한다. */
public final class ArticleBodyCleaner {

    private static final int MAX_TRAILING_TEXT_AFTER_MARKER = 600;
    private static final int MAX_GAP_BETWEEN_FOOTER_MARKERS = 240;
    private static final int TRAILING_SECTION_DIVISOR = 3;
    private static final Pattern FOOTER_MARKER = Pattern.compile(
            "대표이사\\s*:|사업자등록번호\\s*:|통신판매업신고|고충처리인\\s*:"
                    + "|저작권자|무단\\s*전재|재배포\\s*금지|copyright\\s*(?:©|c|\\(c\\))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ArticleBodyCleaner() {
    }

    /** NFKC 정규화한 본문에서 실제로 푸터로 확인되는 뒤쪽 마커 묶음만 잘라낸다. */
    public static String withoutTrailingBoilerplate(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(body, Normalizer.Form.NFKC);
        Matcher matcher = FOOTER_MARKER.matcher(normalized);
        List<Marker> markers = new ArrayList<>();
        while (matcher.find()) {
            markers.add(new Marker(matcher.start(), matcher.end()));
        }
        int footerStart = footerStart(normalized, markers);
        return normalized.substring(0, footerStart).strip();
    }

    private static int footerStart(String body, List<Marker> markers) {
        int bodyLength = body.length();
        if (markers.isEmpty()) {
            return bodyLength;
        }
        Marker last = markers.getLast();
        if (bodyLength - last.end() > MAX_TRAILING_TEXT_AFTER_MARKER) {
            return bodyLength;
        }

        int firstInChain = markers.size() - 1;
        while (firstInChain > 0) {
            Marker current = markers.get(firstInChain);
            Marker previous = markers.get(firstInChain - 1);
            if (current.start() - previous.end() > MAX_GAP_BETWEEN_FOOTER_MARKERS) {
                break;
            }
            firstInChain--;
        }

        Marker first = markers.get(firstInChain);
        int trailingSectionStart = bodyLength * (TRAILING_SECTION_DIVISOR - 1)
                / TRAILING_SECTION_DIVISOR;
        boolean denseFooter = markers.size() - firstInChain >= 2;
        boolean startsWithBoilerplate = body.substring(0, first.start()).isBlank();
        boolean startsTrailingLine = first.start() >= trailingSectionStart
                && startsLine(body, first.start());
        return denseFooter || startsWithBoilerplate || startsTrailingLine
                ? first.start()
                : bodyLength;
    }

    private static boolean startsLine(String body, int markerStart) {
        for (int index = markerStart - 1; index >= 0; index--) {
            char value = body.charAt(index);
            if (value == '\n' || value == '\r') {
                return true;
            }
            if (!Character.isWhitespace(value)) {
                return false;
            }
        }
        return true;
    }

    private record Marker(int start, int end) {
    }
}
