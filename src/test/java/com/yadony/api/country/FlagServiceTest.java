package com.yadony.api.country;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlagServiceTest {

    @Mock private CountryRepository countryRepository;

    @InjectMocks private FlagService service;

    @Test
    void getFlag_absent_computesPersistsAndReturns() {
        when(countryRepository.findById("US")).thenReturn(Optional.empty());

        String flag = service.getFlag("US");

        assertThat(flag).isEqualTo("🇺🇸"); // 🇺🇸

        verify(countryRepository).insertIfAbsent("US", null, "🇺🇸");
        verify(countryRepository, never()).save(any());
    }

    @Test
    void getFlag_inReadOnlyTransaction_computesWithoutWriting() {
        // Liste « Mes trajets » en readOnly : Postgres refuserait l'INSERT et ferait
        // tomber toute la liste. Le drapeau est rendu, la persistance attend une écriture.
        CountryEntity withoutFlag = new CountryEntity();
        withoutFlag.setCountryCode("SN");
        when(countryRepository.findById("ML")).thenReturn(Optional.empty());
        when(countryRepository.findById("SN")).thenReturn(Optional.of(withoutFlag));
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try {
            assertThat(service.getFlag("ML")).isEqualTo("🇲🇱");
            assertThat(service.getFlag("SN")).isEqualTo("🇸🇳");
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        }

        verify(countryRepository, never()).insertIfAbsent(anyString(), any(), anyString());
        verify(countryRepository, never()).save(any());
    }

    @Test
    void getFlag_lowercase_normalizesToUpper() {
        when(countryRepository.findById("US")).thenReturn(Optional.empty());

        String flag = service.getFlag("us");

        assertThat(flag).isEqualTo("🇺🇸"); // 🇺🇸
        verify(countryRepository).insertIfAbsent("US", null, "🇺🇸");
    }

    @Test
    void getFlag_italy_returnsItalianFlag() {
        when(countryRepository.findById("IT")).thenReturn(Optional.empty());

        String flag = service.getFlag("IT");

        assertThat(flag).isEqualTo("🇮🇹"); // 🇮🇹
        verify(countryRepository).insertIfAbsent("IT", null, "🇮🇹");
    }

    @Test
    void getFlag_france_withName_persistsName() {
        when(countryRepository.findById("FR")).thenReturn(Optional.empty());

        String flag = service.getFlag("FR", "France");

        assertThat(flag).isEqualTo("🇫🇷"); // 🇫🇷
        verify(countryRepository).insertIfAbsent("FR", "France", "🇫🇷");
    }

    @Test
    void getFlag_existingWithFlag_returnsStoredWithoutSaving() {
        CountryEntity existing = new CountryEntity();
        existing.setCountryCode("DE");
        existing.setCountryName("Germany");
        existing.setFlag("STORED");
        when(countryRepository.findById("DE")).thenReturn(Optional.of(existing));

        String flag = service.getFlag("DE");

        assertThat(flag).isEqualTo("STORED");
        verify(countryRepository, never()).save(any());
    }

    @Test
    void getFlag_existingWithNullFlag_computesAndSaves() {
        CountryEntity existing = new CountryEntity();
        existing.setCountryCode("IT");
        existing.setFlag(null);
        when(countryRepository.findById("IT")).thenReturn(Optional.of(existing));
        when(countryRepository.save(any(CountryEntity.class))).thenAnswer(i -> i.getArgument(0));

        String flag = service.getFlag("IT");

        assertThat(flag).isEqualTo("🇮🇹"); // 🇮🇹
        verify(countryRepository).save(existing);
        assertThat(existing.getFlag()).isEqualTo("🇮🇹");
    }

    @Test
    void getFlag_null_returnsNullNoSave() {
        assertThat(service.getFlag(null)).isNull();
        verify(countryRepository, never()).save(any());
    }

    @Test
    void getFlag_empty_returnsNullNoSave() {
        assertThat(service.getFlag("")).isNull();
        verify(countryRepository, never()).save(any());
    }

    @Test
    void getFlag_threeChars_returnsNullNoSave() {
        assertThat(service.getFlag("USA")).isNull();
        verify(countryRepository, never()).save(any());
    }

    @Test
    void getFlag_nonLetter_returnsNullNoSave() {
        assertThat(service.getFlag("U1")).isNull();
        verify(countryRepository, never()).save(any());
    }

    @Test
    void getFlag_blankWithSpaces_returnsNullNoSave() {
        assertThat(service.getFlag("  ")).isNull();
        verify(countryRepository, never()).findById(anyString());
        verify(countryRepository, never()).save(any());
    }

    @Test
    void emojiFromIso_pureHelper() {
        assertThat(FlagService.emojiFromIso("US")).isEqualTo("🇺🇸");
        assertThat(FlagService.emojiFromIso("FR")).isEqualTo("🇫🇷");
    }
}
