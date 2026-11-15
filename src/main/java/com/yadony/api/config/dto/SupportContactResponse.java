package com.yadony.api.config.dto;

/**
 * Contact du support affiché par l'application. {@code whatsappNumber} : numéro international
 * (« +33612345678 »). Tant qu'aucun numéro n'est réglé dans le back-office la clé est absente de la réponse (le
 * serveur n'écrit pas les null) : l'application n'affiche alors pas la carte WhatsApp.
 */
public record SupportContactResponse(String whatsappNumber) {}
