package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.StorageService;
import com.yadony.api.config.YadonyConfigProperties;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementSearchMapper — devise")
class AnnouncementSearchMapperTest {

    @Mock private UserRepository userRepository;
    @Mock private BidRepository bidRepository;
    @Mock private PriceGridService priceGridService;
    @Mock private StorageService storageService;

    private AnnouncementSearchMapper mapper;

    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();

    @BeforeEach
    void init() {
        YadonyConfigProperties config = new YadonyConfigProperties(null, null,
                new YadonyConfigProperties.Urgency(3), null);
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        mapper = new AnnouncementSearchMapper(userRepository, bidRepository, priceGridService, storageService, config);
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AnnouncementEntity buildAnnouncement() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(TRAVELER_ID);
        a.setDepartureCity("Toronto");
        a.setArrivalCity("Paris");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setAvailableKg(BigDecimal.valueOf(15));
        a.setTotalKg(BigDecimal.valueOf(15));
        a.setPricePerKg(BigDecimal.valueOf(8.4));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Toronto Pearson");
        a.setPickupLat(BigDecimal.valueOf(43.677));
        a.setPickupLng(BigDecimal.valueOf(-79.630));
        a.setDeliveryAddressLabel("CDG");
        a.setDeliveryLat(BigDecimal.valueOf(49.009));
        a.setDeliveryLng(BigDecimal.valueOf(2.547));
        a.setCurrency("CAD");
        setId(a, ANNOUNCEMENT_ID);
        return a;
    }

    @Test
    @DisplayName("toSearchResponse expose la devise de l'annonce, pas toujours EUR")
    void toSearchResponse_exposesAnnouncementCurrency() {
        when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.empty());
        when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

        AnnouncementSearchResponse result = mapper.toSearchResponse(buildAnnouncement(), false);

        assertThat(result.currency()).isEqualTo("CAD");
    }

    /**
     * Le feed de recherche est la première (et souvent la seule) surface où
     * l'expéditeur voit un trajet. Sans le drapeau ici, aucun badge « prix
     * négociable » n'est affichable et la fonctionnalité n'est jamais découverte.
     */
    @Test
    @DisplayName("toSearchResponse expose le drapeau negotiable (variante unitaire)")
    void toSearchResponse_exposesNegotiable() {
        when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.empty());
        when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);
        AnnouncementEntity a = buildAnnouncement();
        a.setNegotiable(true);

        assertThat(mapper.toSearchResponse(a, false).negotiable()).isTrue();
    }

    /**
     * La variante batch est celle réellement utilisée par {@code GET /announcements} :
     * les deux corps sont des jumeaux copiés-collés, un champ ajouté à l'un seulement
     * produirait un feed où le drapeau existe en détail mais jamais en liste.
     */
    @Test
    @DisplayName("la variante batch expose aussi le drapeau negotiable")
    void toSearchResponseBatch_exposesNegotiable() {
        AnnouncementEntity a = buildAnnouncement();
        a.setNegotiable(true);

        AnnouncementSearchResponse result = mapper.toSearchResponse(
                a, false, java.util.Map.of(), java.util.Map.of(), java.util.Map.of());

        assertThat(result.negotiable()).isTrue();
    }
}
