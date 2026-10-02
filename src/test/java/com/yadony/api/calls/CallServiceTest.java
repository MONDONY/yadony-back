package com.yadony.api.calls;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.messaging.ConversationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CallServiceTest {

    @Mock CallEligibilityService eligibility;
    @Mock StreamClient stream;
    @Mock StreamTokenService tokens;
    @Mock CallRepository calls;
    @Mock UserRepository users;
    @Mock AuditService audit;
    @Mock CallExpiryService expiry;
    @Mock org.springframework.transaction.PlatformTransactionManager txManager;

    CallService service;
    UserEntity caller;
    UUID calleeId = UUID.randomUUID();
    ConversationEntity conv;

    @BeforeEach
    void setUp() {
        service = new CallService(eligibility, stream, tokens, calls, users, audit,
                new StreamProperties(true, "u", "key", "s", "audio_call", 3), expiry,
                new org.springframework.transaction.support.TransactionTemplate(txManager));
        caller = new UserEntity();
        ReflectionTestUtils.setField(caller, "id", UUID.randomUUID());
        caller.setFirstName("Awa");
        caller.setLastName("Diop");
        lenient().when(users.findByFirebaseUid("uid")).thenReturn(Optional.of(caller));
        UserEntity callee = new UserEntity();
        ReflectionTestUtils.setField(callee, "id", calleeId);
        callee.setFirstName("Moussa");
        lenient().when(users.findById(calleeId)).thenReturn(Optional.of(callee));
        conv = new ConversationEntity(UUID.randomUUID(), caller.getId(), calleeId, "fs");
        ReflectionTestUtils.setField(conv, "id", UUID.randomUUID());
        lenient().when(calls.saveAndFlush(any())).thenAnswer(i -> {
            CallEntity c = i.getArgument(0);
            ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            return c;
        });
    }

    private void allowed() {
        when(eligibility.check(caller.getId(), conv.getId()))
                .thenReturn(new CallEligibilityService.Eligibility(null, conv, calleeId));
    }

    private void denied(CallEligibilityService.Reason reason) {
        when(eligibility.check(caller.getId(), conv.getId()))
                .thenReturn(CallEligibilityService.Eligibility.denied(reason));
    }

    @Test
    void lanceLAppelEtLeTrace() {
        allowed();

        var res = service.start("uid", conv.getId());

        assertThat(res.callType()).isEqualTo("audio_call");
        verify(stream).upsertUsers(argThat(l -> l.size() == 2 && l.get(0).name().equals("Awa D.")
                && l.get(1).name().equals("Moussa")));
        verify(stream).createRingingCall(eq(res.callId()), eq(caller.getId()), eq(List.of(caller.getId(), calleeId)));
        ArgumentCaptor<CallEntity> saved = ArgumentCaptor.forClass(CallEntity.class);
        var order = inOrder(expiry, calls, stream);
        order.verify(expiry).expireStale(conv.getId());
        order.verify(calls).saveAndFlush(saved.capture());
        order.verify(stream).createRingingCall(any(), any(), any());
        assertThat(saved.getValue().getStreamCallId()).isEqualTo(res.callId());
        assertThat(saved.getValue().getBidId()).isEqualTo(conv.getBidId());
        verify(audit).log(eq("CALL"), any(), eq("CALL_STARTED"), eq(caller.getId()), anyMap());
    }

    @Test
    void refusHorsFenetreEn422() {
        denied(CallEligibilityService.Reason.OUT_OF_WINDOW);
        assertThatThrownBy(() -> service.start("uid", conv.getId()))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("call-out-of-window");
                });
        verifyNoInteractions(stream);
    }

    @Test
    void chaqueRefusALeBonStatutEtCode() {
        record Case(CallEligibilityService.Reason reason, HttpStatus status, String code) {}
        List<Case> cases = List.of(
                new Case(CallEligibilityService.Reason.NOT_PARTICIPANT, HttpStatus.FORBIDDEN, "call-not-participant"),
                new Case(CallEligibilityService.Reason.CONVERSATION_CLOSED, HttpStatus.UNPROCESSABLE_ENTITY, "call-conversation-closed"),
                new Case(CallEligibilityService.Reason.BLOCKED, HttpStatus.UNPROCESSABLE_ENTITY, "call-blocked"),
                new Case(CallEligibilityService.Reason.CALLEE_UNAVAILABLE, HttpStatus.UNPROCESSABLE_ENTITY, "call-callee-unavailable"),
                new Case(CallEligibilityService.Reason.CALLS_DISABLED, HttpStatus.SERVICE_UNAVAILABLE, "calls-disabled"));
        for (Case c : cases) {
            reset(eligibility);
            denied(c.reason());
            assertThatThrownBy(() -> service.start("uid", conv.getId()))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(c.status());
                        assertThat(e.getErrorCode()).isEqualTo(c.code());
                    });
        }
    }

    @Test
    void conversationInconnueEn404() {
        denied(CallEligibilityService.Reason.NOT_FOUND);
        assertThatThrownBy(() -> service.start("uid", conv.getId())).isInstanceOf(YadonyNotFoundException.class);
    }

    @Test
    void appelDejaEnCoursEn409() {
        allowed();
        when(calls.existsByConversationIdAndStatusIn(eq(conv.getId()), anyCollection())).thenReturn(true);
        assertThatThrownBy(() -> service.start("uid", conv.getId()))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(stream);
    }

    @Test
    void streamEnPanneEn503SansLigneOrpheline() {
        allowed();
        doThrow(new StreamClient.StreamUnavailableException("x", null)).when(stream).createRingingCall(any(), any(), any());
        assertThatThrownBy(() -> service.start("uid", conv.getId()))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getErrorCode()).isEqualTo("call-provider-unavailable");
                });
        verify(calls).discard(any(), any());
        verify(audit, never()).log(any(), any(), eq("CALL_STARTED"), any(), anyMap());
    }

    @Test
    void appelsSimultanesLeSecondRecoit409() {
        allowed();
        doThrow(new org.springframework.dao.DataIntegrityViolationException("uq")).when(calls).saveAndFlush(any());
        assertThatThrownBy(() -> service.start("uid", conv.getId()))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("call-already-in-progress");
                });
        verifyNoInteractions(stream);
    }

    @Test
    void utilisateurInconnuEn404() {
        assertThatThrownBy(() -> service.start("inconnu", conv.getId())).isInstanceOf(YadonyNotFoundException.class);
    }

    @Test
    void jetonPourLUtilisateurCourant() {
        Instant exp = Instant.parse("2026-10-03T10:00:00Z");
        when(tokens.userToken(caller.getId())).thenReturn(new StreamTokenService.IssuedToken("tok", exp));
        var res = service.token("uid");
        assertThat(res.apiKey()).isEqualTo("key");
        assertThat(res.userId()).isEqualTo(caller.getId().toString());
        assertThat(res.token()).isEqualTo("tok");
        assertThat(res.expiresAt()).isEqualTo(exp);
    }

    @Test
    void jetonRefuseSiNonConfigure() {
        service = new CallService(eligibility, stream, tokens, calls, users, audit,
                new StreamProperties(false, "u", "key", "s", "audio_call", 3), expiry,
                new org.springframework.transaction.support.TransactionTemplate(txManager));
        assertThatThrownBy(() -> service.token("uid"))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("calls-disabled"));
    }
}
