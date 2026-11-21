package com.yadony.api.cancellation.fee;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.dto.CancellationFeePreviewResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Aperçu montré à l'expéditeur AVANT qu'il confirme l'annulation de son colis : ce que le remboursement retiendrait
 * s'il annulait maintenant. Lecture seule, aucun effet (rien n'est enregistré, aucun audit).
 *
 * <p>Aucune seconde règle : la décision est celle de l'enregistrement de la retenue (statut accepté ou remis,
 * {@link SenderCancellationFeeRecorder#senderCancellationReason} ; interrupteur, date d'effet, rail et taux du barème,
 * {@link CancellationFeeEligibility}), le brut celui du règlement (port {@link CancellationFeeQuotePort}, implémenté
 * par {@code payments} avec le calcul du remboursement). L'aperçu montre le brut que perd l'expéditeur, jamais la part
 * nette du voyageur.
 *
 * <p>Aucun frais (taux 0, frais 0, tout le payé rendu, aucune hausse annoncée) quand l'interrupteur est éteint, que le
 * colis précède la date d'effet, qu'il est payé en espèces, pas encore accepté, qu'un report du trajet attend sa
 * réponse, ou que son paiement ne peut pas porter de retenue. Dans la fenêtre gratuite, le taux est 0 mais la hausse à venir est annoncée ({@code nextTierAt}).
 */
@Service
public class CancellationFeePreviewService {

    private final UserRepository users;
    private final BidRepository bids;
    private final CancellationFeeEligibility eligibility;
    private final CancellationFeeQuotePort quotes;
    private final Clock clock;

    public CancellationFeePreviewService(UserRepository users, BidRepository bids, CancellationFeeEligibility eligibility,
                                         CancellationFeeQuotePort quotes, Clock clock) {
        this.users = users;
        this.bids = bids;
        this.eligibility = eligibility;
        this.quotes = quotes;
        this.clock = clock;
    }

    /**
     * @throws YadonyBusinessException 401 appelant inconnu, 404 {@code bid-not-found}, 403 {@code forbidden} si
     *                                 l'appelant n'est pas l'expéditeur du colis
     */
    @Transactional(readOnly = true)
    public CancellationFeePreviewResponse preview(String firebaseUid, UUID bidId) {
        UUID callerId = users.findByFirebaseUid(firebaseUid)
                .map(UserEntity::getId)
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.UNAUTHORIZED, "unauthorized",
                        "problem.auth.user-not-found"));
        BidEntity bid = bids.findById(bidId)
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "bid-not-found",
                        "problem.not-found.bid"));
        if (!callerId.equals(bid.getSenderId())) {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "forbidden",
                    "problem.forbidden.fee-preview-sender-only");
        }

        Instant now = clock.instant();
        Optional<CancellationFeeEligibility.Assessment> assessed =
                SenderCancellationFeeRecorder.senderCancellationReason(bid.getStatus()).isPresent()
                        ? eligibility.assess(bid, bid.getStatus(), now)
                        : Optional.empty();
        BigDecimal rate = assessed.map(CancellationFeeEligibility.Assessment::rate).orElse(BigDecimal.ZERO);

        Optional<CancellationFeeQuotePort.Quote> quote = quotes.quote(bidId, rate);
        if (quote.isEmpty()) {
            // Rien de payé en ligne ni de remboursable (espèces, pas encore payé) : rien à retenir, rien à rendre.
            return new CancellationFeePreviewResponse(BigDecimal.ZERO, BigDecimal.ZERO, bid.getCurrency(),
                    BigDecimal.ZERO, null);
        }
        boolean feeApplies = assessed.isPresent() && quote.get().retainable();
        return new CancellationFeePreviewResponse(
                feeApplies ? rate : BigDecimal.ZERO,
                quote.get().grossFee(),
                quote.get().currency(),
                quote.get().refundAmount(),
                feeApplies ? assessed.get().nextIncreaseAt() : null);
    }
}
