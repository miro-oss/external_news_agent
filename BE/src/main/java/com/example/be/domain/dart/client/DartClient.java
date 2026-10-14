package com.example.be.domain.dart.client;

import com.example.be.domain.dart.config.DartProperties;
import com.example.be.domain.dart.exception.DartUpstreamException;
import com.example.be.global.config.RestClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Fixed HTTPS origin; no configurable destination or redirects can receive the credential. */
@Component
public class DartClient {
    static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    public static final int MAX_ZIP_BYTES = 32 * 1024 * 1024;
    private static final Set<String> STATUSES = Set.of("000", "010", "011", "012", "013", "014",
            "020", "021", "100", "101", "800", "900", "901");
    private final RestClient http;
    private final DartProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public DartClient(DartProperties properties, RestClientFactory factory) {
        this(properties, factory.create(properties.getConnectTimeout(), properties.getReadTimeout())
                .baseUrl("https://opendart.fss.or.kr/api").build());
    }

    DartClient(DartProperties properties, RestClient http) {
        this.properties = properties;
        this.http = http;
    }

    public byte[] companyArchive() { return request("corpCode.xml", Map.of(), MAX_ZIP_BYTES); }

    public JsonNode company(String corpCode) { return json("company.json", Map.of("corp_code", corpCode)); }

    public JsonNode financial(String corpCode, int year, String reportCode, String fsDiv) {
        return json("fnlttSinglAcntAll.json", Map.of("corp_code", corpCode,
                "bsns_year", Integer.toString(year), "reprt_code", reportCode, "fs_div", fsDiv));
    }

    public JsonNode filings(String corpCode, LocalDate from, LocalDate to, boolean regularOnly, int page, int size) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("corp_code", corpCode);
        params.put("bgn_de", from.format(DateTimeFormatter.BASIC_ISO_DATE));
        params.put("end_de", to.format(DateTimeFormatter.BASIC_ISO_DATE));
        // Keep original receipts too for provenance and correction-lag detection.
        params.put("last_reprt_at", regularOnly ? "N" : "Y");
        params.put("sort", "date");
        params.put("sort_mth", "desc");
        params.put("page_no", Integer.toString(page));
        params.put("page_count", Integer.toString(size));
        if (regularOnly) params.put("pblntf_ty", "A");
        return json("list.json", params);
    }

    private JsonNode json(String endpoint, Map<String, String> params) {
        try {
            JsonNode root = json.readTree(request(endpoint, params, MAX_JSON_BYTES));
            String status = root == null ? "" : root.path("status").asText("");
            if (!STATUSES.contains(status)) throw new DartUpstreamException("BAD_RESPONSE");
            if (!"000".equals(status) && !"013".equals(status)) throw new DartUpstreamException(status);
            return root;
        } catch (DartUpstreamException safe) { throw safe; }
        catch (RuntimeException ignored) { throw new DartUpstreamException("BAD_RESPONSE"); }
    }

    private byte[] request(String endpoint, Map<String, String> params, int maxBytes) {
        if (!properties.isEnabled()) throw new DartUpstreamException("DISABLED");
        try {
            return http.get().uri(builder -> {
                builder.path("/" + endpoint).queryParam("crtfc_key", properties.getApiKey());
                params.forEach(builder::queryParam);
                return builder.build();
            }).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) throw new DartUpstreamException("HTTP");
                if (response.getHeaders().getContentLength() > maxBytes)
                    throw new DartUpstreamException("BAD_RESPONSE");
                try (var body = response.getBody()) {
                    byte[] bytes = body.readNBytes(maxBytes + 1);
                    if (bytes.length == 0 || bytes.length > maxBytes)
                        throw new DartUpstreamException("BAD_RESPONSE");
                    return bytes;
                }
            });
        } catch (DartUpstreamException safe) { throw safe; }
        catch (RuntimeException ignored) { throw new DartUpstreamException("NETWORK"); }
    }
}
