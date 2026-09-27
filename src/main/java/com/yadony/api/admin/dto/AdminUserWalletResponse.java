package com.yadony.api.admin.dto;

import java.util.List;

/** {@code GET /admin/users/{userId}/wallet} : tous les portefeuilles, liste vide si aucun. */
public record AdminUserWalletResponse(List<AdminWalletAccountResponse> accounts) {
}
