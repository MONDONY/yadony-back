package com.yadony.api.matching;

import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.matching.events.TripSearchPerformedEvent;
import com.yadony.api.tracking.TripPositionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** La recherche publie {@link TripSearchPerformedEvent} pour les suggestions, sans jamais en dépendre. */
class AnnouncementControllerSearchEventTest {

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String uid) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER"))));
    }

    private AnnouncementController controller(Page<AnnouncementSearchResponse> page) {
        AnnouncementService service = mock(AnnouncementService.class, invocation -> page);
        return new AnnouncementController(service, mock(TripGroupService.class), mock(TripSeriesService.class),
                mock(TripPositionService.class), publisher);
    }

    private void search(AnnouncementController c, String dep, String arr, Pageable pageable) {
        c.searchAnnouncements(dep, arr, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 30),
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                false, null, null, null, null, null, null, null, null, "score", "asc", pageable);
    }

    private static Page<AnnouncementSearchResponse> emptyPage(Pageable pageable, Long bookable) {
        return new TripSearchPage<>(List.of(), pageable, 3, bookable, List.of());
    }

    @Test
    void firstPageOfASignedInSearch_isPublished_withTheBookableCount() {
        Pageable pageable = PageRequest.of(0, 20);
        signIn("uid-awa");

        search(controller(emptyPage(pageable, 0L)), " Paris ", "Abidjan", pageable);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new TripSearchPerformedEvent("uid-awa", "Paris", "Abidjan",
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 30), 0L));
    }

    @Test
    void withoutBookableCount_theTotalIsUsed() {
        Pageable pageable = PageRequest.of(0, 20);
        signIn("uid-awa");

        search(controller(emptyPage(pageable, null)), "Paris", "Abidjan", pageable);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(((TripSearchPerformedEvent) captor.getValue()).bookableCount()).isEqualTo(3L);
    }

    @Test
    void anonymous_counters_nextPages_orIncompleteCorridor_areNotSearches() {
        Pageable first = PageRequest.of(0, 20);
        search(controller(emptyPage(first, 0L)), "Paris", "Abidjan", first);              // anonyme

        signIn("uid-awa");
        Pageable counter = PageRequest.of(0, 1);
        search(controller(emptyPage(counter, 0L)), "Paris", "Abidjan", counter);           // compteur
        Pageable second = PageRequest.of(1, 20);
        search(controller(emptyPage(second, 0L)), "Paris", "Abidjan", second);             // page 2
        search(controller(emptyPage(first, 0L)), "Paris", null, first);                    // pas d'arrivée
        search(controller(emptyPage(first, 0L)), " ", "Abidjan", first);                   // départ vide

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void publishingFailure_neverBreaksTheSearch() {
        Pageable pageable = PageRequest.of(0, 20);
        signIn("uid-awa");
        doThrow(new IllegalStateException("bus")).when(publisher).publishEvent(any(Object.class));

        assertThatCode(() -> search(controller(emptyPage(pageable, 0L)), "Paris", "Abidjan", pageable))
                .doesNotThrowAnyException();
    }
}
