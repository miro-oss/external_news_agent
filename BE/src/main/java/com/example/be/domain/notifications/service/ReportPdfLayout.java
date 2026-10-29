package com.example.be.domain.notifications.service;

import com.example.be.domain.dart.model.DartModels.Quarter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small, fully local A4 layout engine. Every prose line and table row may continue on a new page. */
final class ReportPdfLayout implements AutoCloseable {
    private static final float MARGIN = 44;
    private static final float WIDTH = PDRectangle.A4.getWidth() - MARGIN * 2;
    private static final float TOP = PDRectangle.A4.getHeight() - 68;
    private static final float BOTTOM = 57;
    private static final Color INK = new Color(25, 31, 40);
    private static final Color MUTED = new Color(99, 108, 120);
    private static final Color BLUE = new Color(27, 100, 218);
    private static final Color GREEN = new Color(22, 132, 109);
    private static final Color RULE = new Color(221, 227, 235);
    private final PDDocument document;
    private final PDType0Font regular;
    private final PDType0Font bold;
    private final Map<Integer, String> glyphs = new HashMap<>();
    private final Map<String, PDPageDestination> destinations = new java.util.TreeMap<>();
    private final Map<String, Integer> destinationPages = new HashMap<>();
    private final List<InternalLink> internalLinks = new ArrayList<>();
    private PDPage page;
    private PDPageContentStream stream;
    private float y;

    ReportPdfLayout(PDDocument document) throws IOException {
        this.document = document;
        regular = loadFont("/fonts/NanumGothic-Regular.ttf");
        bold = loadFont("/fonts/NanumGothic-Bold.ttf");
        newPage();
    }

    private PDType0Font loadFont(String resource) throws IOException {
        try (InputStream input = ReportPdfLayout.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("보고서 PDF 글꼴을 불러올 수 없습니다.");
            return PDType0Font.load(document, input, true);
        }
    }

    void title(String value) throws IOException {
        textBlock(value, 22, 31, bold, INK, 0);
        gap(9);
    }

    void section(String value) throws IOException {
        ensure(66);
        gap(17);
        line(MARGIN, y, MARGIN + WIDTH, y, BLUE, 1.3f);
        gap(17);
        textBlock(value, 15, 22, bold, BLUE, 0);
        gap(4);
    }

    void subheading(String value) throws IOException {
        ensure(48);
        gap(7);
        textBlock(value, 11.5f, 17, bold, INK, 0);
        gap(2);
    }

    void paragraph(String value) throws IOException { textBlock(value, 10, 16, regular, INK, 0); gap(5); }
    void muted(String value) throws IOException { textBlock(value, 8.5f, 13, regular, MUTED, 0); gap(4); }
    void bullet(String value) throws IOException { textBlock("- " + nullable(value), 10, 16, regular, INK, 8); gap(3); }
    void keyValue(String key, String value) throws IOException { paragraph(key + " · " + nullable(value)); }
    void gap(float amount) { y -= amount; }
    void reserve(float amount) throws IOException { ensure(amount); }

    void pageBreak() throws IOException { newPage(); }

    /** A 0–100 keyword share with explicit counts; keep ordinary rows on one page. */
    void keywordBar(String label, String amount, double share, String related) throws IOException {
        float height = readingTextHeight(label, bold, 10.5f, 17)
                + readingTextHeight(amount, regular, 8.5f, 13)
                + readingTextHeight(related, regular, 8.5f, 13) + 42;
        ensure(Math.min(height, TOP - BOTTOM));
        textBlock(label, 10.5f, 17, bold, INK, 0);
        muted(amount);
        ensure(20);
        stream.setNonStrokingColor(RULE);
        stream.addRect(MARGIN, y - 6, WIDTH, 7);
        stream.fill();
        if (share > 0) {
            stream.setNonStrokingColor(BLUE);
            stream.addRect(MARGIN, y - 6, WIDTH * (float) (Math.min(100, share) / 100), 7);
            stream.fill();
        }
        gap(18);
        if (related != null && !related.isBlank()) muted(related);
        gap(10);
    }

    void keywordComparison(String label, String amount, double previous, double current) throws IOException {
        float height = readingTextHeight(label, bold, 10.5f, 17) + readingTextHeight(amount, regular, 8.5f, 13) + 40;
        ensure(Math.min(height, TOP - BOTTOM));
        textBlock(label, 10.5f, 17, bold, INK, 0);
        muted(amount);
        ensure(28);
        for (int index = 0; index < 2; index++) {
            double share = index == 0 ? previous : current;
            stream.setNonStrokingColor(RULE);
            stream.addRect(MARGIN, y - 5, WIDTH, 5);
            stream.fill();
            if (share > 0) {
                stream.setNonStrokingColor(index == 0 ? new Color(148, 163, 184) : BLUE);
                stream.addRect(MARGIN, y - 5, WIDTH * (float) (Math.min(100, share) / 100), 5);
                stream.fill();
            }
            gap(9);
        }
        gap(12);
    }

    void anchor(String name) {
        if (destinations.containsKey(name)) throw new IllegalArgumentException("중복된 PDF 이동 대상입니다.");
        PDPageXYZDestination destination = new PDPageXYZDestination();
        destination.setPage(page);
        destination.setTop(Math.round(y + 10));
        destination.setLeft(Math.round(MARGIN));
        destination.setZoom(0);
        destinations.put(name, destination);
        destinationPages.put(name, document.getNumberOfPages());
    }

    Integer destinationPage(String name) { return destinationPages.get(name); }

    /** Forward links reserve their page label, which is resolved after all appendix pages exist. */
    void internalLink(String label, String destination) throws IOException {
        List<String> lines = wrap(label, regular, 9, WIDTH - 68);
        for (int index = 0; index < lines.size(); index++) {
            ensure(16);
            String line = lines.get(index);
            draw(line, MARGIN, y, regular, 9, BLUE);
            internalLinks.add(new InternalLink(page, y, destination, index == lines.size() - 1));
            y -= 16;
        }
        gap(4);
    }

    private record InternalLink(PDPage page, float baseline, String destination, boolean showPage) { }

    record ReadingField(String label, String text) { }
    record ReadingItem(String label, String title, List<ReadingField> fields) { }
    record ReadingGroup(String heading, List<ReadingItem> items) { }

    /**
     * Keeps the section heading, notice and first ordinary item on one page when they fit.
     * Groups must contain at least one item; oversized items continue through the line paginator.
     */
    void readingSection(String heading, String notice, List<ReadingGroup> groups) throws IOException {
        if (groups.isEmpty()) return;
        ReadingGroup first = groups.getFirst();
        float prefix = 38 + readingTextHeight(heading, bold, 15, 22)
                + readingTextHeight(notice, regular, 8.5f, 13) + 4
                + 22 + readingTextHeight(first.heading(), bold, 11.5f, 17);
        float complete = prefix + readingHeight(first.items().getFirst());
        boolean keepFirstTogether = complete <= TOP - BOTTOM;
        ensure(keepFirstTogether ? complete : Math.min(prefix + 90, TOP - BOTTOM));
        section(heading);
        muted(notice);
        for (int index = 0; index < groups.size(); index++) {
            ReadingGroup group = groups.get(index);
            readingGroup(group.heading(), group.items(), index > 0 || keepFirstTogether);
        }
    }

    /** Keep ordinary reading items together; oversized prose still flows through the normal line paginator. */
    private void readingGroup(String heading, List<ReadingItem> items, boolean keepFirstTogether) throws IOException {
        if (items.isEmpty()) return;
        gap(6);
        float headingHeight = 16 + readingTextHeight(heading, bold, 11.5f, 17);
        float opening = readingHeight(items.getFirst()) + headingHeight;
        ensure(keepFirstTogether && opening <= TOP - BOTTOM ? opening : headingHeight + 90);
        subheading(heading);
        gap(7);
        for (int index = 0; index < items.size(); index++) {
            readingItem(items.get(index), index > 0, index > 0 || (keepFirstTogether && opening <= TOP - BOTTOM));
        }
    }

    private void readingItem(ReadingItem item, boolean separated, boolean keepTogether) throws IOException {
        float height = readingHeight(item);
        PDPage previousPage = page;
        float requested = height + (separated ? 20 : 0);
        ensure(keepTogether && requested <= TOP - BOTTOM ? requested : 90);
        if (separated && previousPage == page) {
            gap(8);
            line(MARGIN, y, MARGIN + WIDTH, y, RULE, .5f);
            gap(12);
        }
        readingLabel(item.label(), 18);
        textBlock(item.title(), 10.5f, 18, bold, INK, 0);
        gap(8);
        for (ReadingField field : item.fields()) {
            if (field.text() == null || field.text().isBlank()) continue;
            readingLabel(field.label(), 17);
            textBlock(field.text(), 10, 17, regular, INK, 0);
            gap(9);
        }
        gap(3);
    }

    private void readingLabel(String label, float followingLineHeight) throws IOException {
        if (label == null || label.isBlank()) return;
        ensure(readingTextHeight(label, regular, 8, 11) + 3 + followingLineHeight);
        textBlock(label, 8, 11, regular, MUTED, 0);
        gap(3);
    }

    private float readingHeight(ReadingItem item) throws IOException {
        float height = readingTextHeight(item.title(), bold, 10.5f, 18) + 11;
        if (item.label() != null && !item.label().isBlank()) height += readingTextHeight(item.label(), regular, 8, 11) + 3;
        for (ReadingField field : item.fields()) {
            if (field.text() == null || field.text().isBlank()) continue;
            if (field.label() != null && !field.label().isBlank()) height += readingTextHeight(field.label(), regular, 8, 11) + 3;
            height += readingTextHeight(field.text(), regular, 10, 17) + 9;
        }
        return height;
    }

    private float readingTextHeight(String value, PDType0Font font, float size, float leading) throws IOException {
        return value == null || value.isBlank() ? 0 : wrap(value, font, size, WIDTH).size() * leading;
    }

    void link(String label, String url) throws IOException {
        if (!safeUrl(url)) { muted(label); return; }
        for (String line : wrap(label, regular, 8.5f, WIDTH)) {
            ensure(14);
            draw(line, MARGIN, y, regular, 8.5f, BLUE);
            if (!line.isBlank()) {
                PDAnnotationLink annotation = new PDAnnotationLink();
                annotation.setRectangle(new PDRectangle(MARGIN, y - 2, width(line, regular, 8.5f), 12));
                PDBorderStyleDictionary border = new PDBorderStyleDictionary();
                border.setWidth(0);
                annotation.setBorderStyle(border);
                PDActionURI action = new PDActionURI();
                action.setURI(url);
                annotation.setAction(action);
                page.getAnnotations().add(annotation);
            }
            y -= 14;
        }
        gap(3);
    }

    static boolean safeUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 8192
                || value.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)
                || c == '<' || c == '>')) return false;
        try {
            URI uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException ignored) { return false; }
    }

    void table(List<String> headers, List<List<String>> rows) throws IOException {
        float[] weights = new float[headers.size()];
        java.util.Arrays.fill(weights, 1);
        table(headers, rows, weights);
    }

    void table(List<String> headers, List<List<String>> rows, float[] weights) throws IOException {
        linkedTable(headers, rows, weights, java.util.Collections.nCopies(rows.size(), ""));
    }

    /** The final cell links to the row's original source, including repeated pieces of a long row. */
    void linkedTable(List<String> headers, List<List<String>> rows, float[] weights, List<String> urls) throws IOException {
        if (headers.isEmpty()) return;
        if (urls.size() != rows.size() || weights.length != headers.size() || rows.stream().anyMatch(row -> row.size() != headers.size()))
            throw new IllegalArgumentException("PDF 표의 열 수가 일치하지 않습니다.");
        float sum = 0;
        for (float weight : weights) {
            if (!(weight > 0)) throw new IllegalArgumentException("PDF 표의 열 너비가 올바르지 않습니다.");
            sum += weight;
        }
        float[] widths = new float[weights.length];
        for (int i = 0; i < widths.length; i++) widths[i] = WIDTH * weights[i] / sum;
        var headerLines = cells(headers, bold, widths);
        int headerCount = maxLines(headerLines);
        float headerHeight = headerCount * 12 + 12;
        ensure(headerHeight + 28);
        tablePart(headerLines, widths, 0, headerCount, true);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            var lines = cells(row, regular, widths);
            int count = maxLines(lines);
            int offset = 0;
            // Keep ordinary rows together; exceptionally long rows continue under a repeated header.
            if (count * 12 + 12 > y - BOTTOM && count * 12 + 12 <= TOP - BOTTOM - headerHeight) {
                newPage();
                tablePart(headerLines, widths, 0, headerCount, true);
            }
            while (offset < count) {
                int capacity = (int) ((y - BOTTOM - 12) / 12);
                if (capacity < 1) {
                    newPage();
                    tablePart(headerLines, widths, 0, headerCount, true);
                    capacity = (int) ((y - BOTTOM - 12) / 12);
                }
                int length = Math.min(count - offset, capacity);
                float top = y;
                tablePart(lines, widths, offset, length, false, safeUrl(urls.get(rowIndex)));
                if (safeUrl(urls.get(rowIndex))) {
                    float lastWidth = widths[widths.length - 1];
                    PDAnnotationLink annotation = new PDAnnotationLink();
                    annotation.setRectangle(new PDRectangle(MARGIN + WIDTH - lastWidth, y, lastWidth, top - y));
                    PDBorderStyleDictionary border = new PDBorderStyleDictionary();
                    border.setWidth(0);
                    annotation.setBorderStyle(border);
                    PDActionURI action = new PDActionURI();
                    action.setURI(urls.get(rowIndex));
                    annotation.setAction(action);
                    page.getAnnotations().add(annotation);
                }
                offset += length;
            }
        }
        gap(10);
    }

    private List<List<String>> cells(List<String> values, PDType0Font font, float[] widths) throws IOException {
        var result = new ArrayList<List<String>>();
        for (int i = 0; i < values.size(); i++) result.add(wrap(values.get(i), font, 8, widths[i] - 12));
        return result;
    }

    private int maxLines(List<List<String>> cells) { return cells.stream().mapToInt(List::size).max().orElse(1); }

    private void tablePart(List<List<String>> cells, float[] widths, int offset, int count, boolean header) throws IOException {
        tablePart(cells, widths, offset, count, header, false);
    }

    private void tablePart(List<List<String>> cells, float[] widths, int offset, int count, boolean header, boolean linked) throws IOException {
        float height = count * 12 + 12;
        if (header) {
            stream.setNonStrokingColor(new Color(232, 243, 255));
            stream.addRect(MARGIN, y - height, WIDTH, height);
            stream.fill();
        }
        float x = MARGIN;
        for (int column = 0; column < cells.size(); column++) {
            List<String> lines = cells.get(column);
            for (int i = 0; i < count && offset + i < lines.size(); i++)
                draw(lines.get(offset + i), x + 6, y - 14 - i * 12, header ? bold : regular, 8,
                        header || linked && column == cells.size() - 1 ? BLUE : INK);
            x += widths[column];
        }
        y -= height;
        line(MARGIN, y, MARGIN + WIDTH, y, RULE, 0.5f);
    }

    void chart(List<Quarter> quarters, String currency) throws IOException {
        List<Double> revenue = quarters.stream().map(q -> chartAmount(q.revenue(), currency)).toList();
        List<Double> profit = quarters.stream().map(q -> chartAmount(q.operatingProfit(), currency)).toList();
        var values = new ArrayList<Double>();
        revenue.stream().filter(java.util.Objects::nonNull).forEach(values::add);
        profit.stream().filter(java.util.Objects::nonNull).forEach(values::add);
        if (values.isEmpty()) { muted("확인된 금액이 없어 실적 차트를 표시할 수 없습니다."); return; }
        ensure(238);
        draw("매출액", MARGIN + 12, y, bold, 9, BLUE);
        draw("영업이익", MARGIN + 80, y, bold, 9, GREEN);
        draw("차트 단위: " + ("KRW".equals(currency) ? "억원" : nullable(currency)), MARGIN + 160, y, regular, 8.5f, MUTED);
        float chartTop = y - 20;
        float chartBottom = chartTop - 158;
        float left = MARGIN + 67;
        float right = MARGIN + WIDTH - 16;
        double min = Math.min(0, values.stream().mapToDouble(Double::doubleValue).min().orElse(0));
        double max = Math.max(0, values.stream().mapToDouble(Double::doubleValue).max().orElse(0));
        double padding = Math.max((max - min) * .1, .1);
        min = min < 0 ? min - padding : 0;
        max += padding;
        for (int i = 0; i <= 4; i++) {
            double value = min + (max - min) * i / 4;
            float yy = chartBottom + 158 * i / 4;
            line(left, yy, right, yy, RULE, .5f);
            String label = chartTick(value);
            draw(label, left - width(label, regular, 7.5f) - 7, yy - 3, regular, 7.5f, MUTED);
        }
        float zero = chartBottom + (float) ((0 - min) / (max - min) * 158);
        line(left, zero, right, zero, MUTED, .7f);
        chartSeries(revenue, left, right, chartBottom, min, max, BLUE, false);
        chartSeries(profit, left, right, chartBottom, min, max, GREEN, true);
        for (int i = 0; i < quarters.size(); i++) {
            var quarter = quarters.get(i);
            String label = String.format(java.util.Locale.ROOT, "%02d.%dQ", quarter.year() % 100, quarter.quarter());
            float x = chartX(i, quarters.size(), left, right);
            draw(label, x - width(label, regular, 7) / 2, chartBottom - 16, regular, 7, MUTED);
        }
        y = chartBottom - 34;
        muted("자료가 없는 분기는 선을 연결하지 않습니다. 분기별 수치는 아래 표에서 확인할 수 있습니다.");
    }

    private void chartSeries(List<Double> values, float left, float right, float bottom,
                             double min, double max, Color color, boolean square) throws IOException {
        Double previous = null;
        for (int i = 0; i < values.size(); i++) {
            Double value = values.get(i);
            if (value == null) { previous = null; continue; }
            float x = chartX(i, values.size(), left, right);
            float yy = bottom + (float) ((value - min) / (max - min) * 158);
            if (previous != null) line(chartX(i - 1, values.size(), left, right),
                    bottom + (float) ((previous - min) / (max - min) * 158), x, yy, color, 1.5f);
            stream.setNonStrokingColor(color);
            if (square) stream.addRect(x - 2.3f, yy - 2.3f, 4.6f, 4.6f);
            else {
                stream.moveTo(x, yy + 3);
                stream.lineTo(x + 3, yy);
                stream.lineTo(x, yy - 3);
                stream.lineTo(x - 3, yy);
                stream.closePath();
            }
            stream.fill();
            previous = value;
        }
    }

    private static float chartX(int index, int count, float left, float right) {
        return count == 1 ? (left + right) / 2 : left + index * (right - left) / (count - 1);
    }

    private static Double chartAmount(String value, String currency) {
        if (value == null || !value.matches("-?\\d+(?:\\.\\d+)?")) return null;
        double result = new BigDecimal(value).movePointLeft("KRW".equals(currency) ? 8 : 0).doubleValue();
        return Double.isFinite(result) && Math.abs(result) < 1e100 ? result : null;
    }

    private static String chartTick(double value) {
        if (Math.abs(value) >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.1e", value);
        return String.format(java.util.Locale.KOREA, Math.abs(value) < 10 ? "%,.1f" : "%,.0f", value);
    }

    private void textBlock(String value, float size, float leading, PDType0Font font, Color color, float indent) throws IOException {
        if (value == null || value.isBlank()) return;
        for (String line : wrap(value, font, size, WIDTH - indent)) {
            ensure(leading);
            draw(line, MARGIN + indent, y, font, size, color);
            y -= leading;
        }
    }

    private List<String> wrap(String value, PDType0Font font, float size, float available) throws IOException {
        var lines = new ArrayList<String>();
        for (String paragraph : safeText(value).split("\\R", -1)) {
            StringBuilder line = new StringBuilder();
            float used = 0;
            for (int offset = 0; offset < paragraph.length();) {
                int code = paragraph.codePointAt(offset);
                String glyph = new String(Character.toChars(code));
                float next = width(glyph, font, size);
                if (used + next > available && !line.isEmpty()) {
                    // Latin words wrap at their preceding space; long URLs and Korean wrap by codepoint.
                    int space = line.lastIndexOf(" ");
                    if (space > line.length() / 2 && Character.isLetterOrDigit(code) && code < 128) {
                        lines.add(line.substring(0, space).stripTrailing());
                        String carry = line.substring(space + 1);
                        line.setLength(0);
                        line.append(carry);
                        used = width(carry, font, size);
                    } else {
                        lines.add(line.toString().stripTrailing());
                        line.setLength(0);
                        used = 0;
                    }
                }
                if (!line.isEmpty() || !glyph.isBlank()) { line.append(glyph); used += next; }
                offset += Character.charCount(code);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private String safeText(String value) {
        String normalized = Normalizer.normalize(nullable(value), Normalizer.Form.NFC)
                .replace('\t', ' ').replace('\u2011', '-');
        StringBuilder result = new StringBuilder();
        normalized.codePoints().forEach(code -> {
            if (code == '\n' || code == '\r') { result.append((char) code); return; }
            if (Character.isISOControl(code) || Character.getType(code) == Character.FORMAT) return;
            result.append(glyphs.computeIfAbsent(code, point -> {
                String glyph = new String(Character.toChars(point));
                try { regular.encode(glyph); bold.encode(glyph); return glyph; }
                catch (IllegalArgumentException | IOException ignored) { return "[U+" + Integer.toHexString(point).toUpperCase(java.util.Locale.ROOT) + "]"; }
            }));
        });
        return result.toString();
    }

    private float width(String value, PDType0Font font, float size) throws IOException { return font.getStringWidth(value) * size / 1000; }
    private static String nullable(String value) { return value == null ? "" : value; }

    private void draw(String value, float x, float baseline, PDType0Font font, float size, Color color) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.setNonStrokingColor(color);
        stream.newLineAtOffset(x, baseline);
        stream.showText(value);
        stream.endText();
    }

    private void line(float x1, float y1, float x2, float y2, Color color, float width) throws IOException {
        stream.setStrokingColor(color);
        stream.setLineWidth(width);
        stream.moveTo(x1, y1);
        stream.lineTo(x2, y2);
        stream.stroke();
    }

    private void ensure(float height) throws IOException { if (y - height < BOTTOM) newPage(); }

    private void newPage() throws IOException {
        if (stream != null) stream.close();
        page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        stream = new PDPageContentStream(document, page);
        draw("BISTelligence", MARGIN, PDRectangle.A4.getHeight() - 36, bold, 10, BLUE);
        draw("NEWS REPORT", MARGIN + WIDTH - 72, PDRectangle.A4.getHeight() - 36, regular, 8, MUTED);
        line(MARGIN, PDRectangle.A4.getHeight() - 47, MARGIN + WIDTH, PDRectangle.A4.getHeight() - 47, RULE, .5f);
        y = TOP;
    }

    byte[] save() throws IOException {
        close();
        PDDestinationNameTreeNode tree = new PDDestinationNameTreeNode();
        tree.setNames(destinations);
        PDDocumentNameDictionary names = new PDDocumentNameDictionary(document.getDocumentCatalog());
        names.setDests(tree);
        document.getDocumentCatalog().setNames(names);
        for (InternalLink pending : internalLinks) {
            Integer targetPage = destinationPages.get(pending.destination());
            if (targetPage == null) throw new IOException("PDF 이동 대상을 찾을 수 없습니다.");
            PDAnnotationLink annotation = new PDAnnotationLink();
            annotation.setRectangle(new PDRectangle(MARGIN, pending.baseline() - 3, WIDTH, 14));
            PDBorderStyleDictionary border = new PDBorderStyleDictionary();
            border.setWidth(0);
            annotation.setBorderStyle(border);
            PDActionGoTo action = new PDActionGoTo();
            action.setDestination(new PDNamedDestination(pending.destination()));
            annotation.setAction(action);
            pending.page().getAnnotations().add(annotation);
            if (pending.showPage()) {
                try (PDPageContentStream label = new PDPageContentStream(document, pending.page(),
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    String text = "p. " + targetPage;
                    label.beginText();
                    label.setFont(regular, 9);
                    label.setNonStrokingColor(BLUE);
                    label.newLineAtOffset(MARGIN + WIDTH - width(text, regular, 9), pending.baseline());
                    label.showText(text);
                    label.endText();
                }
            }
        }
        int count = document.getNumberOfPages();
        for (int index = 0; index < count; index++) {
            try (PDPageContentStream footer = new PDPageContentStream(document, document.getPage(index),
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                String label = (index + 1) + " / " + count;
                footer.beginText();
                footer.setFont(regular, 8);
                footer.setNonStrokingColor(MUTED);
                footer.newLineAtOffset((PDRectangle.A4.getWidth() - width(label, regular, 8)) / 2, 30);
                footer.showText(label);
                footer.endText();
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        document.save(bytes);
        return bytes.toByteArray();
    }

    @Override public void close() throws IOException {
        if (stream != null) { stream.close(); stream = null; }
    }
}
