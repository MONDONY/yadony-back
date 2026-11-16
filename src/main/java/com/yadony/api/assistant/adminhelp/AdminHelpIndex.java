package com.yadony.api.assistant.adminhelp;

import com.yadony.api.assistant.kb.AssistantAnalyzers;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Index Lucene en mémoire des fiches du centre d'aide admin (français), construit une fois au démarrage. */
@Component
public class AdminHelpIndex {

    static final String ROOT = "classpath:assistant/admin-help";
    private static final String[] FIELDS = {"title", "keywords", "summary", "body"};
    private static final float[] BOOSTS = {3f, 2f, 2f, 1f};
    static final int MAX_TERMS = 30;

    private final List<AdminHelpArticle> articles;
    private final Map<String, AdminHelpArticle> byId;
    private final Analyzer analyzer = AssistantAnalyzers.forLocale("fr");
    private final IndexSearcher searcher;

    @Autowired
    public AdminHelpIndex() {
        this(AdminHelpLoader.load(new PathMatchingResourcePatternResolver(), ROOT));
    }

    public AdminHelpIndex(List<AdminHelpArticle> articles) {
        this.articles = articles.stream()
                .sorted(Comparator.comparingInt((AdminHelpArticle a) -> categoryRank(a.category()))
                        .thenComparingInt(AdminHelpArticle::order).thenComparing(AdminHelpArticle::title))
                .toList();
        Map<String, AdminHelpArticle> map = new LinkedHashMap<>();
        for (AdminHelpArticle a : this.articles) map.put(a.id(), a);
        this.byId = Map.copyOf(map);
        this.searcher = build(this.articles, analyzer);
    }

    /** Toutes les fiches, par rubrique puis par ordre. */
    public List<AdminHelpArticle> all() {
        return articles;
    }

    public Optional<AdminHelpArticle> find(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    public record Hit(AdminHelpArticle article, float score) {
    }

    /** Fiches les plus proches de {@code text}, meilleures d'abord, au-dessus de {@code minScore}. */
    public List<Hit> search(String text, int limit, double minScore) {
        if (text == null || limit <= 0) return List.of();
        Set<String> terms = terms(text);
        if (terms.isEmpty()) return List.of();
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        int count = 0;
        for (String term : terms) {
            if (count++ >= MAX_TERMS) break;
            for (int i = 0; i < FIELDS.length; i++) {
                query.add(new BoostQuery(new TermQuery(new Term(FIELDS[i], term)), BOOSTS[i]), BooleanClause.Occur.SHOULD);
            }
        }
        try {
            List<Hit> hits = new ArrayList<>();
            for (ScoreDoc d : searcher.search(query.build(), limit).scoreDocs) {
                if (d.score < minScore) continue;
                hits.add(new Hit(byId.get(searcher.storedFields().document(d.doc).get("id")), d.score));
            }
            return hits;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Termes analysés (mots vides retirés, accents pliés, racinisés) : sert aussi à choisir l'extrait. */
    public Set<String> terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null) return terms;
        try (TokenStream ts = analyzer.tokenStream("body", text)) {
            CharTermAttribute attr = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) terms.add(attr.toString());
            ts.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return terms;
    }

    static int categoryRank(String category) {
        int i = 0;
        for (String c : AdminHelpLoader.CATEGORIES.keySet()) {
            if (c.equals(category)) return i;
            i++;
        }
        return i;
    }

    private static IndexSearcher build(List<AdminHelpArticle> articles, Analyzer analyzer) {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        try {
            try (IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer))) {
                for (AdminHelpArticle a : articles) {
                    Document doc = new Document();
                    doc.add(new StringField("id", a.id(), Field.Store.YES));
                    doc.add(new TextField("title", a.title(), Field.Store.NO));
                    doc.add(new TextField("keywords", String.join(" ", a.keywords()), Field.Store.NO));
                    doc.add(new TextField("summary", a.summary(), Field.Store.NO));
                    doc.add(new TextField("body", a.body(), Field.Store.NO));
                    writer.addDocument(doc);
                }
            }
            return new IndexSearcher(DirectoryReader.open(dir));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
