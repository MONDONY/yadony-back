package com.yadony.api.addressbook.invitation;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contrainte de canal des invitations sur un vrai PostgreSQL (la suite H2 désactive Flyway, donc la contrainte
 * posée par V284 n'y existe pas) : une invitation par identifiant doit s'insérer, un canal inconnu non.
 * Régression : V391 n'avait pas élargi la contrainte, l'envoi répondait « envoyé » sans rien créer.
 */
@DisplayName("Invitations destinataire - contrainte de canal")
class RecipientInvitationChannelMigrationIT {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").cleanDisabled(false).load().migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static void insert(String channel) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            // Les clés étrangères ne sont pas le sujet : seule la contrainte de canal est vérifiée.
            s.execute("SET session_replication_role = replica");
            s.execute("INSERT INTO recipient_invitations (id, inviter_user_id, invitee_user_id, channel, "
                    + "target_hash, masked_target, status, created_at, updated_at) VALUES ("
                    + "gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '" + channel + "', "
                    + "md5(random()::text), 'x', 'PENDING', now(), now())");
        }
    }

    @Test
    @DisplayName("les canaux PHONE, EMAIL et USERNAME sont acceptés")
    void knownChannelsAreAccepted() throws Exception {
        insert("PHONE");
        insert("EMAIL");
        insert("USERNAME");
    }

    @Test
    @DisplayName("un canal inconnu est refusé par la contrainte")
    void unknownChannelIsRejected() {
        assertThatThrownBy(() -> insert("FAX")).isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_recipient_invitations_channel");
    }
}
