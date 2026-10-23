package com.yadony.api.admin.userview;

import com.yadony.api.admin.userview.dto.AdminUserOverviewResponse;
import com.yadony.api.admin.userview.dto.AdminUserOverviewResponse.Kpis;
import com.yadony.api.auth.UserEntity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Assemble la synthèse d'un utilisateur ; chaque domaine n'est calculé que si l'appelant y a droit. */
@Service
public class AdminUserOverviewService {

    /** Un colis livré reste « en cours » dans la synthèse pendant cette fenêtre (UTC). */
    public static final Duration RECENT_DELIVERY_WINDOW = Duration.ofDays(3);

    public AdminUserOverviewResponse overview(UserEntity user, Set<String> authorities) {
        boolean bids = authorities.contains("BID_VIEW");
        boolean payments = authorities.contains("PAYMENT_VIEW");
        boolean disputes = authorities.contains("DISPUTE_VIEW");
        boolean reports = authorities.contains("REPORT_VIEW");

        Kpis kpis = new Kpis(
                bids ? 0 : null,
                bids ? 0 : null,
                payments ? List.of() : null,
                disputes ? 0 : null,
                disputes ? 0 : null,
                reports ? 0 : null,
                reports ? 0 : null,
                reports ? 0 : null,
                user.getAverageRating() == null ? null : user.getAverageRating().doubleValue(),
                user.getRatingCount());
        return new AdminUserOverviewResponse(kpis, List.of(), List.of());
    }
}
