package com.yadony.api.matching;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.KgSoldDetailsDto;
import com.yadony.api.matching.dto.RevenueDetailsDto;
import com.yadony.api.matching.dto.TripsSummaryDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/travelers")
public class TripsSummaryController {

    private final TripsSummaryService tripsSummaryService;
    private final UserRepository userRepository;

    public TripsSummaryController(
            TripsSummaryService tripsSummaryService,
            UserRepository userRepository) {
        this.tripsSummaryService = tripsSummaryService;
        this.userRepository = userRepository;
    }

    /**
     * Résumé d'activité voyageur (statistiques du hub Activités).
     * Contrairement à /me/stats, accessible à tout voyageur (pas de gate Pro).
     *
     * @param period {@code 7d}, {@code 30d} (défaut) ou {@code 12m}. Une valeur
     *               inconnue retombe silencieusement sur le défaut plutôt que
     *               de renvoyer une erreur.
     */
    @GetMapping("/me/trips-summary")
    public ResponseEntity<TripsSummaryDto> getMyTripsSummary(
            @RequestParam(required = false) String period) {
        UserEntity user = requireTraveler();
        return ResponseEntity.ok(
                tripsSummaryService.computeSummary(user, StatsPeriod.fromApiValue(period)));
    }

    /** Feuille « Revenus » : chaque livraison dans la devise de son paiement, par devise. */
    @GetMapping("/me/trips-summary/revenues")
    public ResponseEntity<RevenueDetailsDto> getMyRevenueDetails(
            @RequestParam(required = false) String period) {
        UserEntity user = requireTraveler();
        return ResponseEntity.ok(
                tripsSummaryService.computeRevenueDetails(user, StatsPeriod.fromApiValue(period)));
    }

    /** Feuille « Kg vendus » : le poids livré trajet par trajet. */
    @GetMapping("/me/trips-summary/kg-sold")
    public ResponseEntity<KgSoldDetailsDto> getMyKgSold(
            @RequestParam(required = false) String period) {
        UserEntity user = requireTraveler();
        return ResponseEntity.ok(
                tripsSummaryService.computeKgSold(user, StatsPeriod.fromApiValue(period)));
    }

    /** Session Firebase valide, compte connu, rôle voyageur : la même garde pour les trois routes. */
    private UserEntity requireTraveler() {
        String firebaseUid = requireFirebaseUid();

        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found",
                        "User Not Found", "Utilisateur introuvable"));

        if (!user.getRoles().contains(Role.TRAVELER)) {
            throw new YadonyBusinessException(
                    HttpStatus.FORBIDDEN, "traveler-required",
                    "Traveler role required",
                    "Réservé aux voyageurs.");
        }
        return user;
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new YadonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthenticated",
                    "Unauthenticated", "Authentification requise");
        }
        return auth.getName();
    }
}
