package com.yadony.api.payments.mobilemoney.dto;

import java.util.List;

/** Corps de PUT /payments/mobile-money/account/providers : nouveaux réseaux acceptés (codes pawaPay). */
public record MobileMoneyProvidersUpdateRequest(List<String> providers) {}
