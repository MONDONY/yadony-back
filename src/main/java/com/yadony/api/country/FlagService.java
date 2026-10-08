package com.yadony.api.country;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

/**
 * Résout l'emoji drapeau d'un pays à partir de son code ISO-2, en le calculant
 * paresseusement puis en le mettant en cache dans la table {@code countries}.
 *
 * <p>Premier accès à un pays sans drapeau stocké → calcul de l'emoji depuis le code,
 * persistance, retour. Accès suivants → lecture en base.</p>
 */
@Service
public class FlagService {

    /** Premier Regional Indicator Symbol (lettre 'A'). */
    private static final int REGIONAL_INDICATOR_BASE = 0x1F1E6;

    private final CountryRepository countryRepository;

    public FlagService(CountryRepository countryRepository) {
        this.countryRepository = countryRepository;
    }

    public String getFlag(String countryCode) {
        return getFlag(countryCode, null);
    }

    @Transactional
    public String getFlag(String countryCode, String countryName) {
        String code = normalize(countryCode);
        if (code == null) {
            return null;
        }

        Optional<CountryEntity> existing = countryRepository.findById(code);
        if (existing.isPresent() && existing.get().getFlag() != null) {
            return existing.get().getFlag();
        }
        // Appelé depuis une lecture (liste « Mes trajets », en transaction readOnly) : Postgres
        // y refuse tout INSERT et l'échec ferait tomber la liste entière. Le drapeau se déduit
        // du seul code ISO : on le rend sans le persister, une écriture suivante le fera.
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return emojiFromIso(code);
        }
        if (existing.isPresent()) {
            CountryEntity entity = existing.get();
            // Ligne présente mais drapeau manquant : on le calcule et on le persiste.
            String flag = emojiFromIso(code);
            entity.setFlag(flag);
            if (entity.getCountryName() == null && countryName != null) {
                entity.setCountryName(countryName);
            }
            countryRepository.save(entity);
            return flag;
        }

        // Ligne absente : on crée le pays avec son drapeau calculé. Si un autre appel l'a
        // créé entre-temps, l'INSERT ne fait rien ; le drapeau, dérivé du seul code ISO,
        // est le même.
        String flag = emojiFromIso(code);
        countryRepository.insertIfAbsent(code, countryName, flag);
        return flag;
    }

    /**
     * Normalise un code ISO-2 : trim + uppercase. Retourne {@code null} si l'entrée
     * n'est pas exactement deux lettres ASCII A–Z (on ne persiste jamais de déchet).
     */
    private static String normalize(String countryCode) {
        if (countryCode == null) {
            return null;
        }
        String code = countryCode.trim().toUpperCase();
        if (code.length() != 2) {
            return null;
        }
        for (int i = 0; i < 2; i++) {
            char c = code.charAt(i);
            if (c < 'A' || c > 'Z') {
                return null;
            }
        }
        return code;
    }

    /**
     * Calcule l'emoji drapeau d'un code ISO-2 (supposé déjà normalisé : 2 lettres A–Z).
     * Chaque lettre est convertie en Regional Indicator Symbol, puis concaténée.
     */
    public static String emojiFromIso(String code) {
        int first = REGIONAL_INDICATOR_BASE + (code.charAt(0) - 'A');
        int second = REGIONAL_INDICATOR_BASE + (code.charAt(1) - 'A');
        return new String(Character.toChars(first)) + new String(Character.toChars(second));
    }
}
