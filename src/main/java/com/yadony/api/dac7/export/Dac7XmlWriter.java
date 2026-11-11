package com.yadony.api.dac7.export;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Écrit le fichier DPI-DAC7 conforme au schéma de collecte de la DGFiP ({@code collecte_DPIXML_v1.0.xsd}, revenus
 * 2026, publié dans {@code src/main/resources/dac7/xsd/}) et au cahier des charges v1.7 : UTF-8 sans BOM, prologue
 * XML seul, une déclaration initiale (DPI401 / OECD1), services personnels ({@code PersonalServices}) en euros
 * entiers, taxes à zéro (Yadony n'en retient aucune).
 *
 * <p>Choix de valorisation (cahier des charges, à confirmer par l'expert-comptable) : nom de type OECD207 (nom légal),
 * adresse de résidence OECD302 pour une personne, siège OECD304 pour une entité et pour l'opérateur, opérateur
 * fiscalement domicilié en France (RPONEX1), SIREN dans {@code TIN} et dans {@code IN} (BRN). Aucun compte financier
 * (IBAN) : Yadony ne le détient pas, Stripe le garde.
 */
public final class Dac7XmlWriter {

    static final String DPI = "urn:oecd:ties:dpi";
    static final String STF = "urn:oecd:ties:dpistf";
    static final String PROLOGUE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private Dac7XmlWriter() {
    }

    public static byte[] write(Dac7XmlDocument doc) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            // Prologue écrit tel que le cahier des charges le montre (guillemets doubles) ; StAX mettrait des apostrophes.
            out.writeBytes(PROLOGUE.getBytes(StandardCharsets.UTF_8));
            XMLStreamWriter w = XMLOutputFactory.newFactory().createXMLStreamWriter(out, "UTF-8");
            w.writeStartElement("dpi", "DPI_OECD", DPI);
            w.writeNamespace("dpi", DPI);
            w.writeNamespace("stf", STF);
            w.writeAttribute("version", "1.0");
            messageSpec(w, doc);
            w.writeStartElement("dpi", "DPIBody", DPI);
            operator(w, doc.year(), doc.operator());
            for (Dac7XmlDocument.Seller seller : doc.sellers()) {
                seller(w, doc.year(), doc.operator().siren(), seller);
            }
            w.writeEndElement();
            w.writeEndElement();
            w.flush();
            w.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Écriture du XML DAC7 impossible : " + e.getClass().getSimpleName());
        }
        return out.toByteArray();
    }

    /** {@code OP_<millésime>_<SIREN>_<partie unique>} : forme des MessageRefId et DocRefId du cahier des charges. */
    static String reference(int year, String siren, String unique) {
        return "OP_" + year + "_" + siren + "_" + unique;
    }

    private static void messageSpec(XMLStreamWriter w, Dac7XmlDocument doc) throws XMLStreamException {
        w.writeStartElement("dpi", "MessageSpec", DPI);
        text(w, "TransmittingCountry", "FR");
        text(w, "ReceivingCountry", "FR");
        text(w, "MessageType", "DPI");
        text(w, "MessageRefId", reference(doc.year(), doc.operator().siren(), doc.messageUniquePart()));
        text(w, "MessageTypeIndic", "DPI401");
        text(w, "ReportingPeriod", doc.year() + "-12-31");
        text(w, "Timestamp", doc.timestamp().format(TIMESTAMP));
        w.writeEndElement();
    }

    private static void operator(XMLStreamWriter w, int year, Dac7XmlDocument.Operator op) throws XMLStreamException {
        w.writeStartElement("dpi", "PlatformOperator", DPI);
        text(w, "ResCountryCode", "FR");
        w.writeStartElement("dpi", "TIN", DPI);
        w.writeAttribute("issuedBy", "FR");
        w.writeCharacters(op.siren());
        w.writeEndElement();
        w.writeStartElement("dpi", "IN", DPI);
        w.writeAttribute("issuedBy", "FR");
        w.writeAttribute("INType", "BRN");
        w.writeCharacters(op.siren());
        w.writeEndElement();
        optional(w, "VAT", op.vat());
        text(w, "Name", op.name());
        text(w, "PlatformBusinessName", op.businessName());
        w.writeStartElement("dpi", "Address", DPI);
        w.writeAttribute("legalAddressType", "OECD304");
        text(w, "CountryCode", "FR");
        w.writeStartElement("dpi", "AddressFix", DPI);
        optional(w, "Street", op.street());
        optional(w, "PostCode", op.postCode());
        text(w, "City", op.city());
        w.writeEndElement();
        w.writeEndElement();
        text(w, "Nexus", "RPONEX1");
        text(w, "AssumedReporting", "false");
        docSpec(w, reference(year, op.siren(), op.docRefUniquePart()));
        w.writeEndElement();
    }

    private static void seller(XMLStreamWriter w, int year, String siren, Dac7XmlDocument.Seller seller)
            throws XMLStreamException {
        Dac7XmlSeller s = seller.data();
        w.writeStartElement("dpi", "ReportableSeller", DPI);
        w.writeStartElement("dpi", "Identity", DPI);
        if (s.entity()) {
            w.writeStartElement("dpi", "EntitySeller", DPI);
            w.writeStartElement("dpi", "Standard", DPI);
            w.writeStartElement("dpi", "EntSellerID", DPI);
            text(w, "ResCountryCode", s.resCountryCode());
            tin(w, s);
            if (s.registrationNumber() != null && !s.registrationNumber().isBlank()) {
                w.writeStartElement("dpi", "IN", DPI);
                w.writeAttribute("issuedBy", s.resCountryCode());
                w.writeAttribute("INType", "Other");
                w.writeCharacters(s.registrationNumber());
                w.writeEndElement();
            }
            optional(w, "VAT", s.vat());
            w.writeStartElement("dpi", "Name", DPI);
            w.writeAttribute("nameType", "OECD207");
            w.writeCharacters(s.businessName());
            w.writeEndElement();
            address(w, s.address(), "OECD304");
        } else {
            w.writeStartElement("dpi", "IndividualSeller", DPI);
            w.writeStartElement("dpi", "Standard", DPI);
            w.writeStartElement("dpi", "IndSellerID", DPI);
            text(w, "ResCountryCode", s.resCountryCode());
            tin(w, s);
            optional(w, "VAT", s.vat());
            w.writeStartElement("dpi", "Name", DPI);
            w.writeAttribute("nameType", "OECD207");
            text(w, "FirstName", s.firstName());
            text(w, "LastName", s.lastName());
            w.writeEndElement();
            address(w, s.address(), "OECD302");
            w.writeStartElement("dpi", "BirthInfo", DPI);
            text(w, "BirthDate", s.birthDate().toString());
            w.writeEndElement();
        }
        w.writeEndElement(); // ...SellerID
        w.writeEndElement(); // Standard
        w.writeEndElement(); // ...Seller
        w.writeEndElement(); // Identity

        w.writeStartElement("dpi", "RelevantActivities", DPI);
        w.writeStartElement("dpi", "PersonalServices", DPI);
        List<Dac7XmlSeller.Quarter> q = s.quarters();
        amounts(w, "Consideration", "ConsQ", q.stream().map(Dac7XmlSeller.Quarter::consideration).toList());
        w.writeStartElement("dpi", "NumberOfActivities", DPI);
        for (int i = 0; i < 4; i++) {
            text(w, "NumbQ" + (i + 1), String.valueOf(q.get(i).operations()));
        }
        w.writeEndElement();
        amounts(w, "Fees", "FeesQ", q.stream().map(Dac7XmlSeller.Quarter::fees).toList());
        amounts(w, "Taxes", "TaxesQ", List.of(0L, 0L, 0L, 0L));
        w.writeEndElement();
        w.writeEndElement();
        docSpec(w, reference(year, siren, seller.docRefUniquePart()));
        w.writeEndElement();
    }

    private static void tin(XMLStreamWriter w, Dac7XmlSeller s) throws XMLStreamException {
        w.writeStartElement("dpi", "TIN", DPI);
        w.writeAttribute("issuedBy", s.tinIssuedBy());
        w.writeCharacters(s.tin());
        w.writeEndElement();
    }

    private static void address(XMLStreamWriter w, Dac7XmlSeller.Address a, String type) throws XMLStreamException {
        w.writeStartElement("dpi", "Address", DPI);
        w.writeAttribute("legalAddressType", type);
        text(w, "CountryCode", a.countryCode());
        if (a.city() != null && !a.city().isBlank()) {
            w.writeStartElement("dpi", "AddressFix", DPI);
            optional(w, "Street", a.street());
            optional(w, "BuildingIdentifier", a.building());
            optional(w, "PostCode", a.postCode());
            text(w, "City", a.city());
            w.writeEndElement();
        } else {
            text(w, "AddressFree", a.free());
        }
        w.writeEndElement();
    }

    private static void amounts(XMLStreamWriter w, String block, String prefix, List<Long> values)
            throws XMLStreamException {
        w.writeStartElement("dpi", block, DPI);
        for (int i = 0; i < 4; i++) {
            w.writeStartElement("dpi", prefix + (i + 1), DPI);
            w.writeAttribute("currCode", "EUR");
            w.writeCharacters(String.valueOf(values.get(i)));
            w.writeEndElement();
        }
        w.writeEndElement();
    }

    private static void docSpec(XMLStreamWriter w, String docRefId) throws XMLStreamException {
        w.writeStartElement("dpi", "DocSpec", DPI);
        w.writeStartElement("stf", "DocTypeIndic", STF);
        w.writeCharacters("OECD1");
        w.writeEndElement();
        w.writeStartElement("stf", "DocRefId", STF);
        w.writeCharacters(docRefId);
        w.writeEndElement();
        w.writeEndElement();
    }

    private static void text(XMLStreamWriter w, String name, String value) throws XMLStreamException {
        w.writeStartElement("dpi", name, DPI);
        w.writeCharacters(value == null ? "" : value);
        w.writeEndElement();
    }

    /** Balise facultative : absente plutôt que vide (règle CV02 du cahier des charges). */
    private static void optional(XMLStreamWriter w, String name, String value) throws XMLStreamException {
        if (value != null && !value.isBlank()) {
            text(w, name, value.strip());
        }
    }

    /** Pour les tests : le XML produit est-il bien du UTF-8 sans BOM ? */
    static boolean startsWithBom(byte[] xml) {
        return xml.length >= 3 && (xml[0] & 0xFF) == 0xEF && (xml[1] & 0xFF) == 0xBB && (xml[2] & 0xFF) == 0xBF;
    }

    static String asString(byte[] xml) {
        return new String(xml, StandardCharsets.UTF_8);
    }
}
