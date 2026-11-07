package com.yadony.api.notifications;

import com.yadony.api.common.i18n.Messages;
import com.yadony.api.common.i18n.MessagesResolver;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;
import java.util.UUID;

/**
 * Lien « Ne plus recevoir ces suggestions » des e-mails de suggestion de trajets (public, sous
 * {@code /public/**} : le jeton signé de l'adresse fait foi, aucun jeton Firebase).
 *
 * <p>{@code GET} n'affiche qu'une confirmation : les antivirus des messageries ouvrent les liens
 * des e-mails, un GET qui coupait le réglage désinscrirait des gens sans qu'ils le sachent.
 * {@code POST} coupe le réglage : c'est le bouton de la page, et le désabonnement en un clic des
 * messageries (en-têtes {@code List-Unsubscribe} et {@code List-Unsubscribe-Post}, RFC 8058),
 * qui postent {@code List-Unsubscribe=One-Click} à la même adresse.
 */
@Controller
@RequestMapping("/public/notifications/search-suggestions")
public class SearchSuggestionUnsubscribeController {

    static final String VIEW = "public/search-suggestions-unsubscribe";
    static final String APP_URL = "https://yadony.com";

    private final SearchSuggestionUnsubscribeTokens tokens;
    private final NotificationPrefsService prefsService;
    private final MessagesResolver messagesResolver;

    public SearchSuggestionUnsubscribeController(SearchSuggestionUnsubscribeTokens tokens,
                                                 NotificationPrefsService prefsService,
                                                 MessagesResolver messagesResolver) {
        this.tokens = tokens;
        this.prefsService = prefsService;
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
    public String unsubscribe(@RequestParam(name = "t", required = false) String token,
                              Model model, HttpServletResponse response) {
        noStore(response);
        Optional<UUID> userId = tokens.verify(token);
        if (userId.isEmpty()) {
            return page(model, "expired", null, messagesResolver.forRequest());
        }
        prefsService.disableSearchSuggestions(userId.get());
        return page(model, "done", null, messagesResolver.forUser(userId.get()));
    }

    private String page(Model model, String state, String token, Messages m) {
        String prefix = "search-suggestions.unsubscribe." + state;
        model.addAttribute("lang", m.language().code());
        model.addAttribute("state", state);
        model.addAttribute("title", m.get(prefix + ".title"));
        model.addAttribute("body", m.get(prefix + ".body"));
        model.addAttribute("token", token);
        model.addAttribute("confirmCta", m.get("search-suggestions.unsubscribe.confirm.cta"));
        model.addAttribute("appCta", m.get("search-suggestions.unsubscribe.open-app"));
        model.addAttribute("appUrl", APP_URL);
        return VIEW;
    }

    /** L'adresse porte un jeton : ni cache, ni Referer vers d'autres sites. */
    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
