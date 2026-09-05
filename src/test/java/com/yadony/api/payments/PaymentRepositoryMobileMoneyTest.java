package com.yadony.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PaymentRepositoryMobileMoneyTest {

    @Autowired private PaymentRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private PaymentEntity pawapayPayment(PaymentStatus status) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        p.setRail(PaymentRail.PAWAPAY);
        p.setStripePaymentIntentId(null);
        p.setAmount(new BigDecimal("15000"));
        p.setCommissionAmount(new BigDecimal("1800"));
        p.setCurrency("XOF");
        p.setStatus(status);
        return repository.saveAndFlush(p);
    }

    @Test
    void pawapayPayment_persistsWithoutPaymentIntent_andDefaultsRailToStripe() {
        PaymentEntity p = pawapayPayment(PaymentStatus.PENDING);
        assertThat(repository.findById(p.getId())).get()
                .satisfies(saved -> {
                    assertThat(saved.getRail()).isEqualTo(PaymentRail.PAWAPAY);
                    assertThat(saved.getStripePaymentIntentId()).isNull();
                });
        assertThat(new PaymentEntity().getRail()).isEqualTo(PaymentRail.STRIPE);
    }

    @Test
    void markEscrowIfPending_movesOnce_andRecordsDepositId() {
        PaymentEntity p = pawapayPayment(PaymentStatus.PENDING);
        UUID opId = UUID.randomUUID();
        assertThat(repository.markEscrowIfPending(p.getId(), opId, Instant.now())).isEqualTo(1);
        assertThat(repository.markEscrowIfPending(p.getId(), UUID.randomUUID(), Instant.now())).isZero();
        String status = jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, p.getId());
        UUID stored = jdbc.queryForObject("SELECT pawapay_deposit_id FROM payments WHERE id = ?", UUID.class, p.getId());
        assertThat(status).isEqualTo("ESCROW");
        assertThat(stored).isEqualTo(opId);
    }

    @Test
    void markCancelledIfPending_onlyFromPending() {
        PaymentEntity pending = pawapayPayment(PaymentStatus.PENDING);
        PaymentEntity escrow = pawapayPayment(PaymentStatus.ESCROW);
        assertThat(repository.markCancelledIfPending(pending.getId())).isEqualTo(1);
        assertThat(repository.markCancelledIfPending(escrow.getId())).isZero();
    }

    // ── Ronde 1, point 1 : le claim atomique markReleasedIfEscrow ne doit JAMAIS être suivi
    // d'une mutation de l'entité gérée chargée en amont (voir MobileMoneyPayoutInitiator) ────

    /**
     * Reproduit EXACTEMENT le défaut trouvé en revue : {@code p} est l'entité gérée renvoyée par
     * {@code saveAndFlush} (snapshot Hibernate {@code status = ESCROW}). {@code markReleasedIfEscrow}
     * est un bulk JPQL {@code @Modifying} SANS {@code clearAutomatically} : la base passe
     * {@code RELEASED}, mais {@code p} reste {@code ESCROW} en mémoire. Un setter sur ce {@code p}
     * après coup le rend sale ; au flush, Hibernate (pas de {@code @DynamicUpdate} sur
     * {@code PaymentEntity}) régénère un UPDATE de TOUTES les colonnes avec les valeurs en
     * mémoire — {@code status = 'ESCROW'} écrase silencieusement le {@code RELEASED} qui vient
     * d'être posé. Constaté rouge (voir task-16-report.md, section Ronde 1) avec
     * {@code p.setPawapayPayoutId(opId)} à la place de l'appel ci-dessous ; corrigé en
     * remplaçant cette mutation par {@link PaymentRepository#attachPayoutId}, qui n'écrit QUE la
     * colonne visée et ne touche jamais l'état Java de l'entité.
     */
    @Test
    void markReleasedIfEscrow_thenAttachPayoutId_doesNotRevertStatus() {
        PaymentEntity p = pawapayPayment(PaymentStatus.ESCROW);
        UUID opId = UUID.randomUUID();

        assertThat(repository.markReleasedIfEscrow(p.getId(), LocalDateTime.now(ZoneOffset.UTC))).isEqualTo(1);
        // Constaté rouge (voir task-16-report.md, Ronde 1) avec p.setPawapayPayoutId(opId) à la
        // place de la ligne ci-dessous : "expected RELEASED but was ESCROW" — le setter sur
        // l'entité gérée redevenait sale et écrasait le RELEASED au flush. L'UPDATE ciblé ne
        // touche jamais l'état Java de l'entité : rien à re-flusher.
        repository.attachPayoutId(p.getId(), opId);
        repository.flush();

        String status = jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, p.getId());
        UUID stored = jdbc.queryForObject("SELECT pawapay_payout_id FROM payments WHERE id = ?", UUID.class, p.getId());
        assertThat(status).isEqualTo("RELEASED");
        assertThat(stored).isEqualTo(opId);
    }

    // ── Tâche 17 : même piège, symétrique côté remboursement ────────────────────────────────

    /**
     * Reproduit EXACTEMENT le défaut de {@link #markReleasedIfEscrow_thenAttachPayoutId_doesNotRevertStatus}
     * pour le remboursement : {@code markRefundedIfEscrow} est le même genre de bulk JPQL
     * {@code @Modifying} SANS {@code clearAutomatically} — la base passe {@code REFUNDED}, mais
     * l'entité {@code p} chargée en amont par {@code saveAndFlush} garde son snapshot
     * {@code ESCROW} en mémoire. {@code p.setPawapayRefundId(opId)} à la place de l'appel
     * ci-dessous ferait retomber ce test rouge — corrigé par {@link PaymentRepository#attachRefundId},
     * qui n'écrit QUE la colonne visée.
     *
     * <p><b>Ronde 1 (revue) — deux reproductions empiriques, résultats opposés.</b> Point 1 de
     * la revue affirmait qu'un {@code payment.setStatus(REFUNDED)} intercalé ENTRE le claim et
     * {@code attachRefundId} (l'ordre exact que {@code RefundProcessor} utilisait) romprait ce
     * test au flush final. Vérifié empiriquement dans les deux ordres :
     * <ul>
     *   <li>{@code setStatus} PUIS {@code attachRefundId} (ordre réellement utilisé par
     *       {@code RefundProcessor} avant correction) : **test resté vert**. Hibernate déclenche
     *       son propre auto-flush AVANT d'exécuter {@code attachRefundId} — dont l'espace de
     *       requête ({@code payments}) recoupe l'entité sale — ce qui écrit {@code status}
     *       (valeur en mémoire, identique à celle du claim, donc sans dégât) AVANT que
     *       {@code attachRefundId} ne pose la bonne valeur de {@code pawapay_refund_id} juste
     *       après ; rien ne la re-déloge ensuite. Le {@code flushAutomatically=false} de Spring
     *       Data ne supprime que le flush EXPLICITE que Spring ajouterait lui-même — il ne
     *       désactive pas l'auto-flush interne d'Hibernate déclenché par le recoupement
     *       d'espace de requête.</li>
     *   <li>{@code attachRefundId} PUIS {@code setStatus} (ordre inverse, qui aurait pu résulter
     *       d'un réordonnancement futur — exactement le risque que la règle « jamais d'écriture
     *       sur l'entité après un claim bulk » entend prévenir) : **rouge, reproduit à
     *       l'identique** — {@code expected: <uuid> but was: null}. Aucune requête ne recoupe
     *       plus l'espace {@code payments} après {@code setStatus}, rien ne déclenche
     *       l'auto-flush avant le flush explicite final, qui régénère alors un UPDATE de toutes
     *       les colonnes et écrase {@code pawapay_refund_id} avec la valeur en mémoire
     *       ({@code null}, jamais posée par un setter).</li>
     * </ul>
     * Conclusion retenue (voir task-17-report.md, section Ronde 1, pour le détail et le résultat
     * exact du second cas) : le mécanisme précis dépend d'un ordre d'exécution que rien ne
     * garantit dans la durée (un futur réordonnancement — exactement ce que fait la Ronde 1 pour
     * l'audit, point 2 — suffirait à faire basculer le premier cas dans le second). La correction
     * appliquée (suppression de tout {@code setStatus} après le claim dans
     * {@code RefundProcessor}) élimine la dépendance à cet ordre plutôt que de s'y fier.
     */
    @Test
    void markRefundedIfEscrow_thenAttachRefundId_doesNotRevertStatus() {
        PaymentEntity p = pawapayPayment(PaymentStatus.ESCROW);
        UUID opId = UUID.randomUUID();

        assertThat(repository.markRefundedIfEscrow(p.getId())).isEqualTo(1);
        // Constaté rouge avec p.setPawapayRefundId(opId) à la place de la ligne ci-dessous.
        // L'UPDATE ciblé ne touche jamais l'état Java de l'entité : rien à re-flusher.
        repository.attachRefundId(p.getId(), opId);
        repository.flush();

        String status = jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, p.getId());
        UUID stored = jdbc.queryForObject("SELECT pawapay_refund_id FROM payments WHERE id = ?", UUID.class, p.getId());
        assertThat(status).isEqualTo("REFUNDED");
        assertThat(stored).isEqualTo(opId);
    }

    // ── Tâche 18, Ronde 1, point 1 : entityManager.refresh remplace la liste de setters
    // (devenue incomplète — escrowReleasedAt notamment) dans AdminPaymentController ────────────

    /**
     * Preuve du correctif retenu à la tâche 18 (Ronde 1, point 1) : après le claim {@code
     * markReleasedIfEscrow} et l'attachement {@code attachPayoutId} — les deux mêmes écritures
     * ciblées que {@link #markReleasedIfEscrow_thenAttachPayoutId_doesNotRevertStatus} ci-dessus
     * — {@code entityManager.refresh(p)} relit la ligne réelle DANS la même transaction (elle y
     * voit ses propres écritures non commitées) et rend l'entité {@code p} à nouveau PROPRE :
     * Hibernate n'a plus rien à réécrire pour elle. Contrairement à une liste de
     * {@code p.setXxx(...)} — qui salit l'entité et ne protège que les colonnes explicitement
     * listées (piège reproduit une deuxième fois y compris APRÈS la correction de la tâche 17,
     * cette fois sur {@code escrowReleasedAt} dans {@code AdminPaymentController}, avec un écart
     * d'un aller-retour HTTP pawaPay entier, pas de quelques millisecondes) — un refresh couvre
     * TOUTES les colonnes, présentes et futures, sans liste à tenir à jour. La preuve porte sur
     * {@code Statistics#getEntityUpdateCount()} : aucun UPDATE supplémentaire n'est généré au
     * flush qui suit le refresh, alors qu'un simple setter en aurait régénéré un (voir les deux
     * tests ci-dessus, dont la note démontre qu'un setter EST parfois absorbé sans dégât selon un
     * ordre non garanti — ici, aucun UPDATE du tout, quel que soit cet ordre).
     */
    @Test
    void markReleasedIfEscrow_thenAttachPayoutId_thenRefresh_generatesNoUpdate() {
        PaymentEntity p = pawapayPayment(PaymentStatus.ESCROW);
        UUID opId = UUID.randomUUID();

        assertThat(repository.markReleasedIfEscrow(p.getId(), LocalDateTime.now(ZoneOffset.UTC))).isEqualTo(1);
        repository.attachPayoutId(p.getId(), opId);

        Statistics stats = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        long updatesBeforeRefresh = stats.getEntityUpdateCount();

        entityManager.refresh(p);
        repository.flush(); // matérialise toute écriture Hibernate encore en attente sur l'entité

        assertThat(stats.getEntityUpdateCount())
                .as("refresh doit rendre l'entité propre : aucun UPDATE ne doit être régénéré au flush qui suit")
                .isEqualTo(updatesBeforeRefresh);
        // Et la relecture reflète bien les DEUX écritures ciblées — status ET pawapayPayoutId,
        // dans le même geste, sans setter.
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(p.getPawapayPayoutId()).isEqualTo(opId);
    }

    @Test
    void rawInsertWithoutRail_stillWorks_thanksToColumnDefault() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, bid_id, stripe_payment_intent_id, amount, currency, "
                + "commission_amount, status, legacy_destination_charge, disputed, created_at, updated_at) "
                + "VALUES (?, ?, ?, 30.00, 'EUR', 3.60, 'PENDING', false, false, NOW(), NOW())",
                id, UUID.randomUUID(), "pi_" + id);
        String rail = jdbc.queryForObject("SELECT rail FROM payments WHERE id = ?", String.class, id);
        assertThat(rail).isEqualTo("STRIPE");
    }
}
