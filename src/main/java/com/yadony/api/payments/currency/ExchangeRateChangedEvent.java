package com.yadony.api.payments.currency;

import java.math.BigDecimal;

/**
 * Un taux administré de {@code exchange_rates} vient d'être modifié (back-office ou
 * synchronisation BCE), dans la transaction courante.
 *
 * <p>Écouté par {@code matching.ExchangeRatePivotListener} pour recalculer le pivot EUR
 * des annonces de la devise — via événement et non par injection directe du repository
 * de {@code matching} : la règle du projet interdit les dépendances de service
 * inter-packages. Listener SYNCHRONE même transaction, à dessein : un taux commité sans
 * ses pivots recalculés ferait filtrer et trier le fil sur l'ancien taux.
 */
public record ExchangeRateChangedEvent(String currency, BigDecimal unitsPerEur) {}
