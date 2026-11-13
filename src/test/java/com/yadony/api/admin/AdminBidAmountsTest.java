package com.yadony.api.admin;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.TransportMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AdminBidAmounts : net voyageur affiché à l'équipe")
class AdminBidAmountsTest {

    private static BidEntity bid(String weightKg, String volumeM3) {
        BidEntity b = new BidEntity();
        b.setWeightKg(weightKg == null ? null : new BigDecimal(weightKg));
        b.setVolumeM3(volumeM3 == null ? null : new BigDecimal(volumeM3));
        return b;
    }

    @Test
    @DisplayName("trajet au kilo : poids × prix au kilo + grille, arrondi au centime (inchangé)")
    void kgTrip() {
        AnnouncementEntity plane = new AnnouncementEntity();
        plane.setTransportMode(TransportMode.PLANE);
        plane.setPricePerKg(new BigDecimal("8.50"));

        assertThat(AdminBidAmounts.netOf(bid("2.5", null), plane, new BigDecimal("3")))
                .isEqualByComparingTo("24.25");
        assertThat(AdminBidAmounts.netOf(bid(null, null), plane, null)).isNull();
    }

    @Test
    @DisplayName("bateau au m³ : volume × prix au m³, le poids n'est pas tarifé ; accord négocié tel quel")
    void boatPerM3Trip() {
        AnnouncementEntity boat = new AnnouncementEntity();
        boat.setTransportMode(TransportMode.BOAT);
        boat.setPricePerM3(new BigDecimal("600"));

        assertThat(AdminBidAmounts.netOf(bid("12", "0.096"), boat, null)).isEqualByComparingTo("57.60");
        BidEntity negotiated = bid("12", "0.096");
        negotiated.setNegotiatedNetEur(new BigDecimal("45"));
        assertThat(AdminBidAmounts.netOf(negotiated, boat, null)).isEqualByComparingTo("45");
        assertThat(AdminBidAmounts.netOf(bid("12", null), null, null)).isNull();
    }
}
