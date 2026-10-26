package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminAlertContextResponse;
import com.yadony.api.admin.dto.AdminAlertContextResponse.RelatedAlert;
import com.yadony.api.admin.dto.AdminAlertResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contexte d'une alerte pour le poste de traitement (consultation seule). */
@Service
public class AlertContextService {

    /** Autres alertes montrées au plus. */
    static final int RELATED_LIMIT = 10;
    static final String ENTITY_TYPE = "ADMIN_ALERT";

    private final AdminAlertRepository alertRepo;
    private final ReportContextService reportContext;

    public AlertContextService(AdminAlertRepository alertRepo, ReportContextService reportContext) {
        this.alertRepo = alertRepo;
        this.reportContext = reportContext;
    }

    @Transactional(readOnly = true)
    public AdminAlertContextResponse build(AdminAlertEntity alert) {
        return new AdminAlertContextResponse(related(alert), reportContext.history(ENTITY_TYPE, alert.getId()));
    }

    /**
     * Autres alertes (ouvertes ou résolues) qui citent le même paiement ou le même colis, les plus
     * récentes d'abord. Les identifiants viennent de la levée de l'alerte, jamais d'une saisie.
     */
    private List<RelatedAlert> related(AdminAlertEntity alert) {
        AdminAlertResponse self = AdminAlertResponse.from(alert);
        List<UUID> keys = new ArrayList<>();
        if (self.paymentId() != null) {
            keys.add(self.paymentId());
        }
        UUID bidId = uuid(self.payload().get("bidId"));
        if (bidId != null && !keys.contains(bidId)) {
            keys.add(bidId);
        }
        Map<UUID, AdminAlertEntity> found = new LinkedHashMap<>();
        for (UUID key : keys) {
            for (AdminAlertEntity other : alertRepo.findReferencing("%" + key + "%", alert.getId(), RELATED_LIMIT)) {
                found.putIfAbsent(other.getId(), other);
            }
        }
        return found.values().stream()
                .sorted(Comparator.comparing(AdminAlertEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(RELATED_LIMIT)
                .map(o -> new RelatedAlert(o.getId(), o.getType(), o.getSeverity(), o.isResolved(),
                        o.getCreatedAt(), o.getResolvedAt()))
                .toList();
    }

    private static UUID uuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
