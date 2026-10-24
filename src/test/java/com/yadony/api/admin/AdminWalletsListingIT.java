package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.dto.AdminWalletResponse;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Liste des portefeuilles de l'admin ({@link AdminFinanceListings#wallets}) : nom du titulaire, recherche repliée,
 * devise, signe du solde, titulaire, compte supprimé. Chaque test pose ses portefeuilles sous une étiquette
 * (lettres seules : un chiffre pourrait correspondre par hasard à un jeton numérique) et filtre dessus.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminWalletsListingIT {

    @Autowired AdminFinanceListings listings;
    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private int minutes;

    @BeforeEach
    void setUp() {
        tag = "wl" + letters(10);
        minutes = 0;
    }

    @Test
    void userName_prenomNomEtPseudo_plusRecentsDAbord() {
        UserEntity awa = persistUser(tag, "Diallo", "awa" + tag);
        UserEntity moussa = persistUser(tag, "Koné", "moussa" + tag);
        wallet(awa.getId(), "EUR", "10.00");
        wallet(moussa.getId(), "XOF", "2500.00");

        List<AdminWalletResponse> rows = wallets(tag, null, null, null);

        // Le dernier posé d'abord (updated_at DESC).
        assertThat(rows).extracting(AdminWalletResponse::userId).containsExactly(moussa.getId(), awa.getId());
        assertThat(rows.get(0).userName()).isEqualTo(tag + " Koné (@moussa" + tag + ")");
        assertThat(rows.get(0).balanceCents()).isEqualTo(250000);
        assertThat(rows.get(1).userName()).isEqualTo(tag + " Diallo (@awa" + tag + ")");
    }

    @Test
    void q_nomPrenomPseudoUuid_accentsRepliesEtJetonsEnEt() {
        UserEntity u = persistUser("Aïssatou" + tag, "N'Diaye", "ndiaye" + tag);
        UserEntity other = persistUser("Aïssatou" + tag, "Sow", "sow" + tag);
        wallet(u.getId(), "EUR", "1.00");
        wallet(other.getId(), "EUR", "1.00");

        assertThat(ids("aissatou" + tag)).containsExactlyInAnyOrder(u.getId(), other.getId());
        assertThat(ids("AÏSSATOU" + tag + " ndiaye")).containsExactly(u.getId());
        assertThat(ids("@ndiaye" + tag)).containsExactly(u.getId());
        assertThat(ids("sow" + tag)).containsExactly(other.getId());
        assertThat(ids(u.getId().toString().toUpperCase())).containsExactly(u.getId());
        assertThat(ids("zzz" + tag)).isEmpty();
    }

    @Test
    void q_caracteresSpeciauxSeuls_neTrouventRien() {
        UserEntity u = persistUser(tag, "Diallo", "d" + tag);
        wallet(u.getId(), "EUR", "1.00");
        for (String q : List.of("%", "_", "'", "@", "%%")) {
            assertThat(listings.wallets(q, null, null, null, PageRequest.of(0, 50)).getContent())
                    .as("q=%s", q).extracting(AdminWalletResponse::userId).doesNotContain(u.getId());
            assertThat(listings.wallets(q, null, null, null, PageRequest.of(0, 50)).getTotalElements())
                    .as("q=%s", q).isZero();
        }
    }

    @Test
    void currency_insensibleALaCasse() {
        UserEntity u = persistUser(tag, "Diallo", "d" + tag);
        UUID eur = wallet(u.getId(), "EUR", "1.00");
        UUID xof = wallet(u.getId(), "XOF", "1000.00");

        assertThat(wallets(tag, "XOF", null, null)).extracting(AdminWalletResponse::id).containsExactly(xof);
        assertThat(wallets(tag, "eur", null, null)).extracting(AdminWalletResponse::id).containsExactly(eur);
        assertThat(wallets(tag, null, null, null)).hasSize(2);
    }

    @Test
    void balanceSign_positifNulNegatif() {
        UserEntity p = persistUser(tag, "Pos", "p" + tag);
        UserEntity z = persistUser(tag, "Zero", "z" + tag);
        UserEntity n = persistUser(tag, "Neg", "n" + tag);
        wallet(p.getId(), "EUR", "5.00");
        wallet(z.getId(), "EUR", "0.00");
        boolean negativeAllowed = !isPostgres();
        if (negativeAllowed) {
            // Postgres porte CHECK (balance >= 0) : le solde négatif n'existe qu'en H2.
            wallet(n.getId(), "EUR", "-3.00");
        }

        assertThat(wallets(tag, null, AdminWalletBalanceSign.POSITIVE, null))
                .extracting(AdminWalletResponse::userId).containsExactly(p.getId());
        assertThat(wallets(tag, null, AdminWalletBalanceSign.ZERO, null))
                .extracting(AdminWalletResponse::userId).containsExactly(z.getId());
        assertThat(wallets(tag, null, AdminWalletBalanceSign.NEGATIVE, null))
                .extracting(AdminWalletResponse::userId)
                .containsExactlyElementsOf(negativeAllowed ? List.of(n.getId()) : List.of());
        assertThat(wallets(tag, null, null, null)).hasSize(negativeAllowed ? 3 : 2);
    }

    @Test
    void partyId_titulaireSeul() {
        UserEntity a = persistUser(tag, "Aa", "a" + tag);
        UserEntity b = persistUser(tag, "Bb", "b" + tag);
        UUID wa = wallet(a.getId(), "EUR", "1.00");
        wallet(b.getId(), "EUR", "1.00");

        assertThat(wallets(null, null, null, a.getId())).extracting(AdminWalletResponse::id).containsExactly(wa);
        assertThat(wallets(null, null, null, UUID.randomUUID())).isEmpty();
    }

    @Test
    void compteSupprime_portefeuilleListeSansNom_etIntrouvableParLeNom() {
        UserEntity gone = persistUser(tag, "Parti", "parti" + tag);
        UUID w = wallet(gone.getId(), "EUR", "7.00");
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", gone.getId());

        List<AdminWalletResponse> rows = wallets(null, null, null, gone.getId());
        assertThat(rows).extracting(AdminWalletResponse::id).containsExactly(w);
        assertThat(rows.get(0).userName()).isNull();
        // Le portefeuille supprimé lui-même (deleted_at) ne l'est pas.
        jdbc.update("UPDATE wallet_accounts SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", w);
        assertThat(wallets(null, null, null, gone.getId())).isEmpty();
    }

    @Test
    void pagination_totalJuste_etJsonSansNomAbsent() throws Exception {
        UserEntity a = persistUser(tag, "Aa", "a" + tag);
        UserEntity b = persistUser(tag, "Bb", "b" + tag);
        wallet(a.getId(), "EUR", "1.00");
        wallet(b.getId(), "EUR", "2.00");
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", b.getId());

        var page = listings.wallets(null, null, null, a.getId(), PageRequest.of(0, 1));
        assertThat(page.getTotalElements()).isEqualTo(1);

        mockMvc.perform(get("/admin/wallets").param("q", tag).param("balanceSign", "POSITIVE")
                        .with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].userName").value(tag + " Aa (@a" + tag + ")"));
        mockMvc.perform(get("/admin/wallets").param("partyId", b.getId().toString()).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].userId").value(b.getId().toString()))
                .andExpect(jsonPath("$.content[0].userName").doesNotExist());
        mockMvc.perform(get("/admin/wallets").param("balanceSign", "BIZARRE").with(authentication(admin())))
                .andExpect(status().isBadRequest());
    }

    // ── Aides ───────────────────────────────────────────────────────────────────

    private static UsernamePasswordAuthenticationToken admin() {
        AdminPrincipal principal = new AdminPrincipal(UUID.randomUUID(), "support@yadony.test", AdminRole.SUPPORT,
                false, "uid-support-wallets");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("PAYMENT_VIEW")));
    }

    private List<AdminWalletResponse> wallets(String q, String currency, AdminWalletBalanceSign sign, UUID partyId) {
        return listings.wallets(q, currency, sign, partyId, PageRequest.of(0, 100)).getContent();
    }

    private List<UUID> ids(String q) {
        return wallets(q, null, null, null).stream().map(AdminWalletResponse::userId).toList();
    }

    private boolean isPostgres() {
        return jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName())
                .toLowerCase().contains("postgres");
    }

    private UUID wallet(UUID userId, String currency, String balance) {
        UUID id = UUID.randomUUID();
        LocalDateTime at = LocalDateTime.of(2026, 10, 20, 8, 0).plusMinutes(minutes++);
        jdbc.update("INSERT INTO wallet_accounts (id, user_id, balance, currency, refund_eligible_amount, created_at, "
                + "updated_at) VALUES (?, ?, CAST(? AS DECIMAL(10,2)), ?, 0, ?, ?)",
                id, userId, balance, currency, Timestamp.valueOf(at), Timestamp.valueOf(at));
        return id;
    }

    private UserEntity persistUser(String firstName, String lastName, String username) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setUsername(username);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(new java.util.HashSet<>(Set.of(Role.SENDER)));
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    /** Lettres seules, minuscules. */
    private static String letters(int length) {
        StringBuilder out = new StringBuilder(length);
        for (char c : UUID.randomUUID().toString().replace("-", "").substring(0, length).toCharArray()) {
            out.append((char) ('g' + Character.digit(c, 16)));
        }
        return out.toString();
    }
}
