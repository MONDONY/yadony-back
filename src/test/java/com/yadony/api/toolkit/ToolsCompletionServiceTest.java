package com.yadony.api.toolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.yadony.api.toolkit.ToolsCompletionResponse.ToolStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolsCompletionServiceTest {

    @Mock private ToolsCompletionQueryRepository repository;
    @InjectMocks private ToolsCompletionService service;

    private final UUID userId = UUID.randomUUID();

    @Test
    void maps_counts_to_ordered_tools_and_ready_flags() {
        when(repository.countAddresses(userId)).thenReturn(2L);
        when(repository.countRecipients(userId)).thenReturn(0L);
        when(repository.countAlerts(userId)).thenReturn(0L);
        when(repository.countTripTemplates(userId)).thenReturn(1L);
        when(repository.countPriceGridItems(userId)).thenReturn(6L);

        ToolsCompletionResponse response = service.compute(userId);

        assertThat(response.total()).isEqualTo(5);
        assertThat(response.ready()).isEqualTo(3);
        assertThat(response.tools()).extracting(ToolStatus::key)
                .containsExactly("addresses", "recipients", "alerts", "trip_templates", "price_grid");
        assertThat(response.tools()).extracting(ToolStatus::count)
                .containsExactly(2L, 0L, 0L, 1L, 6L);
        assertThat(response.tools()).extracting(ToolStatus::ready)
                .containsExactly(true, false, false, true, true);
    }

    @Test
    void nothing_configured_gives_zero_ready_and_five_tools() {
        when(repository.countAddresses(userId)).thenReturn(0L);
        when(repository.countRecipients(userId)).thenReturn(0L);
        when(repository.countAlerts(userId)).thenReturn(0L);
        when(repository.countTripTemplates(userId)).thenReturn(0L);
        when(repository.countPriceGridItems(userId)).thenReturn(0L);

        ToolsCompletionResponse response = service.compute(userId);

        assertThat(response.ready()).isZero();
        assertThat(response.tools()).hasSize(5).allMatch(t -> !t.ready());
    }

    @Test
    void every_tool_configured_gives_five_ready() {
        when(repository.countAddresses(userId)).thenReturn(1L);
        when(repository.countRecipients(userId)).thenReturn(1L);
        when(repository.countAlerts(userId)).thenReturn(1L);
        when(repository.countTripTemplates(userId)).thenReturn(1L);
        when(repository.countPriceGridItems(userId)).thenReturn(1L);

        assertThat(service.compute(userId).ready()).isEqualTo(5);
    }

    @Test
    void tool_keys_are_stable_api_identifiers() {
        assertThat(List.of(ToolKey.values())).extracting(ToolKey::apiKey)
                .containsExactly("addresses", "recipients", "alerts", "trip_templates", "price_grid");
    }
}
