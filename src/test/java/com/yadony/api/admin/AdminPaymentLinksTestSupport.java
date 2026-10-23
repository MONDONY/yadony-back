package com.yadony.api.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ajouts de fiche vides, pour les tests unitaires du contrôleur qui ne portent pas sur eux. */
public final class AdminPaymentLinksTestSupport {

    private AdminPaymentLinksTestSupport() {
    }

    public static AdminPaymentLinks none() {
        // Indulgent : la plupart des tests n'appellent pas la fiche, le bouchon ne doit pas les faire échouer.
        AdminPaymentLinks links = mock(AdminPaymentLinks.class,
                org.mockito.Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        when(links.of(any(), any(), any())).thenReturn(AdminPaymentLinks.Result.EMPTY);
        return links;
    }
}
