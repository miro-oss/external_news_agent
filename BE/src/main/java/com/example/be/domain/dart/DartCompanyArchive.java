package com.example.be.domain.dart;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipInputStream;

/** The archive is never extracted to disk, and XML cannot resolve DTDs or external entities. */
final class DartCompanyArchive {
    static final int MAX_XML_BYTES = 128 * 1024 * 1024;
    static final int MAX_COMPANIES = 500_000;
    private final int xmlLimit;
    record Entry(DartModels.Company company, LocalDate modifiedAt) { }

    DartCompanyArchive() { this(MAX_XML_BYTES); }
    DartCompanyArchive(int xmlLimit) {
        if (xmlLimit < 1 || xmlLimit > MAX_XML_BYTES) throw new IllegalArgumentException("Invalid XML limit");
        this.xmlLimit = xmlLimit;
    }

    List<Entry> parse(byte[] archive) {
        if (archive == null || archive.length == 0 || archive.length > DartClient.MAX_ZIP_BYTES)
            throw new DartUpstreamException("BAD_RESPONSE");
        if (archive.length < 4 || archive[0] != 'P' || archive[1] != 'K') {
            // DART sends XML error statuses instead of a ZIP, even on HTTP 200.
            parseXml(archive, false);
            throw new DartUpstreamException("BAD_RESPONSE");
        }
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            byte[] xml = null;
            int entries = 0;
            long total = 0;
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 4 || entry.isDirectory() || entry.getSize() > xmlLimit)
                    throw new DartUpstreamException("BAD_RESPONSE");
                // No paths, unexpected files, or duplicate corpus are accepted.
                if (!"CORPCODE.xml".equalsIgnoreCase(entry.getName()) || xml != null)
                    throw new DartUpstreamException("BAD_RESPONSE");
                xml = zip.readNBytes(xmlLimit + 1);
                total += xml.length;
                if (total > xmlLimit) throw new DartUpstreamException("BAD_RESPONSE");
                zip.closeEntry(); // validates CRC before any database replacement
            }
            if (xml == null) throw new DartUpstreamException("BAD_RESPONSE");
            return parseXml(xml, true);
        } catch (IOException ignored) { throw new DartUpstreamException("BAD_RESPONSE"); }
    }

    private List<Entry> parseXml(byte[] xml, boolean corpus) {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> {
            throw new XMLStreamException("External entities are forbidden");
        });
        List<Entry> entries = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        try {
            var reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml), "UTF-8");
            try {
                String code = null, name = null, stock = "", modified = null;
                boolean inList = false;
                int depth = 0;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE)
                        throw new DartUpstreamException("BAD_RESPONSE");
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if (++depth > 4) throw new DartUpstreamException("BAD_RESPONSE");
                        String tag = reader.getLocalName();
                        if ("list".equals(tag)) {
                            if (inList || !corpus || depth != 2) throw new DartUpstreamException("BAD_RESPONSE");
                            inList = true;
                            code = name = modified = null;
                            stock = "";
                        } else if ("status".equals(tag)) {
                            String status = reader.getElementText().trim(); depth--;
                            if (!"000".equals(status)) throw new DartUpstreamException(
                                    status.matches("010|011|012|013|014|020|021|100|101|800|900|901")
                                            ? status : "BAD_RESPONSE");
                        } else if (inList) {
                            String value = reader.getElementText().trim(); depth--;
                            if (value.length() > 512) throw new DartUpstreamException("BAD_RESPONSE");
                            switch (tag) {
                                case "corp_code" -> code = value;
                                case "corp_name" -> name = value;
                                case "stock_code" -> stock = value;
                                case "modify_date" -> modified = value;
                                default -> { }
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("list".equals(reader.getLocalName())) {
                            if (code == null || !code.matches("[0-9]{8}") || name == null || name.isBlank()
                                    || !stock.matches("[0-9A-Z]{6}|")) throw new DartUpstreamException("BAD_RESPONSE");
                            LocalDate date = LocalDate.parse(modified, DateTimeFormatter.BASIC_ISO_DATE);
                            if (!codes.add(code) || entries.size() >= MAX_COMPANIES)
                                throw new DartUpstreamException("BAD_RESPONSE");
                            entries.add(new Entry(new DartModels.Company(code, name, stock), date));
                            inList = false;
                        }
                        depth--;
                    }
                }
            } finally { reader.close(); }
            if (entries.isEmpty()) throw new DartUpstreamException("BAD_RESPONSE");
            return List.copyOf(entries);
        } catch (DartUpstreamException safe) { throw safe; }
        catch (Exception ignored) { throw new DartUpstreamException("BAD_RESPONSE"); }
    }
}
