package com.yadony.api.payments;

import com.yadony.api.common.i18n.Messages;
import com.yadony.api.common.i18n.MessagesResolver;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;
import java.util.UUID;

/**
 * Bouton « Terminer mon inscription » de l'e-mail de relance Stripe Connect (public, sans
 * jeton Firebase : le jeton signé de l'adresse fait foi).
 *
 * <p>{@code GET /payments/onboarding/resume?t=…} crée un lien Stripe neuf et y redirige.
 * Ce même lien sert de {@code refresh_url} : si le voyageur laisse le formulaire Stripe
 * ouvert trop longtemps, Stripe le renvoie ici et il repart avec un lien frais.
 * {@code GET /payments/onboarding/resume/done} est le {@code return_url} : une page qui
 * marche aussi sur ordinateur, là où le deep link de l'app ne mène nulle part.
 */
@Controller
@RequestMapping("/payments/onboarding")
public class StripeOnboardingResumeController {

    static final String VIEW = "payments/onboarding-resume";
    /** Même destination que le bouton par défaut des e-mails : le site mène à l'application. */
    static final String APP_URL = "https://yadony.com";

    private final StripeOnboardingResumeTokens tokens;
    private final StripeOnboardingResumeService resumeService;
    private final StripeOnboardingResumeEmailLink links;
    private final MessagesResolver messagesResolver;

    public StripeOnboardingResumeController(StripeOnboardingResumeTokens tokens,
                                            StripeOnboardingResumeService resumeService,
                                            StripeOnboardingResumeEmailLink links,
                                            MessagesResolver messagesResolver) {
        this.tokens = tokens;
        this.resumeService = resumeService;
        this.links = links;
        this.messagesResolver = messagesResolver;
    }

    @GetMapping("/resume")
    public String resume(@RequestParam(name = "t", required = false) String token,
                         Model model, HttpServletResponse response) {
        noStore(response);
        Optional<UUID> userId = tokens.verify(token);
        if (userId.isEmpty()) {
            return page(model, "expired", messagesResolver.forRequest());
        }
        Messages m = messagesResolver.forUser(userId.get());
        StripeOnboardingResumeService.Outcome outcome =
                resumeService.resume(userId.get(), links.resumeUrl(token), links.doneUrl());
        return switch (outcome) {
            case StripeOnboardingResumeService.Outcome.Redirect r -> "redirect:" + r.url();
            case StripeOnboardingResumeService.Outcome.AlreadyComplete c -> page(model, "complete", m);
            case StripeOnboardingResumeService.Outcome.OpenApp o -> page(model, "open-app", m);
        };
    }

    @GetMapping("/resume/done")
    public String done(Model model, HttpServletResponse response) {
        noStore(response);
        return page(model, "done", messagesResolver.forRequest());
    }

    private String page(Model model, String state, Messages m) {
        String prefix = "stripe.resume." + state;
        model.addAttribute("lang", m.language().code());
        model.addAttribute("state", state);
        model.addAttribute("title", m.get(prefix + ".title"));
        model.addAttribute("body", m.get(prefix + ".body"));
        model.addAttribute("cta", m.get("stripe.resume.cta"));
        model.addAttribute("appUrl", APP_URL);
        return VIEW;
    }

    /** L'adresse porte un jeton : ni cache, ni Referer vers d'autres sites. */
    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
