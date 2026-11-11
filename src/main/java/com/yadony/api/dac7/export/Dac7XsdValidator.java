package com.yadony.api.dac7.export;

import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Set;

/**
 * Valide un fichier DPI-DAC7 contre le schéma de collecte publié par la DGFiP ({@code classpath:dac7/xsd/}). Les
 * trois fichiers du schéma sont servis depuis le classpath par un résolveur dédié : aucune ressource externe n'est
 * jamais chargée (ni DTD, ni schéma distant).
 *
 * <p>Une erreur ne rapporte que sa ligne et sa colonne, jamais le message du validateur : il recopie la valeur fautive,
 * qui peut être un NIF ou un nom.
 */
public final class Dac7XsdValidator {

    static final String ROOT = "collecte_DPIXML_v1.0.xsd";
    private static final String DIR = "dac7/xsd/";
    private static final Set<String> FILES = Set.of(ROOT, "collecte_IsoDPITypes_v1.0.xsd",
            "collecte_OecdDPITypes_v1.0.xsd");
    private static volatile Schema schema;

    private Dac7XsdValidator() {
    }

    /** Fichier non conforme : position seulement, aucune valeur. */
    public static final class InvalidFileException extends RuntimeException {
        private final int line;
        private final int column;

        InvalidFileException(int line, int column) {
            super("Fichier DAC7 non conforme au schéma de la DGFiP (ligne " + line + ", colonne " + column + ")");
            this.line = line;
            this.column = column;
        }

        public int line() { return line; }
        public int column() { return column; }
    }

    public static void validate(byte[] xml) {
        try {
            Validator validator = schema().newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                    // Un avertissement ne rend pas le fichier invalide.
                }

                @Override
                public void error(SAXParseException e) {
                    throw new InvalidFileException(e.getLineNumber(), e.getColumnNumber());
                }

                @Override
                public void fatalError(SAXParseException e) {
                    throw new InvalidFileException(e.getLineNumber(), e.getColumnNumber());
                }
            });
            validator.validate(new StreamSource(new ByteArrayInputStream(xml)));
        } catch (InvalidFileException e) {
            throw e;
        } catch (SAXParseException e) {
            throw new InvalidFileException(e.getLineNumber(), e.getColumnNumber());
        } catch (SAXException | IOException e) {
            throw new InvalidFileException(-1, -1);
        }
    }

    private static Schema schema() throws SAXException {
        Schema loaded = schema;
        if (loaded == null) {
            synchronized (Dac7XsdValidator.class) {
                loaded = schema;
                if (loaded == null) {
                    SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                    factory.setResourceResolver(new ClasspathResolver());
                    loaded = factory.newSchema(new StreamSource(open(ROOT), ROOT));
                    schema = loaded;
                }
            }
        }
        return loaded;
    }

    private static InputStream open(String name) {
        InputStream in = Dac7XsdValidator.class.getClassLoader().getResourceAsStream(DIR + name);
        if (in == null) {
            throw new IllegalStateException("Schéma DAC7 introuvable : " + DIR + name);
        }
        return in;
    }

    /** Ne sert que les trois fichiers du schéma, par leur nom ; tout autre import est refusé. */
    private static final class ClasspathResolver implements LSResourceResolver {
        @Override
        public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId,
                                       String baseURI) {
            String name = systemId == null ? null : systemId.substring(systemId.lastIndexOf('/') + 1);
            if (name == null || !FILES.contains(name)) {
                throw new IllegalStateException("Ressource de schéma non autorisée");
            }
            return new Input(publicId, name, open(name));
        }
    }

    private record Input(String publicId, String systemId, InputStream byteStream) implements LSInput {
        @Override public Reader getCharacterStream() { return null; }
        @Override public void setCharacterStream(Reader characterStream) { }
        @Override public InputStream getByteStream() { return byteStream; }
        @Override public void setByteStream(InputStream byteStream) { }
        @Override public String getStringData() { return null; }
        @Override public void setStringData(String stringData) { }
        @Override public String getSystemId() { return systemId; }
        @Override public void setSystemId(String systemId) { }
        @Override public String getPublicId() { return publicId; }
        @Override public void setPublicId(String publicId) { }
        @Override public String getBaseURI() { return null; }
        @Override public void setBaseURI(String baseURI) { }
        @Override public String getEncoding() { return null; }
        @Override public void setEncoding(String encoding) { }
        @Override public boolean getCertifiedText() { return false; }
        @Override public void setCertifiedText(boolean certifiedText) { }
    }
}
