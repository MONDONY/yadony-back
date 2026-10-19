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
 * les marques acceptées par le voyageur. Lecture seule, sans donnée personnelle de l'expéditeur.
 * Le catalogue lève lui-même 422 {@code mobile-money-disabled} (rail coupé) et 502
 * {@code mobile-money-provider-unavailable} (pawaPay injoignable) : ils remontent tels quels.
 */
@Service
public class MobileMoneyAcceptedOperatorsService {

    private final MobileMoneyCountriesService countries;
    private final UserRepository userRepository;

    public MobileMoneyAcceptedOperatorsService(MobileMoneyCountriesService countries, UserRepository userRepository) {
        this.countries = countries;
        this.userRepository = userRepository;
    }

    /** Variante par identifiant (fil de négociation) : charge le voyageur, 404 s'il n'existe plus. */
    public MobileMoneyAcceptedOperatorsResponse forTraveler(UUID travelerId, String currency) {
        UserEntity traveler = userRepository.findById(travelerId).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Voyageur introuvable"));
        return forTraveler(traveler, currency);
    }

    /**
     * @return les codes des opérateurs ouverts en dépôt dont le pays a cette devise et dont la marque
     *         est acceptée par le voyageur, dans l'ordre du catalogue (pays, puis marque)
     */
    public MobileMoneyAcceptedOperatorsResponse forTraveler(UserEntity traveler, String currency) {
        List<String> providers = new ArrayList<>();
        for (CountryOption country : countries.countries(PawapayOperationKind.DEPOSIT).available()) {
            if (currency == null || !currency.equalsIgnoreCase(country.currency())) {
                continue;
            }
            for (OperatorOption operator : country.operators()) {
                // Seul point d'acceptation : le lot C remplacera cet appel par le catalogue de versement.
                if (MobileMoneyNetworks.acceptsBrand(traveler, operator.code())) {
                    providers.add(operator.code());
                }
            }
        }
        return new MobileMoneyAcceptedOperatorsResponse(currency, traveler.getFirstName(), List.copyOf(providers));
    }
}
