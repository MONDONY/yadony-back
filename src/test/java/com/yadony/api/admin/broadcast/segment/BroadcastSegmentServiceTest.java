package com.yadony.api.admin.broadcast.segment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.broadcast.BroadcastAudienceService;
import com.yadony.api.admin.dto.BroadcastSegmentRequest;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("BroadcastSegmentService — chemins d'erreur")
class BroadcastSegmentServiceTest {

    private final BroadcastSegmentRepository repository = mock(BroadcastSegmentRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final BroadcastSegmentService service = new BroadcastSegmentService(
            repository, mock(BroadcastAudienceService.class), mock(AuditService.class), mapper);
    private final UUID adminId = UUID.randomUUID();

    @Test
    @DisplayName("règle stockée illisible : IllegalStateException")
    void regleStockeeIllisible() {
        UUID id = UUID.randomUUID();
        BroadcastSegmentEntity entity = new BroadcastSegmentEntity("n", null, "{pas du json", adminId);
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        assertThatThrownBy(() -> service.requireRule(id)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("course sur le nom : 409 broadcast-segment-name-taken")
    void courseSurLeNom() throws Exception {
        when(repository.existsByNameIgnoreCase(any())).thenReturn(false);
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk"));
        BroadcastSegmentRequest req = new BroadcastSegmentRequest("Nom", null, mapper.readTree(
                "{\"op\":\"AND\",\"children\":[{\"field\":\"kyc\",\"operator\":\"IN\",\"value\":[\"VERIFIED\"]}]}"));
        assertThatThrownBy(() -> service.create(req, adminId))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("broadcast-segment-name-taken"));
    }

    @Test
    @DisplayName("accesseurs de l'entité")
    void accesseurs() {
        BroadcastSegmentEntity e = new BroadcastSegmentEntity("n", "d", "{}", adminId);
        assertThat(e.getCreatedBy()).isEqualTo(adminId);
        assertThat(e.getUpdatedBy()).isEqualTo(adminId);
        assertThat(e.getName()).isEqualTo("n");
    }
}
