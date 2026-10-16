package com.yadony.api.assistant.kb;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.en.EnglishPossessiveFilter;
import org.apache.lucene.analysis.en.PorterStemFilter;
import org.apache.lucene.analysis.fr.FrenchAnalyzer;
import org.apache.lucene.analysis.fr.FrenchLightStemFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.util.ElisionFilter;

/** Analyseurs de l'assistant : français et anglais retirent aussi les accents (« numero » = « numéro », « cafe » = « café »). */
final class AssistantAnalyzers {

    private AssistantAnalyzers() {
    }

    static Analyzer forLocale(String locale) {
        return "en".equals(locale) ? english() : french();
    }

    private static Analyzer english() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer source = new StandardTokenizer();
                TokenStream s = new EnglishPossessiveFilter(source);
                s = new LowerCaseFilter(s);
                s = new StopFilter(s, EnglishAnalyzer.ENGLISH_STOP_WORDS_SET);
                s = new ASCIIFoldingFilter(s);
                s = new PorterStemFilter(s);
                return new TokenStreamComponents(source, s);
            }

            @Override
            protected TokenStream normalize(String fieldName, TokenStream in) {
                return new ASCIIFoldingFilter(new LowerCaseFilter(in));
            }
        };
    }

    private static Analyzer french() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer source = new StandardTokenizer();
                TokenStream s = new ElisionFilter(source, FrenchAnalyzer.DEFAULT_ARTICLES);
                s = new LowerCaseFilter(s);
                s = new StopFilter(s, FrenchAnalyzer.getDefaultStopSet());
                s = new ASCIIFoldingFilter(s);
                s = new FrenchLightStemFilter(s);
                return new TokenStreamComponents(source, s);
            }

            @Override
            protected TokenStream normalize(String fieldName, TokenStream in) {
                return new ASCIIFoldingFilter(new LowerCaseFilter(in));
            }
        };
    }
}
