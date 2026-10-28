package com.example.be.domain.notifications.entity;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.settings.exception.AudienceException;

/** An explicit recipient choice; NONE never opts into a stored perspective. */
public enum RecipientInsightAudience {
    NONE,
    CHIP_MAKER,
    EQUIPMENT_MAKER,
    MARKET_INVESTOR,
    IT_INFRA;

    public static RecipientInsightAudience fromApiValue(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new AudienceException();
        }
    }

    public Audience audience() {
        return this == NONE ? null : Audience.valueOf(name());
    }
}
