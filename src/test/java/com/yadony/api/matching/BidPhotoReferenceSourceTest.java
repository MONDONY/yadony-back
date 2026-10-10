package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({BidPhotoReferenceSource.class, BidRefusalPhotoReferenceSource.class})
class BidPhotoReferenceSourceTest {

    @Autowired BidPhotoRepository photos;
    @Autowired BidRepository bids;
    @Autowired BidPhotoReferenceSource photoSource;
    @Autowired BidRefusalPhotoReferenceSource refusalSource;

    @Test
    void bidPhotos_allStatuses_areReferenced() {
        UUID bid = UUID.randomUUID();
        photos.save(new BidPhotoEntity(bid, "bids/s/active.jpg", 0));
        BidPhotoEntity deleting = photos.save(new BidPhotoEntity(bid, "bids/s/deleting.jpg", 1));
        deleting.markDeleting();
        photos.saveAndFlush(deleting);

        assertThat(photoSource.prefixes()).containsExactly("bids/");
        assertThat(photoSource.referencedKeys("bids/"))
                .containsExactlyInAnyOrder("bids/s/active.jpg", "bids/s/deleting.jpg");
    }

    @Test
    void refusalPhoto_anyPrefix_softDeletedBidIncluded() {
        BidEntity live = new BidEntity();
        live.setAnnouncementId(UUID.randomUUID());
        live.setSenderId(UUID.randomUUID());
        live.setStatus(BidStatus.PARCEL_REFUSED);
        live.setRefusalPhotoUrl("tracking/b1/refus.jpg");
        bids.saveAndFlush(live);
        BidEntity deleted = new BidEntity();
        deleted.setAnnouncementId(UUID.randomUUID());
        deleted.setSenderId(UUID.randomUUID());
        deleted.setStatus(BidStatus.PARCEL_REFUSED);
        deleted.setRefusalPhotoUrl("https://h/yadony-prod/bids/s/refus.jpg?sig=1");
        deleted.softDelete();
        bids.saveAndFlush(deleted);

        assertThat(refusalSource.prefixes()).contains("tracking/", "bids/", "users/", "requests/");
        assertThat(refusalSource.referencedKeys("tracking/")).containsExactly("tracking/b1/refus.jpg");
        assertThat(refusalSource.referencedKeys("bids/")).containsExactly("bids/s/refus.jpg");
        assertThat(refusalSource.referencedKeys("users/")).isEmpty();
    }
}
