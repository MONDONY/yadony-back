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
 * Garde-fou (pas une preuve) : aucun colis ne passe à ACCEPTED sans que sa règle de versement soit
 * figée ({@code PayoutTriggerPolicy.freeze}, {@code bids.payout_trigger}, V383).
 *
 * <p>Un chemin d'acceptation qui l'oublierait laisserait un colis bateau payé à la livraison sans
 * le dire (ou l'inverse si la règle venait d'ailleurs). Ce test lit les sources de
 * {@code src/main/java} (pas d'ArchUnit dans le projet, même méthode que
 * {@code PublicDisplayNameCallersTest}), commentaires retirés, par instruction (une écriture coupée sur
 * plusieurs lignes est vue), et impose :
 * <ol>
 *   <li>chaque {@code x.setStatus(BidStatus.ACCEPTED)} est une instruction à part entière, précédée
 *       immédiatement de l'instruction {@code payoutTriggerPolicy.freeze(x, …);} sur le même colis, sans
 *       condition ({@code if}/{@code else} sans accolade refusés : un gel conditionnel ne compte pas) ;</li>
 *   <li>aucun {@code setStatus(…)} ne contient {@code BidStatus.ACCEPTED} dans une expression (ternaire…) ;</li>
 *   <li>aucune mise à jour JPQL ou SQL des colis ({@code BidEntity}, {@code bids}, {@code public.bids},
 *       alias avec ou sans {@code AS}) n'écrit {@code status = ACCEPTED}, un statut venu d'un paramètre ou
 *       un {@code CASE} (qui pourrait valoir ACCEPTED) ;</li>
 *   <li>aucun colis ne reçoit un statut variable : receveur dont le nom contient « bid », ou argument
 *       déclaré {@code BidStatus} dans le même fichier ({@code b.setStatus(next)}) ;</li>
 *   <li>la liste des fichiers qui acceptent un colis est celle que couvre
 *       {@code PayoutTriggerFrozenIT} : un nouveau chemin doit y être ajouté, et testé.</li>
 * </ol>
 * Restent hors de portée d'une lecture de texte : la réflexion, un statut passé par une méthode
 * intermédiaire d'un autre fichier, du SQL construit dynamiquement. L'IT reste la preuve des chemins connus.
 */
@DisplayName("Toute acceptation de colis fige la règle de versement")
class AcceptanceFreezesPayoutTriggerTest {

    private static final Path MAIN = Path.of("src/main/java/com/yadony/api");

    /** {@code x.setStatus(BidStatus.ACCEPTED)}, nom qualifié compris, sur une ou plusieurs lignes ; groupe 1 = le colis. */
    private static final Pattern SET_ACCEPTED = Pattern.compile(
            "([\\w.()]+)\\s*\\.\\s*setStatus\\(\\s*(?:com\\.yadony\\.api\\.matching\\.)?BidStatus\\.ACCEPTED\\s*\\)");

    /** {@code setStatus(ACCEPTED)} après un import statique de BidStatus. */
    private static final Pattern SET_ACCEPTED_STATIC = Pattern.compile(
            "([\\w.()]+)\\s*\\.\\s*setStatus\\(\\s*ACCEPTED\\s*\\)");

    /** Instruction de gel complète : {@code payoutTriggerPolicy.freeze(x, …);} ; groupe 1 = le colis. */
    private static final Pattern FREEZE_STATEMENT = Pattern.compile(
            "(?s)payoutTriggerPolicy\\s*\\.\\s*freeze\\(\\s*([\\w.()]+)\\s*,.*\\)\\s*;");

    /** Un {@code setStatus(…)} dont l'argument mentionne ACCEPTED ailleurs que dans la forme simple. */
    private static final Pattern SET_STATUS_EXPRESSION_WITH_ACCEPTED = Pattern.compile(
            "(?s)\\.\\s*setStatus\\(([^;]*?\\bBidStatus\\.ACCEPTED\\b[^;]*?)\\)\\s*;");

    /** Clause SET d'une mise à jour de colis (JPQL ou SQL natif), jusqu'au WHERE. */
    private static final Pattern BID_UPDATE_SET = Pattern.compile(
            "(?is)\\bUPDATE\\s+(?:public\\.)?(?:BidEntity|bids)\\b(?:\\s+(?:AS\\s+)?(?!SET\\b)\\w+)?\\s+SET\\b"
                    + "((?:(?!\\bWHERE\\b)[^;])*)");

    /** Dans une clause SET : le statut reçoit ACCEPTED, un paramètre ou un CASE. */
    private static final Pattern STATUS_TO_ACCEPTED_OR_PARAM = Pattern.compile(
            "(?i)(?:\\b\\w+\\.)?\\bstatus\\s*=\\s*(?:'ACCEPTED'|[\\w.]*BidStatus\\.ACCEPTED\\b|:\\w+|\\?|CASE\\b)");

    /** Statut posé sur un colis à partir d'une variable plutôt que d'un littéral BidStatus. */
    private static final Pattern BID_VARIABLE_STATUS = Pattern.compile(
            "\\b((?i:\\w*bid\\w*)(?:\\(\\))?)\\s*\\.\\s*setStatus\\(\\s*(?!(?:com\\.yadony\\.api\\.matching\\.)?BidStatus\\.)[^)]*\\)");

    /** {@code x.setStatus(nom)} : argument réduit à un identifiant ; groupe 1 = l'identifiant. */
    private static final Pattern SET_STATUS_IDENTIFIER = Pattern.compile(
            "\\.\\s*setStatus\\(\\s*(\\w+)\\s*\\)");

    /** Les fichiers qui font aujourd'hui passer un colis à ACCEPTED, tous couverts par l'IT. */
    private static final Set<String> KNOWN_ACCEPTANCE_FILES = Set.of(
            "com/yadony/api/matching/BidService.java",
            "com/yadony/api/matching/ThreadAcceptedBidListener.java",
            "com/yadony/api/payments/cash/CashCommissionService.java",
            "com/yadony/api/payments/mobilemoney/MobileMoneyBidPaymentService.java");

    /** Source lue : chemin relatif, texte sans commentaires (positions conservées : lignes vidées, pas retirées). */
    private record Source(String rel, String code) {
        static Source of(String rel, String text) {
            return new Source(rel, stripComments(text));
        }

        int lineOf(int offset) {
            int line = 1;
            for (int i = 0; i < offset && i < code.length(); i++) {
                if (code.charAt(i) == '\n') line++;
            }
            return line;
        }
    }

    private static List<Source> sources() throws IOException {
        List<Source> out = new ArrayList<>();
        try (var paths = Files.walk(MAIN)) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String rel = p.toString().replace('\\', '/');
                rel = rel.substring(rel.indexOf("com/yadony/api/"));
                out.add(Source.of(rel, Files.readString(p)));
            }
        }
        return out;
    }

    /**
     * Retire les commentaires ({@code //} jusqu'à la fin de ligne, {@code /* … *}{@code /}) en gardant les sauts
     * de ligne, sans toucher au contenu des chaînes (une requête JPQL peut contenir {@code //} ou {@code *}).
     */
    static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                boolean textBlock = c == '"' && text.startsWith("\"\"\"", i);
                String end = textBlock ? "\"\"\"" : String.valueOf(c);
                int j = i + end.length();
                while (j < n && !text.startsWith(end, j)) {
                    if (text.charAt(j) == '\\') j++;
                    j++;
                }
                j = Math.min(n, j + end.length());
                out.append(text, i, j);
                i = j;
            } else if (text.startsWith("//", i)) {
                while (i < n && text.charAt(i) != '\n') i++;
            } else if (text.startsWith("/*", i)) {
                int j = text.indexOf("*/", i + 2);
                j = j < 0 ? n : j + 2;
                for (int k = i; k < j; k++) {
                    if (text.charAt(k) == '\n') out.append('\n');
                }
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean staticallyImportsBidStatus(Source s) {
        return s.code().contains("import static com.yadony.api.matching.BidStatus.");
    }

    /** Index juste après le dernier délimiteur d'instruction ({@code ;}, {@code {}, {@code }}) avant {@code end}. */
    private static int statementStart(String code, int end) {
        for (int i = end - 1; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == ';' || c == '{' || c == '}') return i + 1;
        }
        return 0;
    }

    /**
     * Passages à ACCEPTED d'une source qui ne sont pas immédiatement précédés de l'instruction de gel, sans
     * condition, sur le même colis. {@code sites} reçoit chaque passage trouvé.
     */
    static List<String> acceptanceOffenders(Source s, List<String> sites) {
        List<String> offenders = new ArrayList<>();
        String code = s.code();
        List<Matcher> matchers = new ArrayList<>(List.of(SET_ACCEPTED.matcher(code)));
        if (staticallyImportsBidStatus(s)) matchers.add(SET_ACCEPTED_STATIC.matcher(code));
        for (Matcher m : matchers) {
            while (m.find()) {
                sites.add(s.rel());
                String bid = m.group(1);
                int ownStart = statementStart(code, m.start());
                // L'écriture doit être une instruction à part entière : rien entre le délimiteur précédent et
                // le receveur (sinon « if (c) bid.setStatus(…) », « else … », ternaire…).
                boolean standalone = code.substring(ownStart, m.start()).isBlank();
                String previous = null;
                if (standalone && ownStart > 0 && code.charAt(ownStart - 1) == ';') {
                    previous = code.substring(statementStart(code, ownStart - 1), ownStart).strip();
                }
                Matcher f = previous == null ? null : FREEZE_STATEMENT.matcher(previous);
                if (f == null || !f.matches() || !f.group(1).equals(bid)) {
                    offenders.add(s.rel() + ":" + s.lineOf(m.start()) + " → "
                            + code.substring(ownStart, m.end()).strip().replaceAll("\\s+", " ")
                            + "  (instruction précédente : " + (previous == null ? "aucune ou conditionnelle"
                            : previous.replaceAll("\\s+", " ")) + ")");
                }
            }
        }
        Matcher e = SET_STATUS_EXPRESSION_WITH_ACCEPTED.matcher(code);
        while (e.find()) {
            String arg = e.group(1).strip();
            if (!arg.matches("(?:com\\.yadony\\.api\\.matching\\.)?BidStatus\\.ACCEPTED")) {
                offenders.add(s.rel() + ":" + s.lineOf(e.start()) + " → setStatus(" + arg.replaceAll("\\s+", " ")
                        + ") : ACCEPTED dans une expression");
            }
        }
        return offenders;
    }

    /** Mises à jour JPQL/SQL de colis qui écrivent ACCEPTED, un paramètre ou un CASE. {@code updates} les compte. */
    static List<String> bulkUpdateOffenders(Source s, int[] updates) {
        List<String> offenders = new ArrayList<>();
        Matcher m = BID_UPDATE_SET.matcher(s.code());
        while (m.find()) {
            updates[0]++;
            String set = m.group(1);
            if (STATUS_TO_ACCEPTED_OR_PARAM.matcher(set).find()) {
                offenders.add(s.rel() + " → SET" + set.replaceAll("\\s+", " "));
            }
        }
        return offenders;
    }

    /** Colis qui reçoivent un statut variable. */
    static List<String> variableStatusOffenders(Source s) {
        List<String> offenders = new ArrayList<>();
        String code = s.code();
        Matcher m = BID_VARIABLE_STATUS.matcher(code);
        while (m.find()) {
            String line = lineAt(code, m.start());
            // BidEntity.setStatus lui-même (déclaration) et les statuts d'autres entités liées au
            // colis (supplément, avenant…) ne sont pas des statuts de colis.
            if (!line.contains("public void setStatus(")
                    && !line.matches(".*\\b(?:Supplement|Amendment)Status\\..*")) {
                offenders.add(s.rel() + ":" + s.lineOf(m.start()) + " → " + line.strip());
            }
        }
        // Receveur au nom quelconque (b.setStatus(next)) : l'argument est déclaré BidStatus dans ce fichier.
        Matcher id = SET_STATUS_IDENTIFIER.matcher(code);
        while (id.find()) {
            String name = id.group(1);
            String line = lineAt(code, id.start());
            if (Pattern.compile("\\bBidStatus\\s+" + Pattern.quote(name) + "\\b").matcher(code).find()
                    && !line.contains("public void setStatus(")) {
                String entry = s.rel() + ":" + s.lineOf(id.start()) + " → " + line.strip();
                if (!offenders.contains(entry)) offenders.add(entry);
            }
        }
        return offenders;
    }

    private static String lineAt(String code, int offset) {
        int start = code.lastIndexOf('\n', offset) + 1;
        int end = code.indexOf('\n', offset);
        return code.substring(start, end < 0 ? code.length() : end);
    }

    @Test
    @DisplayName("chaque passage à ACCEPTED est précédé du gel de la règle, sans condition, sur le même colis")
    void everySetStatusAcceptedIsPrecededByFreeze() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<String> sites = new ArrayList<>();
        for (Source s : sources()) {
            offenders.addAll(acceptanceOffenders(s, sites));
        }
        Set<String> acceptanceFiles = new TreeSet<>(sites);

        assertThat(sites).as("aucun passage à ACCEPTED trouvé : le test ne vérifie plus rien").hasSizeGreaterThanOrEqualTo(4);
        assertThat(offenders)
                .as("passage à ACCEPTED sans l'instruction payoutTriggerPolicy.freeze(<le même colis>, …); juste avant")
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
        int[] updates = {0};
        for (Source s : sources()) {
            offenders.addAll(bulkUpdateOffenders(s, updates));
        }
        assertThat(updates[0]).as("aucune mise à jour de colis trouvée : motif cassé").isPositive();
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("aucun colis ne reçoit un statut variable, qui masquerait un passage à ACCEPTED")
    void noBidReceivesAVariableStatus() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Source s : sources()) {
            offenders.addAll(variableStatusOffenders(s));
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("le motif reconnaît les écritures qualifiées, et un gel posé sur un autre colis ne compte pas")
    void patternsSelfCheck() {
        assertThat(SET_ACCEPTED.matcher("        bid.setStatus(BidStatus.ACCEPTED);").find()).isTrue();
        assertThat(SET_ACCEPTED.matcher("ctx.bid().setStatus(com.yadony.api.matching.BidStatus.ACCEPTED);").find())
                .isTrue();
        assertThat(SET_ACCEPTED.matcher("if (bid.getStatus() == BidStatus.ACCEPTED) return;").find()).isFalse();
        assertThat(STATUS_TO_ACCEPTED_OR_PARAM.matcher(" b.status = :next, b.x = 1 ").find()).isTrue();
        assertThat(STATUS_TO_ACCEPTED_OR_PARAM.matcher(" b.status = 'ACCEPTED' ").find()).isTrue();
        assertThat(STATUS_TO_ACCEPTED_OR_PARAM.matcher(" b.status = b.status ").find()).isFalse();
        assertThat(STATUS_TO_ACCEPTED_OR_PARAM
                .matcher(" b.status = com.yadony.api.matching.BidStatus.PAYMENT_ESCROWED ").find()).isFalse();
        assertThat(BID_VARIABLE_STATUS.matcher("bid.setStatus(next);").find()).isTrue();
        assertThat(BID_VARIABLE_STATUS.matcher("bid.setStatus(BidStatus.CANCELLED);").find()).isFalse();
    }

    @Test
    @DisplayName("gel correct reconnu, même avec une écriture coupée sur deux lignes")
    void selfCheck_freezeThenAccept_ok() {
        List<String> sites = new ArrayList<>();
        Source ok = Source.of("A.java", """
                class A { void a() {
                    var trip = repo.findById(id).orElseThrow(() -> new IllegalStateException("x"));
                    // commentaire entre les deux : ignoré
                    payoutTriggerPolicy.freeze(bid, trip);
                    bid.setStatus(
                            BidStatus.ACCEPTED);
                } }
                """);
        assertThat(acceptanceOffenders(ok, sites)).isEmpty();
        assertThat(sites).hasSize(1);
    }

    @Test
    @DisplayName("écriture coupée sur deux lignes sans gel : vue et refusée")
    void selfCheck_multilineWithoutFreeze_offender() {
        Source s = Source.of("A.java", """
                class A { void a() {
                    log.info("rien");
                    bid.setStatus(
                            BidStatus.ACCEPTED);
                } }
                """);
        assertThat(acceptanceOffenders(s, new ArrayList<>())).hasSize(1);
    }

    @Test
    @DisplayName("gel sous un if sans accolade (même ligne ou ligne précédente) : refusé")
    void selfCheck_conditionalFreeze_offender() {
        Source sameLine = Source.of("A.java", """
                class A { void a() {
                    if (boat) payoutTriggerPolicy.freeze(bid, trip);
                    bid.setStatus(BidStatus.ACCEPTED);
                } }
                """);
        Source twoLines = Source.of("B.java", """
                class B { void a() {
                    if (boat)
                        payoutTriggerPolicy.freeze(bid, trip);
                    bid.setStatus(BidStatus.ACCEPTED);
                } }
                """);
        Source elseBranch = Source.of("C.java", """
                class C { void a() {
                    if (x) { y(); } else payoutTriggerPolicy.freeze(bid, trip);
                    bid.setStatus(BidStatus.ACCEPTED);
                } }
                """);
        assertThat(acceptanceOffenders(sameLine, new ArrayList<>())).hasSize(1);
        assertThat(acceptanceOffenders(twoLines, new ArrayList<>())).hasSize(1);
        assertThat(acceptanceOffenders(elseBranch, new ArrayList<>())).hasSize(1);
    }

    @Test
    @DisplayName("gel sur un autre colis, écriture conditionnelle ou ACCEPTED dans un ternaire : refusés")
    void selfCheck_otherBidOrConditionalWriteOrTernary_offender() {
        Source otherBid = Source.of("A.java", """
                class A { void a() {
                    payoutTriggerPolicy.freeze(other, trip);
                    bid.setStatus(BidStatus.ACCEPTED);
                } }
                """);
        Source conditionalWrite = Source.of("B.java", """
                class B { void a() {
                    payoutTriggerPolicy.freeze(bid, trip);
                    if (ok) bid.setStatus(BidStatus.ACCEPTED);
                } }
                """);
        Source ternary = Source.of("C.java", """
                class C { void a() {
                    b.setStatus(ok ? BidStatus.ACCEPTED : BidStatus.REJECTED);
                } }
                """);
        assertThat(acceptanceOffenders(otherBid, new ArrayList<>())).hasSize(1);
        assertThat(acceptanceOffenders(conditionalWrite, new ArrayList<>())).hasSize(1);
        assertThat(acceptanceOffenders(ternary, new ArrayList<>())).hasSize(1);
    }

    @Test
    @DisplayName("mises à jour SQL : public.bids, alias avec AS et CASE sont vus")
    void selfCheck_bulkUpdateVariants() {
        int[] updates = {0};
        Source s = Source.of("R.java", """
                interface R {
                    @Query(value = "UPDATE public.bids SET status = 'ACCEPTED' WHERE id = :id", nativeQuery = true)
                    int a(UUID id);
                    @Query(value = "UPDATE bids AS b SET status = CASE WHEN b.x THEN 'ACCEPTED' ELSE b.status END WHERE b.id = :id", nativeQuery = true)
                    int b(UUID id);
                    @Query("UPDATE BidEntity b SET b.status = :next WHERE b.id = :id")
                    int c(UUID id, BidStatus next);
                    @Query("UPDATE BidEntity b SET b.status = b.status WHERE b.id = :id")
                    int d(UUID id);
                }
                """);
        assertThat(bulkUpdateOffenders(s, updates)).hasSize(3);
        assertThat(updates[0]).isEqualTo(4);
    }

    @Test
    @DisplayName("statut variable posé par un receveur au nom quelconque : vu par le type de l'argument")
    void selfCheck_variableStatusOnAnyReceiver() {
        Source s = Source.of("A.java", """
                class A { void a(BidEntity b, BidStatus next, AnnouncementStatus annStatus, AnnouncementEntity ann) {
                    b.setStatus(next);
                    ann.setStatus(annStatus);
                } }
                """);
        List<String> offenders = variableStatusOffenders(s);
        assertThat(offenders).hasSize(1);
        assertThat(offenders.get(0)).contains("b.setStatus(next)");
    }

    @Test
    @DisplayName("les commentaires et les chaînes sont traités à part")
    void selfCheck_stripComments() {
        String code = stripComments("""
                a(); // bid.setStatus(BidStatus.ACCEPTED);
                /* bid.setStatus(
                   BidStatus.ACCEPTED); */
                String q = "http://x/*y";
                b();
                """);
        assertThat(code).doesNotContain("setStatus").contains("\"http://x/*y\"").contains("b();");
        assertThat(code.lines().count()).isEqualTo(5);
    }
}
