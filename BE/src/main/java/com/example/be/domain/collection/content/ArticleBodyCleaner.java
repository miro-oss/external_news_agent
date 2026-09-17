package com.example.be.domain.collection.content;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 기사 앞의 뉴스 메뉴와 계산 입력의 뒤쪽 매체·저작권 푸터를 식별한다. */
public final class ArticleBodyCleaner {

    private static final Pattern NON_EMPTY_LINE = Pattern.compile("[^\\r\\n]+");
    private static final Pattern NAVIGATION_LABEL = Pattern.compile(
            "\\G[\\h|·]*(최신\\h*뉴스|생활\\h*[·/]\\h*문화|생활문화|IT/과학"
                    + "|정치|경제|사회|생활|문화|스포츠|국제|날씨|산업|전국|세계)(?=$|[\\h|·])[\\h|·]*");
    private static final int MAX_TRAILING_TEXT_AFTER_MARKER = 600;
    private static final int MAX_GAP_BETWEEN_FOOTER_MARKERS = 240;
    private static final int TRAILING_SECTION_DIVISOR = 3;
    private static final Pattern FOOTER_MARKER = Pattern.compile(
            "대표이사\\s*:|사업자등록번호\\s*:|통신판매업신고|고충처리인\\s*:"
                    + "|저작권자|무단\\s*전재|재배포\\s*금지|copyright\\s*(?:©|c|\\(c\\))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ArticleBodyCleaner() {
    }

    /**
     * 맨 앞에 연속된 서로 다른 메뉴가 세 개 이상일 때만 제거한다.
     * 본문 문장에 등장하는 분야 이름이나 단독 소제목은 그대로 두며,
     * 메뉴와 첫 문장이 한 줄에 합쳐졌으면 '최신뉴스' 시작과 명시적인 | 구분자도 요구한다.
     */
    public static String withoutLeadingNavigation(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        Set<String> labels = new HashSet<>();
        Matcher lines = NON_EMPTY_LINE.matcher(body);
        int menuEnd = 0;
        while (lines.find()) {
            String line = lines.group();
            if (line.isBlank()) {
                continue;
            }
            Matcher menu = NAVIGATION_LABEL.matcher(line);
            List<String> lineLabels = new ArrayList<>();
            int prefixEnd = 0;
            int delimitedEnd = 0;
            int delimitedLabels = 0;
            while (menu.find()) {
                lineLabels.add(menu.group(1).replaceAll("[\\h·/]", ""));
                prefixEnd = menu.end();
                String separator = line.substring(menu.end(1), menu.end());
                if (separator.contains("|")) {
                    delimitedEnd = prefixEnd;
                    delimitedLabels = lineLabels.size();
                }
            }
            if (lineLabels.isEmpty()) {
                break;
            }
            boolean menuOnly = prefixEnd == line.length();
            if (!menuOnly) {
                // 공백이나 가운뎃점만으로는 '국제·정치 관계가...'의 시작을 구분할 수 없다.
                if (delimitedLabels == 0) {
                    break;
                }
                prefixEnd = delimitedEnd;
                lineLabels = lineLabels.subList(0, delimitedLabels);
            }
            // 이전 줄에 메뉴가 있어도 '경제 전망은 밝다.'의 첫 단어를 잘라내지 않는다.
            boolean explicitInlineMenu = lineLabels.getFirst().equals("최신뉴스")
                    && new HashSet<>(lineLabels).size() >= 3;
            if (!menuOnly && !explicitInlineMenu) {
                break;
            }
            if (menuOnly && labels.size() >= 3 && lineLabels.getFirst().equals("최신뉴스")) {
                // 반복된 전체 메뉴는 새 묶음으로 검증하되, 단독 '최신뉴스' 소제목은 보존한다.
                labels.clear();
            }
            if (lineLabels.stream().anyMatch(labels::contains)) {
                break;
            }
            labels.addAll(lineLabels);
            if (labels.size() >= 3) {
                menuEnd = lines.start() + prefixEnd;
            }
            if (!menuOnly) {
                break;
            }
        }
        return menuEnd > 0 ? body.substring(menuEnd).stripLeading() : body;
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
