package com.yadony.api.toolkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.addressbook.delivery.DeliveryAddressEntity;
import com.yadony.api.addressbook.delivery.DeliveryAddressRepository;
import com.yadony.api.addressbook.pickup.PickupAddressEntity;
import com.yadony.api.addressbook.pickup.PickupAddressRepository;
import com.yadony.api.addressbook.recipient.RecipientEntity;
import com.yadony.api.addressbook.recipient.RecipientRepository;
import com.yadony.api.alerts.CorridorAlertEntity;
import com.yadony.api.alerts.CorridorAlertRepository;
import com.yadony.api.matching.PriceGridItemEntity;
import com.yadony.api.matching.PriceGridItemRepository;
import com.yadony.api.triptemplate.TripTemplateEntity;
import com.yadony.api.triptemplate.TripTemplateRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ToolsCompletionQueryRepositoryIT {

    @Autowired ToolsCompletionQueryRepository repository;
    @Autowired PickupAddressRepository pickupAddresses;
    @Autowired DeliveryAddressRepository deliveryAddresses;
    @Autowired RecipientRepository recipients;
    @Autowired CorridorAlertRepository alerts;
    @Autowired TripTemplateRepository tripTemplates;
    @Autowired PriceGridItemRepository priceGridItems;

    @Test
    void addresses_sum_pickup_and_delivery_of_the_user_only() {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        pickupAddresses.save(pickup(me));
        pickupAddresses.save(pickup(me));
        deliveryAddresses.save(delivery(me));
        pickupAddresses.save(pickup(other));

        assertThat(repository.countAddresses(me)).isEqualTo(3);
        assertThat(repository.countAddresses(other)).isEqualTo(1);
    }

    @Test
    void soft_deleted_rows_are_ignored() {
        UUID me = UUID.randomUUID();
        PickupAddressEntity deleted = pickupAddresses.save(pickup(me));
        deleted.setDeletedAt(LocalDateTime.now());
        pickupAddresses.save(deleted);
        RecipientEntity gone = recipients.save(recipient(me));
        gone.setDeletedAt(LocalDateTime.now());
        recipients.save(gone);

        assertThat(repository.countAddresses(me)).isZero();
        assertThat(repository.countRecipients(me)).isZero();
    }

    @Test
    void recipients_templates_and_price_grid_count_their_owner_rows() {
        UUID me = UUID.randomUUID();
        recipients.save(recipient(me));
        recipients.save(recipient(me));
        tripTemplates.save(template(me));
        priceGridItems.save(priceItem(me, 0));
        priceGridItems.save(priceItem(me, 1));
        priceGridItems.save(priceItem(me, 2));
        priceGridItems.save(priceItem(UUID.randomUUID(), 0));

        assertThat(repository.countRecipients(me)).isEqualTo(2);
        assertThat(repository.countTripTemplates(me)).isEqualTo(1);
        assertThat(repository.countPriceGridItems(me)).isEqualTo(3);
    }

    @Test
    void paused_alerts_still_count_as_configured() {
        UUID me = UUID.randomUUID();
        CorridorAlertEntity paused = alert(me);
        paused.setActive(false);
        alerts.save(paused);
        alerts.save(alert(me));

        assertThat(repository.countAlerts(me)).isEqualTo(2);
    }

    @Test
    void unknown_user_has_zero_everywhere() {
        UUID nobody = UUID.randomUUID();
        assertThat(repository.countAddresses(nobody)).isZero();
        assertThat(repository.countRecipients(nobody)).isZero();
        assertThat(repository.countAlerts(nobody)).isZero();
        assertThat(repository.countTripTemplates(nobody)).isZero();
        assertThat(repository.countPriceGridItems(nobody)).isZero();
    }

    // ── Helpers : uniquement les colonnes NOT NULL de chaque entité ───────────

    private static PickupAddressEntity pickup(UUID userId) {
        PickupAddressEntity a = new PickupAddressEntity();
        a.setUserId(userId);
        a.setLabel("Maison");
        a.setStreet("1 rue de Paris");
        a.setPostalCode("75001");
        a.setCity("Paris");
        return a;
    }

    private static DeliveryAddressEntity delivery(UUID userId) {
        DeliveryAddressEntity a = new DeliveryAddressEntity();
        a.setUserId(userId);
        a.setLabel("Famille");
        a.setCity("Dakar");
        return a;
    }

    private static RecipientEntity recipient(UUID userId) {
        RecipientEntity r = new RecipientEntity();
        r.setUserId(userId);
        r.setFullName("Awa Diop");
        r.setPhoneE164("+221770000000");
        r.setCountry("SN");
        return r;
    }

    private static CorridorAlertEntity alert(UUID ownerId) {
        CorridorAlertEntity a = new CorridorAlertEntity();
        a.setOwnerId(ownerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        return a;
    }

    private static TripTemplateEntity template(UUID userId) {
        TripTemplateEntity t = new TripTemplateEntity();
        t.setUserId(userId);
        t.setLabel("Paris Dakar");
        t.setDepartureCity("Paris");
        t.setArrivalCity("Dakar");
        t.setPricePerKg(8.0);
        return t;
    }

    private static PriceGridItemEntity priceItem(UUID travelerId, int position) {
        PriceGridItemEntity p = new PriceGridItemEntity();
        p.setTravelerId(travelerId);
        p.setLabel("Valise 23 kg");
        p.setUnitPriceNet(new BigDecimal("60.00"));
        p.setPosition(position);
        return p;
    }
}
