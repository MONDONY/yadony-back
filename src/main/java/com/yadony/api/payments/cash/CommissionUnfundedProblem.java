package com.yadony.api.payments.cash;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.cash.dto.CommissionFundingDetailDto;
import com.yadony.api.payments.cash.dto.CommissionShortfallDto;
import com.yadony.api.payments.cash.dto.FundingAlternativeDto;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 422 « solde insuffisant » d'une commission annexe (supplément, modification) prélevée au voyageur sur son
 * portefeuille. Mêmes propriétés que l'{@code INSUFFICIENT_WALLET} de l'acceptation espèces, que l'app lit
 * pour proposer de recharger, de payer par carte ou d'utiliser un autre portefeuille : {@code availableBalance},
 * {@code requiredCommission}, {@code shortfall}, {@code hasCard}, {@code currency} (devise de complément),
 * {@code breakdown} si les devises diffèrent, {@code bidCurrency}, {@code alternatives} et {@code fundingDetail}
 * (détail de tous les portefeuilles : un 422 signifie que leur SOMME ne couvre pas la commission).
 */
public final class CommissionUnfundedProblem {

    private CommissionUnfundedProblem() {
    }

    /**
     * @param errorCode code du problème ({@code supplement-commission-unfunded},
     *                  {@code amendment-commission-unfunded}) ; le titre en est dérivé par l'appelant.
     * @param split     répartition non couverte rendue par {@link WalletCommissionCollector#plan}.
     */
    public static YadonyBusinessException of(String errorCode, String title, String detail, CommissionSplit split,
                                             String bidCurrency, String complementCurrency, BigDecimal commission,
                                             boolean hasCard, List<FundingAlternativeDto> alternatives) {
        BigDecimal missing = split.sameCurrency()
                ? commission.subtract(split.activeBalance())
                : split.remainingActive().subtract(split.activeBalance());
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("availableBalance", split.activeBalance());
        props.put("requiredCommission", split.commissionInActive());
        props.put("shortfall", missing.max(BigDecimal.ZERO));
        props.put("hasCard", hasCard);
        props.put("currency", complementCurrency);
        CommissionShortfallDto breakdown = CommissionShortfallDto.from(split);
        if (breakdown != null) {
            props.put("breakdown", breakdown);
        }
        props.put("bidCurrency", bidCurrency);
        props.put("alternatives", alternatives);
        props.put("fundingDetail", CommissionFundingDetailDto.from(split));
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, title, detail, props);
    }
}
