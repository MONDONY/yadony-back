package com.yadony.api.payments.pawapay;

public enum PawapayOperationKind {
    DEPOSIT("depositId", "/v2/deposits"),
    PAYOUT("payoutId", "/v2/payouts"),
    REFUND("refundId", "/v2/refunds");

    private final String idField;
    private final String path;

    PawapayOperationKind(String idField, String path) {
        this.idField = idField;
        this.path = path;
    }

    /** Nom du champ portant l'identifiant dans les requêtes, réponses et callbacks pawaPay. */
    public String idField() { return idField; }

    public String path() { return path; }
}
