package com.yadony.api.support;

/**
 * Priorite de traitement, fixee par le support depuis le back-office.
 * L'utilisateur ne la choisit pas : sinon tout ticket serait urgent.
 */
public enum SupportPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}
