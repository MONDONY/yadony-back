package com.yadony.api.kyc.provider;

/**
 * Fournisseur de verification d'identite ayant produit une session.
 *
 * <p>Persistee sur chaque ligne KYC : une session se cree chez le fournisseur actif, mais se
 * relit toujours chez celui qui l'a produite.
 */
public enum VerificationProviderKind {
    STRIPE,
    DIDIT
}
