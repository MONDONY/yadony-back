package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.TravelerPayoutNetworksPort.PayoutNetwork;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TravelerPayoutNetworksAdapterTest {

    private static final Instant VERIFIED = Instant.parse("2026-03-04T10:11:12Z");

    @Mock private MobileMoneyPayoutAccountRepository repository;

    private TravelerPayoutNetworksAdapter adapter;
    private final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new TravelerPayoutNetworksAdapter(new MobileMoneyPayoutAccounts(repository, TestMessages.resolver()));
        lenient().when(repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(travelerId)).thenReturn(List.of(
                account("ORANGE_CIV", "CI", "XOF", "+2250700000001"),
                account("ORANGE_SEN", "SN", "XOF", "+221770000002"),
                account("MTN_MOMO_CMR", "CM", "XAF", "+237670000003")));
    }

    private static MobileMoneyPayoutAccountEntity account(String provider, String country, String currency,
                                                          String msisdn) {
        MobileMoneyPayoutAccountEntity a = new MobileMoneyPayoutAccountEntity(UUID.randomUUID(), provider, country,
                currency, msisdn, "masked", VERIFIED);
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        return a;
    }

    @Test
    void xofTrip_listsBothOrangeCountriesInAddOrder_withoutAnyNumber() {
        List<PayoutNetwork> networks = adapter.acceptedNetworks(travelerId, "XOF");

        assertThat(networks).containsExactly(
                new PayoutNetwork("ORANGE_CIV", "Orange Money", "ORANGE", "CI"),
                new PayoutNetwork("ORANGE_SEN", "Orange Money", "ORANGE", "SN"));
        assertThat(networks.toString()).doesNotContain("+225").doesNotContain("+221");
    }

    @Test
    void xafTrip_listsOnlyTheXafAccount() {
        assertThat(adapter.acceptedNetworks(travelerId, "xaf")).containsExactly(
                new PayoutNetwork("MTN_MOMO_CMR", "MTN MoMo", "MTN", "CM"));
    }

    @Test
    void eurTrip_isEmpty() {
        assertThat(adapter.acceptedNetworks(travelerId, "EUR")).isEmpty();
    }

    @Test
    void nullCurrency_isEmpty() {
        assertThat(adapter.acceptedNetworks(travelerId, null)).isEmpty();
    }
}
