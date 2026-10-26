package com.yadony.api.support;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportAdminToolsServiceTest {

    private static final UUID TICKET_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID ADMIN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TEMPLATE_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    @Mock SupportTicketRepository ticketRepository;
    @Mock SupportTicketNoteRepository noteRepository;
    @Mock SupportReplyTemplateRepository templateRepository;
    @Mock AuditService auditService;

    private SupportAdminToolsService service() {
        return new SupportAdminToolsService(ticketRepository, noteRepository, templateRepository, auditService);
    }

    private void ticketExists() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(new SupportTicketEntity()));
    }

    private static SupportReplyTemplateEntity template(String label, int order) {
        SupportReplyTemplateEntity t = new SupportReplyTemplateEntity();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setLabel(label);
        t.setContent("Texte");
        t.setSortOrder(order);
        return t;
    }

    private static int status(Throwable e) {
        return ((YadonyBusinessException) e).getStatus().value();
    }

    // ------------------------------------------------------------------ notes

    @Test
    void addNote_savesTheTrimmedNote_andTracesItWithoutItsText() {
        ticketExists();
        when(noteRepository.save(any(SupportTicketNoteEntity.class))).thenAnswer(i -> {
            SupportTicketNoteEntity n = i.getArgument(0);
            ReflectionTestUtils.setField(n, "id", UUID.fromString("77777777-7777-7777-7777-777777777777"));
            return n;
        });

        SupportTicketNoteEntity note = service().addNote(TICKET_ID, ADMIN_ID, "  Appel passé, sans réponse  ");

        assertThat(note.getContent()).isEqualTo("Appel passé, sans réponse");
        assertThat(note.getTicketId()).isEqualTo(TICKET_ID);
        assertThat(note.getAdminId()).isEqualTo(ADMIN_ID);
        verify(auditService).log(eq("support_ticket"), eq(TICKET_ID), eq("SUPPORT_TICKET_NOTE_ADDED"), eq(ADMIN_ID),
                eq(Map.of("noteId", "77777777-7777-7777-7777-777777777777")));
    }

    @Test
    void addNote_refusesATooShortOrTooLongOrMissingNote() {
        ticketExists();
        for (String bad : new String[] {null, "", "  ab  ", "x".repeat(1001)}) {
            assertThatThrownBy(() -> service().addNote(TICKET_ID, ADMIN_ID, bad))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        assertThat(status(e)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
                        assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("support-note-invalid");
                    });
        }
        verify(noteRepository, never()).save(any());
    }

    @Test
    void notes_ofAnUnknownTicketAre404() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().listNotes(TICKET_ID)).satisfies(e -> assertThat(status(e)).isEqualTo(404));
        assertThatThrownBy(() -> service().addNote(TICKET_ID, ADMIN_ID, "Une note")).satisfies(e -> assertThat(status(e)).isEqualTo(404));
        verifyNoInteractions(noteRepository);
    }

    @Test
    void listNotes_returnsTheNewestFirst() {
        ticketExists();
        SupportTicketNoteEntity note = new SupportTicketNoteEntity();
        when(noteRepository.findByTicketIdOrderByCreatedAtDesc(TICKET_ID)).thenReturn(List.of(note));

        assertThat(service().listNotes(TICKET_ID)).containsExactly(note);
    }

    // ------------------------------------------------------- réponses types

    @Test
    void createTemplate_goesLastInTheOrder_andIsTraced() {
        when(templateRepository.findAllByOrderBySortOrderAscCreatedAtAsc()).thenReturn(List.of(template("A", 10), template("B", 30)));
        when(templateRepository.save(any(SupportReplyTemplateEntity.class))).thenAnswer(i -> {
            SupportReplyTemplateEntity t = i.getArgument(0);
            ReflectionTestUtils.setField(t, "id", TEMPLATE_ID);
            return t;
        });

        SupportReplyTemplateEntity created = service().createTemplate(ADMIN_ID, "  Merci  ", "  Merci de votre patience.  ");

        assertThat(created.getLabel()).isEqualTo("Merci");
        assertThat(created.getContent()).isEqualTo("Merci de votre patience.");
        assertThat(created.getSortOrder()).isEqualTo(40);
        verify(auditService).log(eq("support_reply_template"), eq(TEMPLATE_ID), eq("SUPPORT_REPLY_TEMPLATE_CREATED"), eq(ADMIN_ID), eq(Map.of("label", "Merci")));
    }

    @Test
    void createTemplate_firstOneStartsTheOrder() {
        when(templateRepository.findAllByOrderBySortOrderAscCreatedAtAsc()).thenReturn(List.of());
        when(templateRepository.save(any(SupportReplyTemplateEntity.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service().createTemplate(ADMIN_ID, "Merci", "Texte").getSortOrder()).isEqualTo(10);
    }

    @Test
    void template_refusesAMissingOrTooLongLabelOrContent() {
        for (String[] bad : new String[][] {{null, "x"}, {"", "x"}, {"x".repeat(61), "x"}, {"ok", null}, {"ok", "  "}, {"ok", "x".repeat(4001)}}) {
            assertThatThrownBy(() -> service().createTemplate(ADMIN_ID, bad[0], bad[1]))
                    .satisfies(e -> {
                        assertThat(status(e)).isEqualTo(422);
                        assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("support-template-invalid");
                    });
        }
        verify(templateRepository, never()).save(any());
    }

    @Test
    void updateTemplate_changesLabelAndContent_keepingItsPlace() {
        SupportReplyTemplateEntity existing = template("Ancien", 20);
        ReflectionTestUtils.setField(existing, "id", TEMPLATE_ID);
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(existing));
        when(templateRepository.save(existing)).thenReturn(existing);

        SupportReplyTemplateEntity updated = service().updateTemplate(TEMPLATE_ID, ADMIN_ID, "Nouveau", "Nouveau texte");

        assertThat(updated.getLabel()).isEqualTo("Nouveau");
        assertThat(updated.getContent()).isEqualTo("Nouveau texte");
        assertThat(updated.getSortOrder()).isEqualTo(20);
        verify(auditService).log(eq("support_reply_template"), eq(TEMPLATE_ID), eq("SUPPORT_REPLY_TEMPLATE_UPDATED"), eq(ADMIN_ID), eq(Map.of("label", "Nouveau")));
    }

    @Test
    void deleteTemplate_isASoftDelete() {
        SupportReplyTemplateEntity existing = template("Clôture", 50);
        ReflectionTestUtils.setField(existing, "id", TEMPLATE_ID);
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(existing));

        service().deleteTemplate(TEMPLATE_ID, ADMIN_ID);

        ArgumentCaptor<SupportReplyTemplateEntity> saved = ArgumentCaptor.forClass(SupportReplyTemplateEntity.class);
        verify(templateRepository).save(saved.capture());
        assertThat(saved.getValue().getDeletedAt()).isNotNull();
        verify(templateRepository, never()).delete(any(SupportReplyTemplateEntity.class));
        verify(auditService).log(eq("support_reply_template"), eq(TEMPLATE_ID), eq("SUPPORT_REPLY_TEMPLATE_DELETED"), eq(ADMIN_ID), eq(Map.of("label", "Clôture")));
    }

    @Test
    void updateAndDeleteOfAnUnknownTemplateAre404() {
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateTemplate(TEMPLATE_ID, ADMIN_ID, "a", "b")).satisfies(e -> assertThat(status(e)).isEqualTo(404));
        assertThatThrownBy(() -> service().deleteTemplate(TEMPLATE_ID, ADMIN_ID)).satisfies(e -> assertThat(status(e)).isEqualTo(404));
    }

    @Test
    void listTemplates_returnsThemInOrder() {
        SupportReplyTemplateEntity t = template("A", 10);
        when(templateRepository.findAllByOrderBySortOrderAscCreatedAtAsc()).thenReturn(List.of(t));

        assertThat(service().listTemplates()).containsExactly(t);
    }
}
