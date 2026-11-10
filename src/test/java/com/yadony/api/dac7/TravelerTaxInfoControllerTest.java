package com.yadony.api.dac7;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.dac7.dto.TaxInfoRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Interrupteur {@code yadony.dac7.enabled} : éteint (défaut), les deux endpoints répondent 404 sans rien lire. */
class TravelerTaxInfoControllerTest {

    private final TravelerTaxInfoService service = mock(TravelerTaxInfoService.class);

    @Test
    void eteint_get404SansLecture() {
        TravelerTaxInfoController controller = new TravelerTaxInfoController(service, new Dac7Properties(null, null));
        assertThatThrownBy(() -> controller.get("uid"))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(404);
                    assertThat(e.getErrorCode()).isEqualTo("dac7-disabled");
                });
        verify(service, never()).get(anyString());
    }

    @Test
    void eteint_put404SansEcriture() {
        TravelerTaxInfoController controller = new TravelerTaxInfoController(service, new Dac7Properties(false, null));
        assertThatThrownBy(() -> controller.set("uid", new TaxInfoRequest("1234567890", "FR", null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("dac7-disabled"));
        verify(service, never()).set(anyString(), any());
    }

    @Test
    void allume_sansUid_401() {
        TravelerTaxInfoController controller = new TravelerTaxInfoController(service, new Dac7Properties(true, null));
        assertThatThrownBy(() -> controller.get(null))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(401));
        assertThatThrownBy(() -> controller.set(" ", new TaxInfoRequest("1234567890", "FR", null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(401));
    }

    @Test
    void allume_delegueAuService() {
        TravelerTaxInfoController controller = new TravelerTaxInfoController(service, new Dac7Properties(true, null));
        TaxInfoRequest request = new TaxInfoRequest("1234567890", "FR", null);
        controller.get("uid");
        controller.set("uid", request);
        verify(service).get("uid");
        verify(service).set("uid", request);
    }
}
