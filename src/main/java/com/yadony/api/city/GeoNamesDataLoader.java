package com.yadony.api.city;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

@Component
public class GeoNamesDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GeoNamesDataLoader.class);
    private static final int BATCH_SIZE = 500;

    private static final Map<String, String> COUNTRY_NAMES = Map.ofEntries(
        Map.entry("FR", "France"),
        Map.entry("SN", "Sénégal"),
        Map.entry("CI", "Côte d'Ivoire"),
        Map.entry("ML", "Mali"),
        Map.entry("CM", "Cameroun"),
        Map.entry("GN", "Guinée"),
        Map.entry("BF", "Burkina Faso"),
        Map.entry("TG", "Togo"),
        Map.entry("BJ", "Bénin"),
        Map.entry("NE", "Niger"),
        Map.entry("MR", "Mauritanie"),
        Map.entry("CD", "Congo (RDC)"),
        Map.entry("CG", "Congo"),
        Map.entry("GA", "Gabon"),
        Map.entry("TD", "Tchad"),
        Map.entry("MA", "Maroc"),
        Map.entry("DZ", "Algérie"),
        Map.entry("TN", "Tunisie"),
        Map.entry("MG", "Madagascar"),
        Map.entry("RE", "La Réunion"),
        Map.entry("MU", "Maurice"),
        Map.entry("GP", "Guadeloupe"),
        Map.entry("MQ", "Martinique"),
        Map.entry("US", "États-Unis"),
        Map.entry("CA", "Canada"),
        Map.entry("GB", "Royaume-Uni"),
        Map.entry("DE", "Allemagne"),
        Map.entry("ES", "Espagne"),
        Map.entry("IT", "Italie"),
        Map.entry("BE", "Belgique"),
        Map.entry("CH", "Suisse"),
        Map.entry("NL", "Pays-Bas"),
        Map.entry("PT", "Portugal"),
        Map.entry("BR", "Brésil"),
        Map.entry("CN", "Chine"),
        Map.entry("JP", "Japon"),
        Map.entry("IN", "Inde"),
        Map.entry("AE", "Émirats arabes unis"),
        Map.entry("SA", "Arabie Saoudite"),
        Map.entry("QA", "Qatar"),
        Map.entry("NG", "Nigéria"),
        Map.entry("GH", "Ghana"),
        Map.entry("KE", "Kenya"),
        Map.entry("TZ", "Tanzanie"),
        Map.entry("ET", "Éthiopie"),
        Map.entry("ZA", "Afrique du Sud"),
        Map.entry("EG", "Égypte"),
        Map.entry("LY", "Libye"),
        Map.entry("MX", "Mexique"),
        Map.entry("AR", "Argentine"),
        Map.entry("AU", "Australie"),
        Map.entry("RU", "Russie"),
        Map.entry("PL", "Pologne"),
        Map.entry("SE", "Suède"),
        Map.entry("NO", "Norvège"),
        Map.entry("DK", "Danemark"),
        Map.entry("AT", "Autriche")
    );

    private final CityRepository cityRepository;

    public GeoNamesDataLoader(CityRepository cityRepository) {
        this.cityRepository = cityRepository;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (cityRepository.count() > 0) {
            log.info("[GeoNames] Table cities déjà peuplée ({} entrées) — import ignoré",
                cityRepository.count());
            return;
        }

        log.info("[GeoNames] Démarrage de l'import des villes...");
        ClassPathResource resource = new ClassPathResource("geonames/cities5000.txt.gz");

        if (!resource.exists()) {
            log.warn("[GeoNames] Fichier geonames/cities5000.txt.gz introuvable — import ignoré");
            return;
        }

        List<CityEntity> batch = new ArrayList<>(BATCH_SIZE);
        long total = 0;
        long skipped = 0;

        try (var is = resource.getInputStream();
             var gzip = new GZIPInputStream(is);
             var reader = new BufferedReader(new InputStreamReader(gzip, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                String[] cols = line.split("\t", -1);
                if (cols.length < 15) continue;

                // Col 7 = feature_code. Garder uniquement les lieux peuplés (PPL*)
                String featureCode = cols[7].trim();
                if (!featureCode.startsWith("PPL")) {
                    skipped++;
                    continue;
                }

                try {
                    long geoId = Long.parseLong(cols[0].trim());
                    String name = cols[1].trim();
                    BigDecimal lat = new BigDecimal(cols[4].trim());
                    BigDecimal lng = new BigDecimal(cols[5].trim());
                    String countryCode = cols[8].trim().toUpperCase();
                    String popStr = cols[14].trim();
                    long population = popStr.isEmpty() ? 0L : Long.parseLong(popStr);

                    if (name.isEmpty() || countryCode.isEmpty()) continue;

                    String countryName = COUNTRY_NAMES.getOrDefault(countryCode, countryCode);

                    CityEntity entity = new CityEntity();
                    entity.setId(geoId);
                    entity.setName(name);
                    entity.setCountryCode(countryCode);
                    entity.setCountryName(countryName);
                    entity.setLatitude(lat);
                    entity.setLongitude(lng);
                    entity.setPopulation(population);
                    entity.setTimezone(timezoneOf(cols));
                    batch.add(entity);

                    if (batch.size() == BATCH_SIZE) {
                        cityRepository.saveAll(batch);
                        batch.clear();
                        total += BATCH_SIZE;
                        if (total % 10_000 == 0) {
                            log.info("[GeoNames] {} villes importées...", total);
                        }
                    }
                } catch (NumberFormatException | java.lang.ArithmeticException ignored) {
                    skipped++;
                }
            }

            if (!batch.isEmpty()) {
                cityRepository.saveAll(batch);
                total += batch.size();
            }
        }

        log.info("[GeoNames] Import terminé : {} villes chargées, {} lignes ignorées.", total, skipped);
    }

    /**
     * Fuseau IANA de la ligne GeoNames (colonne 18), ou {@code null} s'il manque ou dépasse
     * la colonne {@code cities.timezone}. Il place l'heure de départ d'un trajet dans le
     * fuseau de sa ville (V300).
     */
    static String timezoneOf(String[] cols) {
        if (cols.length <= 17) {
            return null;
        }
        String zone = cols[17].trim();
        return zone.isEmpty() || zone.length() > 40 ? null : zone;
    }
}
