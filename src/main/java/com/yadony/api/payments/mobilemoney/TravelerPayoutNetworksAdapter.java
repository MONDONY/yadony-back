package com.yadony.api.payments.mobilemoney;

import com.yadony.api.matching.TravelerPayoutNetworksPort;
import com.yadony.api.payments.pawapay.PawapayProviders;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Implémentation du port côté {@code payments/}, comme {@code BidNegotiationMobileMoneyAdapter}. */
@Component
public class TravelerPayoutNetworksAdapter implements TravelerPayoutNetworksPort {

    private final MobileMoneyPayoutAccounts accounts;

    public TravelerPayoutNetworksAdapter(MobileMoneyPayoutAccounts accounts) {
        this.accounts = accounts;
    }

    @Override
    public List<PayoutNetwork> acceptedNetworks(UUID travelerId, String currency) {
        return accounts.activeIn(travelerId, currency).stream()
                .map(a -> new PayoutNetwork(a.getProvider(), PawapayProviders.label(a.getProvider()),
                        PawapayProviders.brand(a.getProvider()), a.getCountry()))
                .toList();
    }
}
