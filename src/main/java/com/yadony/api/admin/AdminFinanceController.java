package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminCashCommissionResponse;
import com.yadony.api.admin.dto.AdminMobileMoneyResponse;
import com.yadony.api.admin.dto.AdminWalletResponse;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.mobilemoney.MobileMoneyPaymentRepository;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lot D — les trois vues financieres etendues du back-office : portefeuilles, paiements
 * Mobile Money et commissions sur reglements en especes.
 *
 * <p><b>Lecture seule, sans exception.</b> Aucune ecriture n'est exposee ici : ces ecrans
 * servent a comprendre un flux financier, pas a le corriger. Une correction passe par les
 * gestes deja outilles et audites ({@code PAYMENT_RELEASE}, {@code PAYMENT_REFUND}).
 *
 * <p>⚠️ Chaque methode re-declare l'expression COMPLETE : une {@code @PreAuthorize} de
 * methode <b>remplace</b> celle de la classe. {@code hasRole('ADMIN')} seule ne filtrerait
 * rien — tout compte admin la porte, SUPPORT compris.
 *
 * <p>{@code PAYMENT_VIEW} est deja detenue par SUPPORT : ces onglets vivent dans la page
 * Transactions, elle-meme ouverte a SUPPORT. Le garde n'est donc pas la pour ecarter le
 * support, mais pour qu'un compte dont la permission a ete retiree par override le reste ici.
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('PAYMENT_VIEW')")
public class AdminFinanceController {

    /** Borne haute de pagination : une page d'ecran, pas un export deguise. */
    private static final int MAX_PAGE_SIZE = 100;

    private final WalletAccountRepository walletRepository;
    private final MobileMoneyPaymentRepository mobileMoneyRepository;
    private final BidRepository bidRepository;

    public AdminFinanceController(WalletAccountRepository walletRepository,
                                  MobileMoneyPaymentRepository mobileMoneyRepository,
                                  BidRepository bidRepository) {
        this.walletRepository = walletRepository;
        this.mobileMoneyRepository = mobileMoneyRepository;
        this.bidRepository = bidRepository;
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('PAYMENT_VIEW')")
    @GetMapping("/wallets")
    public Page<AdminWalletResponse> wallets(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return walletRepository.findAll(pageable(page, size)).map(AdminWalletResponse::from);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('PAYMENT_VIEW')")
    @GetMapping("/mobile-money-payments")
    public Page<AdminMobileMoneyResponse> mobileMoneyPayments(@RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        return mobileMoneyRepository.findAll(pageable(page, size)).map(AdminMobileMoneyResponse::from);
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('PAYMENT_VIEW')")
    @GetMapping("/cash-commissions")
    public Page<AdminCashCommissionResponse> cashCommissions(@RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        return bidRepository.findCashCommissions(pageable(page, size))
                .map(AdminCashCommissionResponse::from);
    }

    /** Borne les parametres plutot que de les refuser : un ecran ne doit pas casser sur une URL bricolee. */
    private static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }
}
