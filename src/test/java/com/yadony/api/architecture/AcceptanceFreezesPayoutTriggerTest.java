package com.yadony.api.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou : aucun colis ne passe à ACCEPTED sans que sa règle de versement soit figée
 * ({@code PayoutTriggerPolicy.freeze}, {@code bids.payout_trigger}, V383).
 *
 * <p>Un chemin d'acceptation qui l'oublierait laisserait un colis bateau payé à la livraison sans
 * le dire (ou l'inverse si la règle venait d'ailleurs). Ce test lit les sources de
 * {@code src/main/java} (pas d'ArchUnit dans le projet, même méthode que
 * {@code PublicDisplayNameCallersTest}) et impose :
 * <ol>
 *   <li>chaque {@code x.setStatus(BidStatus.ACCEPTED)} est précédé, sur la ligne de code juste
 *       au-dessus, de {@code payoutTriggerPolicy.freeze(x, …)} sur le même colis ;</li>
 *   <li>aucune mise à jour JPQL ou SQL des colis n'écrit {@code status = ACCEPTED}, ni un statut
 *       venu d'un paramètre (qui pourrait valoir ACCEPTED) ;</li>
 *   <li>aucun colis ne reçoit un statut variable ({@code bid.setStatus(next)}), qui masquerait un
 *       passage à ACCEPTED à la lecture ;</li>
 *   <li>la liste des fichiers qui acceptent un colis est celle que couvre
 *       {@code PayoutTriggerFrozenIT} : un nouveau chemin doit y être ajouté, et testé.</li>
 * </ol>
 * Lignes de commentaire ignorées.
 */
@DisplayName("Toute acceptation de colis fige la règle de versement")
class AcceptanceFreezesPayoutTriggerTest {

    private static final Path MAIN = Path.of("src/main/java/com/yadony/api");

    /** {@code x.setStatus(BidStatus.ACCEPTED)}, nom qualifié compris ; groupe 1 = le colis. */
    private static final Pattern SET_ACCEPTED = Pattern.compile(
            "([\\w.()]+)\\.setStatus\\(\\s*(?:com\\.yadony\\.api\\.matching\\.)?BidStatus\\.ACCEPTED\\s*\\)");

    /** {@code setStatus(ACCEPTED)} après un import statique de BidStatus. */
    private static final Pattern SET_ACCEPTED_STATIC = Pattern.compile(
            "([\\w.()]+)\\.setStatus\\(\\s*ACCEPTED\\s*\\)");

    /** {@code payoutTriggerPolicy.freeze(x, …)} ; groupe 1 = le colis. */
    private static final Pattern FREEZE = Pattern.compile(
            "\\bpayoutTriggerPolicy\\.freeze\\(\\s*([\\w.()]+)\\s*,");

    /** Clause SET d'une mise à jour de colis (JPQL ou SQL natif), jusqu'au WHERE. */
    private static final Pattern BID_UPDATE_SET = Pattern.compile(
            "(?is)\\bUPDATE\\s+(?:BidEntity|bids)\\b\\s*\\w*\\s+SET\\b((?:(?!\\bWHERE\\b)[^;])*)");

    /** Dans une clause SET : le statut reçoit ACCEPTED ou un paramètre. */
    private static final Pattern STATUS_TO_ACCEPTED_OR_PARAM = Pattern.compile(
            "(?i)(?:\\b\\w+\\.)?\\bstatus\\s*=\\s*(?:'ACCEPTED'|[\\w.]*BidStatus\\.ACCEPTED\\b|:\\w+|\\?)");

    /** Statut posé sur un colis à partir d'une variable plutôt que d'un littéral BidStatus. */
    private static final Pattern BID_VARIABLE_STATUS = Pattern.compile(
            "\\b((?i:\\w*bid\\w*)(?:\\(\\))?)\\.setStatus\\(\\s*(?!(?:com\\.yadony\\.api\\.matching\\.)?BidStatus\\.)[^)]*\\)");

    /** Les fichiers qui font aujourd'hui passer un colis à ACCEPTED, tous couverts par l'IT. */
    private static final Set<String> KNOWN_ACCEPTANCE_FILES = Set.of(
            "com/yadony/api/matching/BidService.java",
            "com/yadony/api/matching/ThreadAcceptedBidListener.java",
            "com/yadony/api/payments/cash/CashCommissionService.java",
            "com/yadony/api/payments/mobilemoney/MobileMoneyBidPaymentService.java");

    private record Source(String rel, List<String> lines, String text) {}

    private static List<Source> sources() throws IOException {
        List<Source> out = new ArrayList<>();
        try (var paths = Files.walk(MAIN)) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String text = Files.readString(p);
                String rel = p.toString().replace('\\', '/');
                rel = rel.substring(rel.indexOf("com/yadony/api/"));
                out.add(new Source(rel, text.lines().toList(), text));
            }
        }
        return out;
    }

    private static boolean isComment(String line) {
        String t = line.strip();
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*");
    }

    private static boolean staticallyImportsBidStatus(Source s) {
        return s.text().contains("import static com.yadony.api.matching.BidStatus.");
    }

    @Test
    @DisplayName("chaque passage à ACCEPTED est précédé du gel de la règle, sur le même colis")
    void everySetStatusAcceptedIsPrecededByFreeze() throws IOException {
        List<String> offenders = new ArrayList<>();
        Set<String> acceptanceFiles = new TreeSet<>();
        int sites = 0;

        for (Source s : sources()) {
            boolean staticImport = staticallyImportsBidStatus(s);
            for (int i = 0; i < s.lines().size(); i++) {
                String line = s.lines().get(i);
                if (isComment(line)) continue;
                Matcher m = SET_ACCEPTED.matcher(line);
                if (!m.find() && !(staticImport && (m = SET_ACCEPTED_STATIC.matcher(line)).find())) continue;
                sites++;
                acceptanceFiles.add(s.rel());
                String bid = m.group(1);
                String previous = previousCodeLine(s.lines(), i);
                Matcher f = previous == null ? null : FREEZE.matcher(previous);
                if (f == null || !f.find() || !f.group(1).equals(bid)) {
                    offenders.add(s.rel() + ":" + (i + 1) + " → " + line.strip()
                            + "  (ligne précédente : " + (previous == null ? "aucune" : previous.strip()) + ")");
                }
            }
        }

        assertThat(sites).as("aucun passage à ACCEPTED trouvé : le test ne vérifie plus rien").isGreaterThanOrEqualTo(4);
        assertThat(offenders)
                .as("passage à ACCEPTED sans payoutTriggerPolicy.freeze(<le même colis>, …) sur la ligne juste au-dessus")
                .isEmpty();
        assertThat(acceptanceFiles)
                .as("chemins d'acceptation : un nouveau fichier qui accepte un colis doit figer la règle ET être "
                        + "ajouté à PayoutTriggerFrozenIT.AcceptancePath puis à cette liste")
                .containsExactlyInAnyOrderElementsOf(KNOWN_ACCEPTANCE_FILES);
    }

    @Test
    @DisplayName("aucune mise à jour en masse des colis n'écrit le statut ACCEPTED (ni un statut paramétré)")
    void noBulkUpdateWritesAcceptedStatus() throws IOException {
        List<String> offenders = new ArrayList<>();
        int updates = 0;
        for (Source s : sources()) {
            Matcher m = BID_UPDATE_SET.matcher(s.text());
            while (m.find()) {
                updates++;
                String set = m.group(1);
                if (STATUS_TO_ACCEPTED_OR_PARAM.matcher(set).find()) {
                    offenders.add(s.rel() + " → SET" + set.replaceAll("\\s+", " "));
                }
            }
        }
        assertThat(updates).as("aucune mise à jour de colis trouvée : motif cassé").isPositive();
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("aucun colis ne reçoit un statut variable, qui masquerait un passage à ACCEPTED")
    void noBidReceivesAVariableStatus() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Source s : sources()) {
            for (int i = 0; i < s.lines().size(); i++) {
                String line = s.lines().get(i);
                if (isComment(line)) continue;
                Matcher m = BID_VARIABLE_STATUS.matcher(line);
                // BidEntity.setStatus lui-même (déclaration) et les statuts d'autres entités liées au
                // colis (supplément, avenant…) ne sont pas des statuts de colis.
                if (m.find() && !line.contains("public void setStatus(")
                        && !line.matches(".*\\b(?:Supplement|Amendment)Status\\..*")) {
                    offenders.add(s.rel() + ":" + (i + 1) + " → " + line.strip());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    /** Ligne de code non vide, hors commentaire, juste au-dessus de {@code index} ; null s'il n'y en a pas. */
    private static String previousCodeLine(List<String> lines, int index) {
        for (int j = index - 1; j >= 0; j--) {
            String l = lines.get(j);
            if (l.isBlank() || isComment(l)) continue;
            return l;
        }
        return null;
    }

    @Test
    @DisplayName("le motif reconnaît les écritures qualifiées, et un gel posé sur un autre colis ne compte pas")
    void patternsSelfCheck() {
        assertThat(SET_ACCEPTED.matcher("        bid.setStatus(BidStatus.ACCEPTED);").find()).isTrue();
        assertThat(SET_ACCEPTED.matcher("ctx.bid().setStatus(com.yadony.api.matching.BidStatus.ACCEPTED);").find())
                .isTrue();
        assertThat(SET_ACCEPTED.matcher("if (bid.getStatus() == BidStatus.ACCEPTED) return;").find()).isFalse();
        Matcher f = FREEZE.matcher("        payoutTriggerPolicy.freeze(other, announcement);");
        assertThat(f.find()).isTrue();
        assertThat(f.group(1)).isNotEqualTo("bid");
        assertThat(STATUS_TO_ACCEPTED_OR_PARAM.matcher(" b.status = :next, b.x = 1 ").find()).isTrue();
        assertThat(STATUS_TO_ACCEPTED_OR_PARAM.matcher(" b.status = 'ACCEPTED' ").find()).isTrue();
        assertThat(STATUS_TO_ACCEPTED_OR_PARAM.matcher(" b.status = b.status ").find()).isFalse();
        assertThat(STATUS_TO_ACCEPTED_OR_PARAM
                .matcher(" b.status = com.yadony.api.matching.BidStatus.PAYMENT_ESCROWED ").find()).isFalse();
        assertThat(BID_VARIABLE_STATUS.matcher("bid.setStatus(next);").find()).isTrue();
        assertThat(BID_VARIABLE_STATUS.matcher("bid.setStatus(BidStatus.CANCELLED);").find()).isFalse();
    }
}
