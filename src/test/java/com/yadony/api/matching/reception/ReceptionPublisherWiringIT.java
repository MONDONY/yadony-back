package com.yadony.api.matching.reception;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le publisher de {@code ReceptionService} et {@code ReceptionLinker} est injecté par setter, avec un
 * défaut sans effet : si Spring ne l'appelait pas, le push « code prêt » serait coupé en silence.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReceptionPublisherWiringIT {

    @Autowired ReceptionService receptionService;
    @Autowired ReceptionLinker receptionLinker;
    @Autowired ApplicationContext context;

    @Test
    void publishersAreWiredByTheContext() {
        Object service = AopTestUtils.getUltimateTargetObject(receptionService);
        Object linker = AopTestUtils.getUltimateTargetObject(receptionLinker);
        assertThat(ReflectionTestUtils.getField(service, "eventPublisher")).isSameAs(context);
        assertThat(ReflectionTestUtils.getField(linker, "eventPublisher")).isSameAs(context);
    }
}
