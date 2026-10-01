package com.yadony.api.auth.dto;

public record PrivacySettingsResponse(boolean contactKycOnly, boolean hidePhoneNumber,
                                      boolean showResidenceCountry) {

    public PrivacySettingsResponse(boolean contactKycOnly, boolean hidePhoneNumber) {
        this(contactKycOnly, hidePhoneNumber, false);
    }
}
