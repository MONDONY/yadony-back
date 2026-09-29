package com.yadony.api.support;

/**
 * Apercu d'un message pour une liste : texte brut sur une ligne (retours a la
 * ligne et espaces multiples ecrases), {@link #MAX_LENGTH} caracteres au plus,
 * points de suspension compris, coupe au mot. Un message sans texte (une image
 * seule) prend le libelle traduit fourni par l'appelant.
 */
public final class SupportMessagePreview {

    public static final int MAX_LENGTH = 80;
    private static final String ELLIPSIS = "…";

    private SupportMessagePreview() {}

    public static String of(SupportMessageEntity message, String attachmentLabel) {
        if (message == null) {
            return null;
        }
        String content = message.getContent();
        String flat = content == null ? "" : content.strip().replaceAll("\\s+", " ");
        if (flat.isEmpty()) {
            return attachmentLabel;
        }
        return truncate(flat);
    }

    static String truncate(String text) {
        if (text.length() <= MAX_LENGTH) {
            return text;
        }
        int room = MAX_LENGTH - ELLIPSIS.length();
        String head;
        if (!Character.isLetterOrDigit(text.charAt(room))) {
            head = text.substring(0, room);
        } else {
            int cut = text.lastIndexOf(' ', room);
            head = cut > 0 ? text.substring(0, cut) : text.substring(0, room);
        }
        int end = head.length();
        while (end > 0 && ",;:.!? ".indexOf(head.charAt(end - 1)) >= 0) {
            end--;
        }
        return head.substring(0, end) + ELLIPSIS;
    }
}
