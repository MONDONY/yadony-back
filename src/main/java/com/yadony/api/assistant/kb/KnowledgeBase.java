package com.yadony.api.assistant.kb;

import com.yadony.api.assistant.AssistantProperties;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Index Lucene en mémoire des fiches, un par langue, construit une fois au démarrage. */
@Component
public class KnowledgeBase {

    private static final String[] FIELDS = {"title", "keywords", "body"};
    private static final float[] BOOSTS = {3f, 2f, 1f};
    private static final float PREVIOUS_QUESTION_WEIGHT = 0.5f;

    private final KbCorpus corpus;
    private final double minScore;
    private final Map<String, Index> indexes = new HashMap<>();

    @Autowired
    public KnowledgeBase(AssistantProperties properties) {
        this(KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb"),
                properties.minScore());
    }

    public KnowledgeBase(KbCorpus corpus, double minScore) {
        this.corpus = corpus;
        this.minScore = minScore;
        for (String locale : KnowledgeBaseLoader.LOCALES) {
            indexes.put(locale, build(locale, corpus.articles(locale)));
        }
    }

    public Set<String> routeWhitelist() {
        return corpus.routePaths();
    }

    public List<KbHit> search(String locale, String question, String previousQuestion, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Index index = indexes.getOrDefault(locale, indexes.get("fr"));
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        int clauses = addTerms(query, index.analyzer(), question, 1f);
        if (previousQuestion != null && !previousQuestion.isBlank()) {
            clauses += addTerms(query, index.analyzer(), previousQuestion, PREVIOUS_QUESTION_WEIGHT);
        }
        if (clauses == 0 || index.articles().isEmpty()) {
            return List.of();
        }
        try {
            ScoreDoc[] docs = index.searcher().search(query.build(), limit).scoreDocs;
            List<KbHit> hits = new ArrayList<>();
            for (ScoreDoc d : docs) {
                if (d.score < minScore) continue;
                String id = index.searcher().storedFields().document(d.doc).get("id");
                hits.add(new KbHit(index.byId().get(id), d.score));
            }
            return hits;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int addTerms(BooleanQuery.Builder query, Analyzer analyzer, String text, float weight) {
        Set<String> terms = new LinkedHashSet<>();
        try (TokenStream ts = analyzer.tokenStream("body", text)) {
            CharTermAttribute attr = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                terms.add(attr.toString());
            }
            ts.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int count = 0;
        for (String term : terms) {
            if (count >= 100) break;
            for (int i = 0; i < FIELDS.length; i++) {
                query.add(new BoostQuery(new TermQuery(new Term(FIELDS[i], term)), BOOSTS[i] * weight),
                        BooleanClause.Occur.SHOULD);
            }
            count++;
        }
        return count;
    }

    private static Index build(String locale, List<KbArticle> articles) {
        Analyzer analyzer = AssistantAnalyzers.forLocale(locale);
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        try {
            try (IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer))) {
                for (KbArticle a : articles) {
                    Document doc = new Document();
                    doc.add(new StringField("id", a.id(), Field.Store.YES));
                    doc.add(new TextField("title", a.title(), Field.Store.NO));
                    doc.add(new TextField("keywords", String.join(" ", a.keywords()), Field.Store.NO));
                    doc.add(new TextField("body", a.body(), Field.Store.NO));
                    writer.addDocument(doc);
                }
            }
            Map<String, KbArticle> byId = new HashMap<>();
            for (KbArticle a : articles) {
                byId.put(a.id(), a);
            }
            return new Index(analyzer, new IndexSearcher(DirectoryReader.open(dir)), List.copyOf(articles), Map.copyOf(byId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Le champ stocké « id » relie chaque document Lucene à sa fiche : pas d'hypothèse sur l'ordre interne. */
    private record Index(Analyzer analyzer, IndexSearcher searcher, List<KbArticle> articles, Map<String, KbArticle> byId) {
    }
}
