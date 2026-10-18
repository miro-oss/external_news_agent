package com.example.be.domain.notifications.service;

import com.example.be.domain.dart.model.DartModels.Filing;

import java.net.URI;
import java.net.URISyntaxException;

/** Stored filing links must point to the matching receipt on the official DART viewer. */
final class DartFilingLinks {
    private DartFilingLinks() { }

    static boolean isSafe(Filing filing) {
        if (filing == null || filing.rceptNo() == null || !filing.rceptNo().matches("[0-9]{14}")) return false;
        String url = filing.url();
        if (url == null || url.isBlank() || url.length() > 2048 || url.chars().anyMatch(character ->
                Character.isWhitespace(character) || Character.isISOControl(character) || character == '<' || character == '>')) return false;
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && "dart.fss.or.kr".equalsIgnoreCase(uri.getHost())
                    && uri.getPort() == -1 && uri.getUserInfo() == null && uri.getFragment() == null
                    && "/dsaf001/main.do".equals(uri.getRawPath())
                    && ("rcpNo=" + filing.rceptNo()).equals(uri.getRawQuery());
        } catch (URISyntaxException exception) {
            return false;
        }
    }
}
