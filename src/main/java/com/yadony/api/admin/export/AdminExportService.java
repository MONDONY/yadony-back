package com.yadony.api.admin.export;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Génération des exports CSV du back-office admin.
 * Types : transactions (payments), users, disputes, payouts (fonds de garantie).
 */
@Service
public class AdminExportService {

    /** Borne basse par défaut quand `from` est absent — antérieure au lancement de la plateforme. */
    private static final LocalDate DEFAULT_FROM = LocalDate.of(2020, 1, 1);

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final DisputeRepository disputeRepository;
    private final FirebaseContactService firebaseContact;

    public AdminExportService(PaymentRepository paymentRepository,
                              UserRepository userRepository,
                              DisputeRepository disputeRepository,
                              FirebaseContactService firebaseContact) {
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.disputeRepository = disputeRepository;
        this.firebaseContact = firebaseContact;
    }

    public byte[] exportTransactions(LocalDate from, LocalDate to) {
        List<PaymentEntity> payments = paymentRepository
                .findAllByCreatedAtBetweenOrderByCreatedAtAsc(lower(from), upper(to));
        // Chaque ligne porte sa devise et son rail : les colonnes « montantEur » mêlaient des
        // XOF, des USD et des EUR sous un même nom, et un paiement mobile money n'a pas de
        // PaymentIntent Stripe.
        CsvWriter sb = new CsvWriter("id,bidId,statut,rail,devise,montant,commission,rembourse,stripePaymentIntentId,creeLe,escrowLibereLe");
        for (PaymentEntity p : payments) {
            sb.row(
                str(p.getId()),
                str(p.getBidId()),
                p.getStatus().name(),
                p.getRail() != null ? p.getRail().name() : "",
                p.getCurrency() != null ? p.getCurrency().toUpperCase(java.util.Locale.ROOT) : "",
                money(p.getAmount()),
                money(p.getCommissionAmount()),
                money(p.getRefundedAmount()),
                p.getStripePaymentIntentId(),
                str(p.getCreatedAt()),
                str(p.getEscrowReleasedAt()));
        }
        return sb.bytes();
    }

    public byte[] exportUsers(LocalDate from, LocalDate to) {
        List<UserEntity> users = userRepository
                .findAllByCreatedAtBetweenOrderByCreatedAtAsc(lower(from), upper(to));
        // Coordonnées récupérées en un lot depuis Firebase (source de vérité) plutôt
        // qu'un appel par ligne exportée.
        var contacts = firebaseContact.getContacts(
                users.stream().map(UserEntity::getFirebaseUid).toList());
        CsvWriter sb = new CsvWriter("id,prenom,nom,telephone,email,roles,statut,kyc,pro,ville,creeLe");
        for (UserEntity u : users) {
            var contact = contacts.getOrDefault(
                    u.getFirebaseUid(), FirebaseContactService.Contact.EMPTY);
            sb.row(
                str(u.getId()),
                u.getFirstName(),
                u.getLastName(),
                contact.phoneNumber(),
                contact.email(),
                u.getRoles().stream().map(Role::name).sorted().collect(Collectors.joining("|")),
                u.getStatus().name(),
                u.getKycStatus().name(),
                String.valueOf(u.isProAccount()),
                u.getCity(),
                str(u.getCreatedAt()));
        }
        return sb.bytes();
    }

    public byte[] exportDisputes(LocalDate from, LocalDate to) {
        List<DisputeEntity> disputes = disputeRepository
                .findAllByCreatedAtBetweenOrderByCreatedAtAsc(lower(from), upper(to));
        CsvWriter sb = new CsvWriter("id,bidId,type,statut,resolution,noteResolution,creeLe,resoluLe");
        for (DisputeEntity d : disputes) {
            sb.row(
                str(d.getId()),
                str(d.getBidId()),
                d.getType(),
                d.getStatus(),
                d.getResolutionType(),
                d.getResolutionNote(),
                str(d.getCreatedAt()),
                str(d.getResolvedAt()));
        }
        return sb.bytes();
    }

    /** Versements du fonds de garantie = litiges résolus en GUARANTEE_PAID sur la période. */
    public byte[] exportPayouts(LocalDate from, LocalDate to) {
        List<DisputeEntity> payouts = disputeRepository
                .findAllByResolutionTypeAndResolvedAtBetweenOrderByResolvedAtAsc(
                        "GUARANTEE_PAID", lowerOffset(from), upperOffset(to));
        CsvWriter sb = new CsvWriter("disputeId,bidId,beneficiaireUserId,montantCents,motif,verseLe");
        for (DisputeEntity d : payouts) {
            sb.row(
                str(d.getId()),
                str(d.getBidId()),
                str(d.getBeneficiaryUserId()),
                d.getGuaranteeAmountCents() != null ? String.valueOf(d.getGuaranteeAmountCents()) : "",
                d.getResolutionNote(),
                str(d.getResolvedAt()));
        }
        return sb.bytes();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static LocalDateTime lower(LocalDate from) {
        return (from != null ? from : DEFAULT_FROM).atStartOfDay();
    }

    private static LocalDateTime upper(LocalDate to) {
        return to != null ? to.plusDays(1).atStartOfDay() : LocalDateTime.now(ZoneOffset.UTC);
    }

    private static OffsetDateTime lowerOffset(LocalDate from) {
        return lower(from).atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime upperOffset(LocalDate to) {
        return upper(to).atOffset(ZoneOffset.UTC);
    }

    private static String str(Object value) {
        return value != null ? value.toString() : "";
    }

    private static String money(BigDecimal value) {
        return value != null ? value.toPlainString() : "";
    }
}
