package com.yadony.api.common;

/**
 * Règles d'une clé d'objet R2 venue du client.
 *
 * <p>Un simple {@code key.startsWith(ownerPrefix)} ne suffit pas : une clé comme
 * {@code bids/<moi>/../../package_requests/<autrui>/x.jpg} passe le test de préfixe. Le SDK S3
 * transmet la clé telle quelle (pas de normalisation des segments pour S3), et ce que R2 ou
 * le bord Cloudflare en fait reste hors de notre contrôle : la clé doit donc être refusée
 * avant toute écriture en base, présignature, copie ou suppression.
 */
public final class StorageKeys {

    private StorageKeys() {}

    /**
     * Clé sans aucun moyen de sortir de son préfixe : non vide, sans {@code /} initial, sans
     * {@code \}, sans caractère de contrôle, sans segment vide, {@code .} ou {@code ..}.
     */
    public static boolean isSafeKey(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.indexOf('\\') >= 0) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            if (Character.isISOControl(key.charAt(i))) {
                return false;
            }
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Garde-fou du dernier recours, avant tout appel à R2 : une clé qui contient un segment
     * {@code .} ou {@code ..}, un {@code \} ou un caractère de contrôle n'est jamais envoyée,
     * même lue en base. Plus permissif que {@link #isSafeKey} : les clés construites côté serveur
     * restent acceptées telles quelles.
     */
    public static boolean hasTraversal(String key) {
        if (key == null) {
            return false;
        }
        if (key.indexOf('\\') >= 0) {
            return true;
        }
        for (int i = 0; i < key.length(); i++) {
            if (Character.isISOControl(key.charAt(i))) {
                return true;
            }
        }
        for (String segment : key.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    /** Clé sûre ({@link #isSafeKey}) rangée sous le préfixe du propriétaire, et pas le préfixe seul. */
    public static boolean isOwnedKey(String key, String ownerPrefix) {
        return isSafeKey(key) && key.startsWith(ownerPrefix) && key.length() > ownerPrefix.length();
    }
}
