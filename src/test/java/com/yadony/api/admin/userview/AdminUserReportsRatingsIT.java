package com.yadony.api.admin.userview;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.ratings.RatingEntity;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Signalements et avis d'un utilisateur sur la fiche admin. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminUserReportsRatingsIT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ReportRepository reportRepository;
    @Autowired RatingRepository ratingRepository;
    @Autowired JdbcTemplate jdbc;

    private UserEntity user;
    private UserEntity other;

    @BeforeEach
    void setUp() {
        user = saveUser();
        other = saveUser();
    }

    private UserEntity saveUser() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        return userRepository.save(u);
    }

    private static UsernamePasswordAuthenticationToken authWith(String... permissions) {
        var all = new ArrayList<SimpleGrantedAuthority>();
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) all.add(new SimpleGrantedAuthority(p));
        var principal = new AdminPrincipal(UUID.randomUUID(), "admin@yadony.com", AdminRole.ADMIN, false, "uid");
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    private static UsernamePasswordAuthenticationToken fullAuth() {
        return authWith("USER_VIEW", "REPORT_VIEW", "RATING_MODERATE");
    }

    private ReportEntity report(UserEntity reporter, ReportTargetType type, UUID targetId, ReportStatus status) {
        ReportEntity r = new ReportEntity();
        r.setReporterId(reporter.getId());
        r.setTargetType(type);
        r.setTargetId(targetId);
        r.setReason(ReportReason.OTHER);
        r.setStatus(status);
        return reportRepository.save(r);
    }

    private RatingEntity rating(UserEntity rated, UserEntity rater, int stars) {
        RatingEntity r = new RatingEntity();
        r.setRatedUserId(rated.getId());
        r.setRaterId(rater.getId());
        r.setBidId(UUID.randomUUID());
        r.setStars(stars);
        r.setComment("ok " + stars);
        return ratingRepository.save(r);
    }

    private void setCreatedAt(String table, UUID id, LocalDateTime at) {
        jdbc.update("UPDATE " + table + " SET created_at = ? WHERE id = ?", at, id);
    }

    private ResultActions overview(UserEntity u, UsernamePasswordAuthenticationToken auth) throws Exception {
        return mockMvc.perform(get("/admin/users/{id}/overview", u.getId()).with(authentication(auth)));
    }

    @Test
    void reports_received_onlyDirectUserTargets() throws Exception {
        ReportEntity direct = report(other, ReportTargetType.USER, user.getId(), ReportStatus.OPEN);
        report(other, ReportTargetType.ANNOUNCEMENT, user.getId(), ReportStatus.OPEN);
        report(other, ReportTargetType.USER, other.getId(), ReportStatus.OPEN);

        mockMvc.perform(get("/admin/users/{id}/reports?direction=RECEIVED", user.getId())
                        .with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(direct.getId().toString()))
                .andExpect(jsonPath("$.content[0].direction").value("RECEIVED"))
                .andExpect(jsonPath("$.content[0].targetType").value("USER"))
                .andExpect(jsonPath("$.content[0].reason").value("OTHER"))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"));
        overview(user, fullAuth())
                .andExpect(jsonPath("$.kpis.reportsReceived").value(1));
    }

    @Test
    void reports_sent_byReporter() throws Exception {
        ReportEntity mine = report(user, ReportTargetType.ANNOUNCEMENT, UUID.randomUUID(), ReportStatus.RESOLVED);
        report(other, ReportTargetType.USER, user.getId(), ReportStatus.OPEN);

        mockMvc.perform(get("/admin/users/{id}/reports?direction=SENT", user.getId())
                        .with(authentication(fullAuth())))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(mine.getId().toString()))
                .andExpect(jsonPath("$.content[0].direction").value("SENT"))
                .andExpect(jsonPath("$.content[0].targetType").value("ANNOUNCEMENT"))
                .andExpect(jsonPath("$.content[0].status").value("RESOLVED"));
    }

    @Test
    void reports_invalidDirection_returns400() throws Exception {
        mockMvc.perform(get("/admin/users/{id}/reports?direction=NOPE", user.getId())
                        .with(authentication(fullAuth())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/users/{id}/reports", user.getId()).with(authentication(fullAuth())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reports_isPaginated_newestFirstWithTieBreak() throws Exception {
        LocalDateTime t = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        ReportEntity a = report(other, ReportTargetType.USER, user.getId(), ReportStatus.OPEN);
        ReportEntity b = report(other, ReportTargetType.USER, user.getId(), ReportStatus.OPEN);
        ReportEntity old = report(other, ReportTargetType.USER, user.getId(), ReportStatus.OPEN);
        setCreatedAt("reports", a.getId(), t);
        setCreatedAt("reports", b.getId(), t);
        setCreatedAt("reports", old.getId(), t.minusDays(5));
        // Ordre des identifiants tel que la base le voit (la comparaison Java des UUID peut différer).
        var ids = jdbc.queryForList("SELECT id FROM reports WHERE id IN (?, ?) ORDER BY id DESC",
                UUID.class, a.getId(), b.getId());
        UUID first = ids.get(0);
        UUID second = ids.get(1);

        mockMvc.perform(get("/admin/users/{id}/reports?direction=RECEIVED&page=0&size=2", user.getId())
                        .with(authentication(fullAuth())))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(first.toString()))
                .andExpect(jsonPath("$.content[1].id").value(second.toString()));
        mockMvc.perform(get("/admin/users/{id}/reports?direction=RECEIVED&page=1&size=2", user.getId())
                        .with(authentication(fullAuth())))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(old.getId().toString()));
        mockMvc.perform(get("/admin/users/{id}/reports?direction=RECEIVED&size=100000", user.getId())
                        .with(authentication(fullAuth())))
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void ratings_listsReceivedRatings() throws Exception {
        RatingEntity older = rating(user, other, 4);
        RatingEntity newer = rating(user, saveUser(), 2);
        rating(other, user, 5);
        setCreatedAt("ratings", older.getId(), LocalDateTime.now(ZoneOffset.UTC).minusDays(3));

        mockMvc.perform(get("/admin/users/{id}/ratings", user.getId()).with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(newer.getId().toString()))
                .andExpect(jsonPath("$.content[0].stars").value(2))
                .andExpect(jsonPath("$.content[0].flagged").value(false))
                .andExpect(jsonPath("$.content[0].bidId").value(newer.getBidId().toString()))
                .andExpect(jsonPath("$.content[1].id").value(older.getId().toString()))
                .andExpect(jsonPath("$.content[1].comment").value("ok 4"));
    }

    @Test
    void overview_kpis_reportCounts() throws Exception {
        report(other, ReportTargetType.USER, user.getId(), ReportStatus.OPEN);
        report(other, ReportTargetType.USER, user.getId(), ReportStatus.OPEN);
        report(other, ReportTargetType.USER, user.getId(), ReportStatus.DISMISSED);
        report(user, ReportTargetType.USER, other.getId(), ReportStatus.OPEN);
        report(other, ReportTargetType.ANNOUNCEMENT, user.getId(), ReportStatus.OPEN);

        overview(user, fullAuth())
                .andExpect(jsonPath("$.kpis.reportsReceived").value(3))
                .andExpect(jsonPath("$.kpis.reportsSent").value(1))
                .andExpect(jsonPath("$.kpis.reportsPending").value(2));
        overview(saveUser(), fullAuth())
                .andExpect(jsonPath("$.kpis.reportsReceived").value(0))
                .andExpect(jsonPath("$.kpis.reportsSent").value(0))
                .andExpect(jsonPath("$.kpis.reportsPending").value(0));
    }

    @Test
    void reports_withoutReportView_returns403() throws Exception {
        mockMvc.perform(get("/admin/users/{id}/reports?direction=SENT", user.getId())
                        .with(authentication(authWith("USER_VIEW", "RATING_MODERATE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void ratings_withoutRatingModerate_returns403() throws Exception {
        mockMvc.perform(get("/admin/users/{id}/ratings", user.getId())
                        .with(authentication(authWith("USER_VIEW", "REPORT_VIEW"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void overview_withoutReportView_omitsReportKpis() throws Exception {
        report(other, ReportTargetType.USER, user.getId(), ReportStatus.OPEN);
        overview(user, authWith("USER_VIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.reportsReceived").doesNotExist())
                .andExpect(jsonPath("$.kpis.reportsSent").doesNotExist())
                .andExpect(jsonPath("$.kpis.reportsPending").doesNotExist());
    }

    @Test
    void unknownUser_returns404() throws Exception {
        mockMvc.perform(get("/admin/users/{id}/reports?direction=SENT", UUID.randomUUID())
                        .with(authentication(fullAuth())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/users/{id}/ratings", UUID.randomUUID()).with(authentication(fullAuth())))
                .andExpect(status().isNotFound());
    }
}
