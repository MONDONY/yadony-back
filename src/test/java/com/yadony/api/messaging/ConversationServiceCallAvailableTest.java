package com.yadony.api.messaging;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.CallAvailability;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** callAvailable : l'app n'affiche le bouton d'appel que si la règle d'éligibilité l'autorise. */
@ExtendWith(MockitoExtension.class)
class ConversationServiceCallAvailableTest {

    @Mock ConversationRepository conversationRepository;
    @Mock FirestoreService firestoreService;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock StorageService storageService;
    @Mock BlockVisibility blockVisibility;
    @Mock CallAvailability callAvailability;

    ConversationService service;
    UUID senderId = UUID.randomUUID();
    ConversationEntity conv;

    @BeforeEach
    void setUp() {
        service = new ConversationService(conversationRepository, firestoreService, userRepository, auditService,
                bidRepository, announcementRepository, storageService, blockVisibility, callAvailability);
        conv = new ConversationEntity(UUID.randomUUID(), senderId, UUID.randomUUID(), "fs");
        ReflectionTestUtils.setField(conv, "id", UUID.randomUUID());
    }

    @Test
    void vraiQuandLAppelEstAutorise() {
        when(callAvailability.canCall(senderId, conv.getId())).thenReturn(true);
        assertThat(service.toResponse(conv, senderId, Map.of()).callAvailable()).isTrue();
    }

    @Test
    void fauxQuandLAppelEstRefuse() {
        when(callAvailability.canCall(senderId, conv.getId())).thenReturn(false);
        assertThat(service.toResponse(conv, senderId, Map.of()).callAvailable()).isFalse();
    }

    @Test
    void fauxSansLeverQuandLaRegleEchoue() {
        when(callAvailability.canCall(any(), any())).thenThrow(new RuntimeException("db"));
        assertThat(service.toResponse(conv, senderId, Map.of()).callAvailable()).isFalse();
    }
}
