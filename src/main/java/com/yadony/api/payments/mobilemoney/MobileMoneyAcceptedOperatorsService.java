package com.yadony.api.payments.mobilemoney;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyAcceptedOperatorsResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyCountriesResponse.CountryOption;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyCountriesResponse.OperatorOption;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Opérateurs avec lesquels un expéditeur peut payer un voyageur, sans connaître son numéro : le
 * catalogue de dépôt ({@link MobileMoneyCountriesService}) filtré sur la devise du colis, puis sur
 * les comptes de versement du voyageur (code exact, même devise : {@link MobileMoneyPayoutAccounts#accepts}). Lecture seule, sans donnée personnelle de l'expéditeur.
 * Le catalogue lève lui-même 422 {@code mobile-money-disabled} (rail coupé) et 502
 * {@code mobile-money-provider-unavailable} (pawaPay injoignable) : ils remontent tels quels.
 */
@Service
public class MobileMoneyAcceptedOperatorsService {

    private final MobileMoneyCountriesService countries;
    private final UserRepository userRepository;
    private final MobileMoneyPayoutAccounts payoutAccounts;

    public MobileMoneyAcceptedOperatorsService(MobileMoneyCountriesService countries, UserRepository userRepository,
                                               MobileMoneyPayoutAccounts payoutAccounts) {
        this.countries = countries;
        this.userRepository = userRepository;
        this.payoutAccounts = payoutAccounts;
    }

    /** Variante par identifiant (fil de négociation) : charge le voyageur, 404 s'il n'existe plus. */
    public MobileMoneyAcceptedOperatorsResponse forTraveler(UUID travelerId, String currency) {
        UserEntity traveler = userRepository.findById(travelerId).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Voyageur introuvable"));
        return forTraveler(traveler, currency);
    }

    /**
     * @return les codes des opérateurs ouverts en dépôt dont le pays a cette devise et dont la marque
     *         pour lesquels le voyageur a un compte de versement de ce code exact dans cette devise,
     *         dans l'ordre du catalogue (pays, puis marque)
     */
    public MobileMoneyAcceptedOperatorsResponse forTraveler(UserEntity traveler, String currency) {
        List<String> providers = new ArrayList<>();
        for (CountryOption country : countries.countries(PawapayOperationKind.DEPOSIT).available()) {
            if (currency == null || !currency.equalsIgnoreCase(country.currency())) {
                continue;
            }
            for (OperatorOption operator : country.operators()) {
                if (payoutAccounts.accepts(traveler.getId(), operator.code(), currency)) {
                    providers.add(operator.code());
                }
            }
        }
        return new MobileMoneyAcceptedOperatorsResponse(currency, traveler.getFirstName(), List.copyOf(providers));
    }
}
