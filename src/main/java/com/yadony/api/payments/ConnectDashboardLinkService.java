package com.yadony.api.payments;

import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.LoginLink;
import com.stripe.param.AccountLinkCreateParams;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.StripeConnectProperties;
import com.yadony.api.payments.dto.DashboardLinkResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Accès du voyageur à son tableau de bord Stripe Express, d'où il change de compte bancaire
 * (IBAN) et suit ses virements. Les comptes Connect sont créés en API v2 avec
 * {@code dashboard = express} ({@link StripeV2AccountProvisioner}) : Stripe y accepte les login
 * links v1. Si Stripe refuse quand même le login link (requête invalide), repli sur un Account
 * Link {@code account_update} (formulaire hébergé de mise à jour du compte).
 *
 * <p>Le lien est à usage unique et donne accès au compte : jamais journalisé ni mis en cache.
 */
@Service
public class ConnectDashboardLinkService {

    private static final Logger log = LoggerFactory.getLogger(ConnectDashboardLinkService.class);

    private final UserRepository userRepository;
    private final StripeGateway stripeGateway;
    private final StripeConnectProperties connectProperties;

    public ConnectDashboardLinkService(UserRepository userRepository, StripeGateway stripeGateway,
                                       StripeConnectProperties connectProperties) {
        this.userRepository = userRepository;
        this.stripeGateway = stripeGateway;
        this.connectProperties = connectProperties;
    }

    public DashboardLinkResponse createDashboardLink(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));
        String accountId = user.getStripeAccountId();
        if (accountId == null || accountId.isBlank()) {
            throw accountMissing();
        }
        Account account;
        try {
            account = stripeGateway.retrieveAccount(accountId);
        } catch (StripeException e) {
            if ("resource_missing".equals(e.getCode())) {
                throw accountMissing();
            }
            log.error("Tableau de bord Stripe : compte {} de l'utilisateur {} illisible ({})",
                    accountId, user.getId(), e.getMessage());
            throw linkFailed();
        }
        if (account == null || !Boolean.TRUE.equals(account.getDetailsSubmitted())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "stripe-onboarding-incomplete",
                    "Stripe Onboarding Incomplete",
                    "Terminez d'abord l'inscription de votre compte de paiement.");
        }
        try {
            LoginLink link = stripeGateway.createLoginLink(accountId);
            return new DashboardLinkResponse(link.getUrl());
        } catch (InvalidRequestException refused) {
            log.warn("Login link Stripe refusé pour le compte {} ({}) : repli sur account_update",
                    accountId, refused.getCode());
            return accountUpdateLink(accountId, user);
        } catch (StripeException e) {
            log.error("Login link Stripe impossible pour le compte {} ({})", accountId, e.getMessage());
            throw linkFailed();
        }
    }

    private DashboardLinkResponse accountUpdateLink(String accountId, UserEntity user) {
        try {
            AccountLink link = stripeGateway.createAccountLink(AccountLinkCreateParams.builder()
                    .setAccount(accountId)
                    .setReturnUrl(connectProperties.returnUrl())
                    .setRefreshUrl(connectProperties.refreshUrl())
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_UPDATE)
                    .build());
            return new DashboardLinkResponse(link.getUrl());
        } catch (StripeException e) {
            log.error("Account link account_update impossible pour l'utilisateur {} ({})", user.getId(), e.getMessage());
            throw linkFailed();
        }
    }

    private static YadonyBusinessException accountMissing() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "stripe-account-missing",
                "Stripe Account Missing", "Aucun compte de paiement n'est relié à votre profil.");
    }

    private static YadonyBusinessException linkFailed() {
        return new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "stripe-dashboard-link-failed",
                "Stripe Dashboard Link Failed", "Impossible d'ouvrir votre espace Stripe pour le moment.");
    }
}
