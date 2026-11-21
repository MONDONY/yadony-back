package com.yadony.api.addressbook.invitation.dto;

/** Aperçu minimal d'un compte trouvé par identifiant : rien d'autre ne doit en sortir. */
public record UsernameLookupResponse(String username, String initial) {}
