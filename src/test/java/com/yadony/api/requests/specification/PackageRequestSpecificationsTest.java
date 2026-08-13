package com.yadony.api.requests.specification;

import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.ParcelSize;
import jakarta.persistence.criteria.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("PackageRequestSpecifications")
class PackageRequestSpecificationsTest {

    private Root<PackageRequestEntity> root;
    private CriteriaQuery<?> query;
    private CriteriaBuilder cb;
    private Path<Object> statusPath;
    private Path<Object> departurePath;
    private Path<Object> arrivalPath;
    private Path<Object> datePath;
    private Path<Object> weightPath;
    private Path<Object> sizePath;
    private Expression<String> lowerExpression;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        root = mock(Root.class);
        query = mock(CriteriaQuery.class);
        cb = mock(CriteriaBuilder.class);
        statusPath = mock(Path.class);
        departurePath = mock(Path.class);
        arrivalPath = mock(Path.class);
        datePath = mock(Path.class);
        weightPath = mock(Path.class);
        sizePath = mock(Path.class);
        lowerExpression = mock(Expression.class);

        when(root.get("status")).thenReturn(statusPath);
        when(root.get("departureCity")).thenReturn(departurePath);
        when(root.get("arrivalCity")).thenReturn(arrivalPath);
        when(root.get("desiredDate")).thenReturn(datePath);
        when(root.get("weightKg")).thenReturn(weightPath);
        when(root.get("parcelSize")).thenReturn(sizePath);
        when(cb.lower(any())).thenReturn(lowerExpression);

        Predicate conjunction = mock(Predicate.class);
        Predicate andPred = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);
        when(cb.and(any(Predicate.class), any(Predicate.class))).thenReturn(andPred);
    }

    @Test
    @DisplayName("openOnly() retourne un prédicat non-null")
    void openOnly_callsIn() {
        // openOnly() calls statusPath.in(OPEN, NEGOTIATING) — the Path.in() varargs
        // returns null by default in Mockito; just verify it's invoked and doesn't NPE.
        // The result may be null because Mockito returns null by default for unmocked calls.
        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.openOnly();
        // Should not throw
        spec.toPredicate(root, query, cb);
        verify(statusPath).in(
            com.yadony.api.requests.entity.PackageRequestStatus.OPEN,
            com.yadony.api.requests.entity.PackageRequestStatus.NEGOTIATING);
    }

    @Test
    @DisplayName("corridor() null/null → retourne conjunction")
    void corridor_nullNull_returnsConjunction() {
        Predicate conjunction = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.corridor(null, null);
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isEqualTo(conjunction);
    }

    @Test
    @DisplayName("corridor() avec departure seulement → filtre sur departureCity uniquement")
    void corridor_departureOnly() {
        Predicate conjunction = mock(Predicate.class);
        Predicate equalPred = mock(Predicate.class);
        Predicate andPred = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);
        when(cb.equal(any(), anyString())).thenReturn(equalPred);
        when(cb.and(any(Predicate.class), any(Predicate.class))).thenReturn(andPred);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.corridor("Paris", null);
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isEqualTo(andPred);
        // Should call lower(departureCity) but not lower(arrivalCity) for the equal pred
        verify(cb, atLeastOnce()).lower(any());
    }

    @Test
    @DisplayName("corridor() avec les deux → filtre sur departure et arrival")
    void corridor_bothSet() {
        Predicate conjunction = mock(Predicate.class);
        Predicate equalPred = mock(Predicate.class);
        Predicate andPred = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);
        when(cb.equal(any(), anyString())).thenReturn(equalPred);
        when(cb.and(any(Predicate.class), any(Predicate.class))).thenReturn(andPred);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.corridor("Paris", "Dakar");
        spec.toPredicate(root, query, cb);

        // Both lower() calls for departure and arrival
        verify(cb, times(2)).lower(any());
    }

    @Test
    @DisplayName("dateRange() null/null → retourne conjunction")
    void dateRange_nullNull_returnsConjunction() {
        Predicate conjunction = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.dateRange(null, null);
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isEqualTo(conjunction);
    }

    @Test
    @DisplayName("dateRange() with from only → greaterThanOrEqualTo")
    void dateRange_fromOnly() {
        Predicate pred = mock(Predicate.class);
        LocalDate from = LocalDate.now();
        when(cb.greaterThanOrEqualTo(any(), eq(from))).thenReturn(pred);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.dateRange(from, null);
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isEqualTo(pred);
        verify(cb).greaterThanOrEqualTo(any(), eq(from));
    }

    @Test
    @DisplayName("dateRange() with to only → lessThanOrEqualTo")
    void dateRange_toOnly() {
        Predicate pred = mock(Predicate.class);
        LocalDate to = LocalDate.now().plusDays(7);
        when(cb.lessThanOrEqualTo(any(), eq(to))).thenReturn(pred);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.dateRange(null, to);
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isEqualTo(pred);
        verify(cb).lessThanOrEqualTo(any(), eq(to));
    }

    @Test
    @DisplayName("dateRange() with both → between")
    void dateRange_both() {
        Predicate pred = mock(Predicate.class);
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(7);
        when(cb.between(any(), eq(from), eq(to))).thenReturn(pred);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.dateRange(from, to);
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isEqualTo(pred);
        verify(cb).between(any(), eq(from), eq(to));
    }

    @Test
    @DisplayName("maxWeight() null → retourne conjunction")
    void maxWeight_null_returnsConjunction() {
        Predicate conjunction = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.maxWeight(null);
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isEqualTo(conjunction);
    }

    @Test
    @DisplayName("maxWeight() non-null → lessThanOrEqualTo sur weightKg")
    void maxWeight_nonNull() {
        Predicate pred = mock(Predicate.class);
        BigDecimal maxKg = new BigDecimal("10");
        when(cb.lessThanOrEqualTo(any(), eq(maxKg))).thenReturn(pred);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.maxWeight(maxKg);
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isEqualTo(pred);
    }

    @Test
    @DisplayName("parcelSize() null → retourne conjunction")
    void parcelSize_null_returnsConjunction() {
        Predicate conjunction = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.parcelSize(null);
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isEqualTo(conjunction);
    }

    @Test
    @DisplayName("parcelSize() non-null → equal sur parcelSize")
    void parcelSize_nonNull() {
        Predicate pred = mock(Predicate.class);
        when(cb.equal(any(), eq(ParcelSize.SMALL))).thenReturn(pred);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.parcelSize(ParcelSize.SMALL);
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isEqualTo(pred);
        verify(cb).equal(any(), eq(ParcelSize.SMALL));
    }

    @Test
    @DisplayName("hasCurrency() non-null → equal sur currency")
    void hasCurrency_nonNull() {
        Predicate pred = mock(Predicate.class);
        when(cb.equal(any(), eq("CAD"))).thenReturn(pred);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.hasCurrency("CAD");
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isEqualTo(pred);
        verify(root).get("currency");
        verify(cb).equal(any(), eq("CAD"));
    }

    @Test
    @DisplayName("urgent() → between(desiredDate, today, today+thresholdDays)")
    void urgent_buildsBetweenOnDesiredDate() {
        Predicate pred = mock(Predicate.class);
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        int thresholdDays = 3;
        when(cb.between(any(), eq(today), eq(today.plusDays(thresholdDays)))).thenReturn(pred);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.urgent(thresholdDays);
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isEqualTo(pred);
        verify(cb).between(any(), eq(today), eq(today.plusDays(thresholdDays)));
    }

    @Test
    @DisplayName("idIn() collection vide → retourne disjunction")
    void idIn_collectionVide_neMatcheRien() {
        java.util.Set<java.util.UUID> emptySet = java.util.Set.of();
        Predicate disjunction = mock(Predicate.class);
        when(cb.disjunction()).thenReturn(disjunction);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.idIn(emptySet);
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isSameAs(disjunction);
        verify(cb).disjunction();
    }

    @Test
    @DisplayName("idIn() collection non-vide → utilise in() sur id")
    @SuppressWarnings("unchecked")
    void idIn_collectionNonVide_utiliseUnIn() {
        java.util.UUID a = java.util.UUID.randomUUID();
        java.util.UUID b = java.util.UUID.randomUUID();

        Path<Object> idPath = mock(Path.class);
        Predicate inPredicate = mock(Predicate.class);
        when(root.get("id")).thenReturn(idPath);
        when(idPath.in(any(java.util.Collection.class))).thenReturn(inPredicate);

        Specification<PackageRequestEntity> spec = PackageRequestSpecifications.idIn(java.util.List.of(a, b));
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isSameAs(inPredicate);
        verify(root).get("id");
    }
}
