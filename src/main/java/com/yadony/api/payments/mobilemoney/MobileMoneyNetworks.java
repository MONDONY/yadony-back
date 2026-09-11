package com.yadony.api.payments.mobilemoney;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.payments.pawapay.PawapayProviders;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Réseaux mobile money acceptés par un voyageur, lus sur {@link UserEntity}. La logique de marque
 * vit ici, dans {@code payments/}, pour que le package {@code auth} n'importe rien de pawaPay.
 *
 * <p>Repli hérité : un compte activé avant V255 n'a que {@code mobile_money_provider} ; il vaut
 * alors une liste d'un seul réseau, donc une seule marque acceptée, jusqu'à ce que le voyageur
 * élargisse depuis l'app.
 */
public final class MobileMoneyNetworks {

    private MobileMoneyNetworks() {}

    /** Codes acceptés, dans l'ordre enregistré (repli sur l'opérateur unique, s'il est renseigné). */
    public static List<String> acceptedCodes(UserEntity traveler) {
        List<String> codes = traveler.getMobileMoneyProviderList();
        String single = traveler.getMobileMoneyProvider();
        if (codes.isEmpty() && single != null && !single.isBlank()) {
            return List.of(single);
        }
        return codes;
    }

    /** Marques acceptées ({@link PawapayProviders#brand}), dédoublonnées, dans l'ordre des codes. */
    public static Set<String> acceptedBrands(UserEntity traveler) {
        Set<String> brands = new LinkedHashSet<>();
        for (String code : acceptedCodes(traveler)) {
            String brand = PawapayProviders.brand(code);
            if (brand != null) {
                brands.add(brand);
            }
        }
        return brands;
    }

    /** Libellés lisibles des réseaux acceptés, dédoublonnés (« Orange Money », « Wave »). */
    public static List<String> acceptedLabels(UserEntity traveler) {
        return acceptedCodes(traveler).stream().map(PawapayProviders::label).distinct().toList();
    }

    /** Vrai si la marque de {@code providerCode} est acceptée par le voyageur. */
    public static boolean acceptsBrand(UserEntity traveler, String providerCode) {
        String brand = PawapayProviders.brand(providerCode);
        return brand != null && acceptedBrands(traveler).contains(brand);
    }

    /** Code accepté de cette marque, s'il existe : c'est lui qui reçoit le versement. */
    public static Optional<String> providerForBrand(UserEntity traveler, String brand) {
        if (brand == null) {
            return Optional.empty();
        }
        return acceptedCodes(traveler).stream().filter(c -> brand.equalsIgnoreCase(PawapayProviders.brand(c))).findFirst();
    }
}
