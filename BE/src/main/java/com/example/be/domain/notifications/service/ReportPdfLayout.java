package com.example.be.domain.notifications.service;

import com.example.be.domain.dart.model.DartModels.Quarter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
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
        if (headers.isEmpty()) return;
        if (weights.length != headers.size() || rows.stream().anyMatch(row -> row.size() != headers.size()))
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
        for (List<String> row : rows) {
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
                tablePart(lines, widths, offset, length, false);
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
                draw(lines.get(offset + i), x + 6, y - 14 - i * 12, header ? bold : regular, 8, header ? BLUE : INK);
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
        muted("자료가 없는 분기는 선을 연결하지 않습니다. 정확한 금액과 근거 공시는 아래 표에서 확인할 수 있습니다.");
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
