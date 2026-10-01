package com.yadony.api.auth.dto;

public record PrivacySettingsResponse(boolean contactKycOnly, boolean hidePhoneNumber,
                                      boolean showResidenceCountry, boolean showLastSeen) {

    public PrivacySettingsResponse(boolean contactKycOnly, boolean hidePhoneNumber) {
        this(contactKycOnly, hidePhoneNumber, false, true);
    }

    public PrivacySettingsResponse(boolean contactKycOnly, boolean hidePhoneNumber,
                                   boolean showResidenceCountry) {
        this(contactKycOnly, hidePhoneNumber, showResidenceCountry, true);
    }
}
