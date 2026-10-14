package com.example.be.domain.dart.company;

import com.example.be.domain.dart.model.DartModels;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class DartCompanyTestFixtures {
    static final DartModels.Company COMPANY = new DartModels.Company("00126380", "삼성전자", "005930");

    static byte[] zip(String name, String text) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(text.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
