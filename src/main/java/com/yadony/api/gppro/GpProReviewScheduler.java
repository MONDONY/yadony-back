package com.yadony.api.gppro;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Revue quotidienne des titulaires GP Pro : crée un signalement par motif actif, et clôt
 * ceux dont la condition a cessé. Un signalement écarté mais non clos bloque la recréation
 * (index unique partiel) : le motif ne réapparaît qu'après avoir cessé puis repris.
 */
@Component
public class GpProReviewScheduler {

    private static final Logger log = LoggerFactory.getLogger(GpProReviewScheduler.class);

    private final UserRepository users;
    private final GpProReviewFlagRepository flags;
    private final GpProReviewEvaluator evaluator;
    private final GpProProperties properties;

    public GpProReviewScheduler(UserRepository users, GpProReviewFlagRepository flags,
                                GpProReviewEvaluator evaluator, GpProProperties properties) {
        this.users = users;
        this.flags = flags;
        this.evaluator = evaluator;
        this.properties = properties;
    }

    @Scheduled(cron = "${yadony.gp-pro.review-cron:0 15 4 * * *}", zone = "UTC")
    @Transactional
    public void run() {
        if (!properties.schedulerEnabledOrDefault()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int created = 0;
        int resolved = 0;
        for (UserEntity user : users.findByGpProTrue()) {
            Set<GpProReviewReason> active = evaluator.reasonsFor(user, now.toLocalDate());
            List<GpProReviewFlagEntity> open = flags.findByUserIdAndResolvedAtIsNull(user.getId());
            List<GpProReviewFlagEntity> toSave = new ArrayList<>();
            for (GpProReviewFlagEntity flag : open) {
                if (!active.contains(flag.getReason())) {
                    flag.setResolvedAt(now);
                    toSave.add(flag);
                    resolved++;
                }
            }
            for (GpProReviewReason reason : active) {
                if (open.stream().noneMatch(f -> f.getReason() == reason)) {
                    GpProReviewFlagEntity flag = new GpProReviewFlagEntity();
                    flag.setUserId(user.getId());
                    flag.setReason(reason);
                    flag.setDetectedAt(now);
                    toSave.add(flag);
                    created++;
                }
            }
            if (!toSave.isEmpty()) {
                flags.saveAll(toSave);
            }
        }
        if (created > 0 || resolved > 0) {
            log.info("GP Pro review: {} flags created, {} resolved", created, resolved);
        }
    }
}
