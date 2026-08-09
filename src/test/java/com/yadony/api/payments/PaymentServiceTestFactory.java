package com.yadony.api.payments;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.promo.PromoService;
import com.yadony.api.config.StripeConnectProperties;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.matching.BidRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class PaymentServiceTestFactory {

    /**
     * Coordonnées de test servies par Firebase — elles ne sont plus stockées en base
     * (cf. {@link com.yadony.api.auth.FirebaseContactService}). Stripe lit l'email par ce
     * canal, donc tout PaymentService de test doit recevoir un contact non vide.
     */
    static com.yadony.api.auth.FirebaseContactService stubbedContacts() {
        var svc = mock(com.yadony.api.auth.FirebaseContactService.class);
        lenient().when(svc.getContact(any())).thenReturn(
                new com.yadony.api.auth.FirebaseContactService.Contact("+33600000000", "test@yadony.app"));
        return svc;
    }


    /**
     * Sets the {@code id} field on a {@link com.yadony.api.common.BaseEntity} subclass via reflection.
     * Walks the class hierarchy until it finds the field, consistent with how BaseEntity declares it.
     */
    static void setId(Object entity, java.util.UUID id) {
        try {
            Class<?> clazz = entity.getClass();
            java.lang.reflect.Field f = null;
            while (clazz != null) {
                try { f = clazz.getDeclaredField("id"); break; }
                catch (NoSuchFieldException e) { clazz = clazz.getSuperclass(); }
            }
            if (f == null) throw new NoSuchFieldException("id not found in class hierarchy");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException("Could not set id via reflection", e);
        }
    }

    static StripeConnectProperties defaultConnectProperties() {
        return new StripeConnectProperties(
                "4215",
                "Transport de colis entre particuliers via la plateforme Yadony",
                "https://yadony.app",
                "http://localhost:8080/api/v1/payments/onboarding/return",
                "http://localhost:8080/api/v1/payments/onboarding/refresh",
                "yadony://stripe/onboarding/complete",
                "yadony://stripe/onboarding/refresh"
        );
    }

    static PaymentService bare() {
        return new PaymentService(
                mock(UserRepository.class),
                mock(BidRepository.class),
                mock(BidGridItemRepository.class),
                mock(AnnouncementRepository.class),
                mock(PaymentRepository.class),
                mock(AuditService.class),
                mock(ApplicationEventPublisher.class),
                defaultConnectProperties(),
                new ObjectMapper(),
                mock(AdminAlertService.class),
                stubbedResolver(),
                mock(PromoService.class),
                new StripeGatewayImpl(),
                PaymentServiceTestFactory.stubbedContacts(),
                mock(com.yadony.api.settings.UserBusinessPrefsRepository.class),
                new com.yadony.api.payments.currency.CurrencyMatchGuard()
        );
    }

    /** Résolveur de taux mocké, stubé au taux global 12 % (suffisant pour les tests). */
    static CommissionRateResolver stubbedResolver() {
        CommissionRateResolver r = mock(CommissionRateResolver.class);
        lenient().when(r.resolve(any())).thenReturn(new BigDecimal("0.12"));
        lenient().when(r.resolve(any(), any())).thenReturn(new BigDecimal("0.12"));
        return r;
    }

    /**
     * Overload that accepts specific mocks for targeted handler unit tests.
     * Repositories and services not provided are mocked internally.
     */
    static PaymentService bare(PaymentRepository paymentRepository,
                               UserRepository userRepository,
                               AuditService auditService,
                               AdminAlertService adminAlert) {
        return new PaymentService(
                userRepository,
                mock(BidRepository.class),
                mock(BidGridItemRepository.class),
                mock(AnnouncementRepository.class),
                paymentRepository,
                auditService,
                mock(ApplicationEventPublisher.class),
                defaultConnectProperties(),
                new ObjectMapper(),
                adminAlert,
                stubbedResolver(),
                mock(PromoService.class),
                new StripeGatewayImpl(),
                PaymentServiceTestFactory.stubbedContacts(),
                mock(com.yadony.api.settings.UserBusinessPrefsRepository.class),
                new com.yadony.api.payments.currency.CurrencyMatchGuard()
        );
    }
}
