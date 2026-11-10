package com.yadony.api.notifications;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.i18n.Messages;
import com.yadony.api.common.i18n.MessagesResolver;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lien « Ne plus recevoir ces rappels » des relances de vérification d'identité (public, sous
 * {@code /public/**} : le jeton signé de l'adresse fait foi, aucun jeton Firebase).
 *
 * <p>{@code GET} n'affiche qu'une confirmation : les antivirus des messageries ouvrent les liens
 * des e-mails, un GET qui coupait le réglage désinscrirait des gens sans qu'ils le sachent.
 * {@code POST} enregistre le refus (date du premier refus, jamais écrasée) : c'est le bouton de
 * la page, et le désabonnement en un clic des messageries (RFC 8058).
 */
@Controller
@RequestMapping("/public/notifications/kyc-reminders")
public class KycReminderUnsubscribeController {

    static final String VIEW = "public/kyc-reminders-unsubscribe";
    static final String APP_URL = "https://yadony.com";

    private final KycReminderUnsubscribeTokens tokens;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final MessagesResolver messagesResolver;

    public KycReminderUnsubscribeController(KycReminderUnsubscribeTokens tokens,
                                            UserRepository userRepository,
                                            AuditService auditService,
                                            MessagesResolver messagesResolver) {
        this.tokens = tokens;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.messagesResolver = messagesResolver;
    }

    @GetMapping("/unsubscribe")
    public String confirm(@RequestParam(name = "t", required = false) String token,
                          Model model, HttpServletResponse response) {
        noStore(response);
        Optional<UUID> userId = tokens.verify(token);
        if (userId.isEmpty()) {
            return page(model, "expired", null, messagesResolver.forRequest());
        }
        return page(model, "confirm", token, messagesResolver.forUser(userId.get()));
    }

    @PostMapping("/unsubscribe")
    @Transactional
    public String unsubscribe(@RequestParam(name = "t", required = false) String token,
                              Model model, HttpServletResponse response) {
        noStore(response);
        Optional<UUID> userId = tokens.verify(token);
        if (userId.isEmpty()) {
            return page(model, "expired", null, messagesResolver.forRequest());
        }
        UUID id = userId.get();
        // Ne met à jour que si le refus n'est pas déjà enregistré : la première date est gardée.
        if (userRepository.markKycRemindersOptedOut(id, Instant.now()) > 0) {
            auditService.log("USER", id, "KYC_REMINDERS_UNSUBSCRIBED", id, Map.of());
        }
        return page(model, "done", null, messagesResolver.forUser(userId.get()));
    }

    private String page(Model model, String state, String token, Messages m) {
        String prefix = "kyc-reminders.unsubscribe." + state;
        model.addAttribute("lang", m.language().code());
        model.addAttribute("state", state);
        model.addAttribute("title", m.get(prefix + ".title"));
        model.addAttribute("body", m.get(prefix + ".body"));
        model.addAttribute("token", token);
        model.addAttribute("confirmCta", m.get("kyc-reminders.unsubscribe.confirm.cta"));
        model.addAttribute("appCta", m.get("kyc-reminders.unsubscribe.open-app"));
        model.addAttribute("appUrl", APP_URL);
        return VIEW;
    }

    /** L'adresse porte un jeton : ni cache, ni Referer vers d'autres sites. */
    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
