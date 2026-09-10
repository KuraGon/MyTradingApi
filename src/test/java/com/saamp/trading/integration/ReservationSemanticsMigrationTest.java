package com.saamp.trading.integration;

import com.saamp.trading.support.LocalPostgres;
import java.util.UUID;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;

/** VÃ©rifie la bascule 010 vers 011 sans modifier les changesets ni les engagements historiques. */
class ReservationSemanticsMigrationTest {
    @Test void backfillPreservesPendingCommitmentsAndAddsExplicitUniqueKinds() throws Exception {
        try (var fixture=new Schema()) {
            var connection=fixture.connection;
            var source=fixture.source;
            var jdbc=fixture.jdbc;
            String schema=fixture.name;
                migrateThrough010(connection,schema);
                jdbc.update("INSERT INTO trading_account(id,company_id,base_currency,status) VALUES (1,1,'EUR','ACTIVE')");
                for (int id=1;id<=3;id++) {
                    String state=id==1?"PENDING":id==2?"PENDING_UNKNOWN":"DRAFT";
                    jdbc.update("""
                            INSERT INTO trading_order(id,account_id,company_id,asset,pair,side,requested_quantity,requested_unit,quantity_oz,status,idempotency_key,cl_ord_id)
                            VALUES (?,1,1,'XAU','XAUEUR','BUY',1,'OZ',1,?,?,?)
                            """,id,state,"KEY-"+id,"CL-"+id);
                    jdbc.update("INSERT INTO trading_reservation(account_id,asset,quantity,order_id,status,expires_at) VALUES (1,'EUR',100,?,'ACTIVE',NOW()-INTERVAL '1 day')",id);
                }
                jdbc.update("INSERT INTO trading_reservation(account_id,asset,quantity,status,expires_at) VALUES (1,'EUR',12.345678,'ACTIVE',NOW()-INTERVAL '1 day')");
                jdbc.update("INSERT INTO trading_reservation(account_id,asset,quantity,status,expires_at) VALUES (1,'XAU',2.123456,'RELEASED',NOW()-INTERVAL '1 day')");
                jdbc.update("INSERT INTO trading_reservation(account_id,asset,quantity,status,expires_at) VALUES (1,'EUR',3,'CONSUMED',NOW()-INTERVAL '1 day')");
                jdbc.update("INSERT INTO trading_reservation(account_id,asset,quantity,status,expires_at) VALUES (1,'EUR',4,'EXPIRED',NOW()-INTERVAL '1 day')");
                var before=jdbc.queryForList("SELECT id,account_id,asset,quantity,order_id,status,expires_at,created_at FROM trading_reservation ORDER BY id");
                migrateCount(connection,schema,1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM databasechangelog",Integer.class)).isEqualTo(11);
                assertThat(jdbc.queryForList("SELECT id,account_id,asset,quantity,order_id,status,expires_at,created_at FROM trading_reservation ORDER BY id")).isEqualTo(before);
                assertThat(jdbc.queryForList("SELECT reservation_kind FROM trading_reservation",String.class)).containsOnly("LEGACY").hasSize(7);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM databasechangelog WHERE id='011-reservation-semantics' AND exectype='EXECUTED'",Integer.class)).isEqualTo(1);
                var reservations=new com.saamp.trading.reservation.ReservationRepository(jdbc);
                assertThat(reservations.cashReserved(1,com.saamp.trading.domain.Asset.EUR,-1)).isEqualByComparingTo("212.345678");
                assertThat(reservations.riskReserved(1,com.saamp.trading.domain.Asset.EUR,-1)).isEqualByComparingTo("212.345678");
                connection.setAutoCommit(false);
                try { assertThat(reservations.expireDue(java.time.Duration.ofMinutes(2))).isEqualTo(1); connection.commit(); }
                finally { connection.setAutoCommit(true); }
                assertThat(jdbc.queryForList("SELECT status FROM trading_reservation WHERE order_id IN (1,2) OR (order_id IS NULL AND status='ACTIVE')",String.class))
                        .containsOnly("ACTIVE").hasSize(3);
                assertThatThrownBy(()->jdbc.update("INSERT INTO trading_reservation(account_id,asset,quantity,order_id,status,expires_at) VALUES (1,'EUR',1,1,'ACTIVE',NOW())"))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
                jdbc.update("INSERT INTO trading_reservation(account_id,asset,quantity,order_id,status,expires_at,reservation_kind) VALUES (1,'EUR',1,1,'ACTIVE',NOW(),'CASH')");
                jdbc.update("INSERT INTO trading_reservation(account_id,asset,quantity,order_id,status,expires_at,reservation_kind) VALUES (1,'EUR',1,1,'ACTIVE',NOW(),'RISK')");
                assertThatThrownBy(()->jdbc.update("INSERT INTO trading_reservation(account_id,asset,quantity,order_id,status,expires_at,reservation_kind) VALUES (1,'EUR',2,1,'ACTIVE',NOW(),'CASH')"))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThatThrownBy(()->jdbc.update("UPDATE trading_reservation SET reservation_kind='UNKNOWN' WHERE id=1"))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }

    @Test void completeInstallationUsesTheRealMasterThrough013() throws Exception {
        try (var fixture=new Schema()) {
            migrate(fixture.source,fixture.name,"db.changelog-master.yaml");
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM databasechangelog WHERE exectype='EXECUTED'",Integer.class)).isEqualTo(13);
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name='trading_reservation' AND column_name='reservation_kind' AND is_nullable='NO'",Integer.class,fixture.name)).isEqualTo(1);
        }
    }

    @Test void historicalDuplicatesBlock011AndRollbackWithoutDeletingOrReleasingAnything() throws Exception {
        try (var fixture=new Schema()) {
            migrateThrough010(fixture.connection,fixture.name);
            fixture.jdbc.update("INSERT INTO trading_account(id,company_id,base_currency,status) VALUES (1,1,'EUR','ACTIVE')");
            fixture.jdbc.update("""
                    INSERT INTO trading_order(id,account_id,company_id,asset,pair,side,requested_quantity,requested_unit,quantity_oz,status,idempotency_key,cl_ord_id)
                    VALUES (1,1,1,'XAU','XAUEUR','BUY',1,'OZ',1,'PENDING_UNKNOWN','DUPLICATE','DUPLICATE')
                    """);
            for (int i=0;i<2;i++) fixture.jdbc.update("INSERT INTO trading_reservation(account_id,asset,quantity,order_id,status,expires_at) VALUES (1,'EUR',100,1,'ACTIVE',NOW()-INTERVAL '1 day')");
            var before=fixture.jdbc.queryForList("SELECT * FROM trading_reservation ORDER BY id");
            assertThatThrownBy(()->migrate(fixture.source,fixture.name,"db.changelog-master.yaml")).isInstanceOf(liquibase.exception.LiquibaseException.class);
            assertThat(fixture.jdbc.queryForList("SELECT * FROM trading_reservation ORDER BY id")).isEqualTo(before);
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM databasechangelog WHERE id='011-reservation-semantics'",Integer.class)).isZero();
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name='trading_reservation' AND column_name='reservation_kind'",Integer.class,fixture.name)).isZero();
        }
    }

    @Test void errorCodeWideningPreservesExistingCodesAndAcceptsUncertaintyMarkers() throws Exception {
        try (var fixture=new Schema()) {
            migrateCount(fixture.connection,fixture.name,11);
            fixture.jdbc.update("INSERT INTO trading_account(id,company_id,base_currency,status) VALUES (1,1,'EUR','ACTIVE')");
            fixture.jdbc.update("""
                    INSERT INTO trading_order(id,account_id,company_id,asset,pair,side,requested_quantity,requested_unit,quantity_oz,status,idempotency_key,cl_ord_id,stonex_error_code)
                    VALUES (1,1,1,'XAU','XAUEUR','BUY',1,'OZ',1,'PENDING_UNKNOWN','WIDTH','WIDTH','631')
                    """);
            var before=fixture.jdbc.queryForList("SELECT * FROM trading_order");
            migrateCount(fixture.connection,fixture.name,1);
            assertThat(fixture.jdbc.queryForList("SELECT * FROM trading_order")).isEqualTo(before);
            fixture.jdbc.update("UPDATE trading_order SET stonex_error_code='PROVIDER_UNCERTAIN' WHERE id=1");
            fixture.jdbc.update("UPDATE trading_order SET stonex_error_code='MANUAL_REVIEW_REQUIRED' WHERE id=1");
            assertThat(fixture.jdbc.queryForObject("SELECT stonex_error_code FROM trading_order WHERE id=1",String.class)).isEqualTo("MANUAL_REVIEW_REQUIRED");
        }
    }

    private void migrateThrough010(java.sql.Connection connection,String schema) throws Exception {
        migrateCount(connection,schema,10);
    }

    private void migrateCount(java.sql.Connection connection,String schema,int count) throws Exception {
        verifySchema(new JdbcTemplate(new SingleConnectionDataSource(connection,true)),schema);
        var database=liquibase.database.DatabaseFactory.getInstance().findCorrectDatabaseImplementation(
                new liquibase.database.jvm.JdbcConnection(connection));
        database.setDefaultSchemaName(schema);
        database.setLiquibaseSchemaName(schema);
        // Le master rÃ©el est lu ; le compteur borne exactement la transition historique testÃ©e.
        var runner=new liquibase.Liquibase("db/changelog/db.changelog-master.yaml",
                new liquibase.resource.ClassLoaderResourceAccessor(),database);
        runner.update(count,new liquibase.Contexts(),new liquibase.LabelExpression());
        // Les fixtures historiques doivent Ãªtre validÃ©es avant le prochain passage Liquibase.
        connection.setAutoCommit(true);
        verifySchema(new JdbcTemplate(new SingleConnectionDataSource(connection,true)),schema);
    }

    private static final class Schema implements AutoCloseable {
        final String name=LocalPostgres.createSchema();
        final java.sql.Connection connection=LocalPostgres.dataSource(name).getConnection();
        final SingleConnectionDataSource source=new SingleConnectionDataSource(connection,true);
        final JdbcTemplate jdbc=new JdbcTemplate(source);
        Schema() throws Exception {
            connection.setSchema(name);
            verifySchema(jdbc,name);
        }
        @Override public void close() throws Exception {
            try {
                verifySchema(jdbc,name);
                LocalPostgres.dropSchema(name);
            } finally { connection.close(); }
        }
    }

    private static void verifySchema(JdbcTemplate jdbc,String schema) {
        assertThat(schema).matches("saamp_test_[a-f0-9]{32}");
        assertThat(jdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo(schema).isNotEqualTo("public");
    }

    private void migrate(SingleConnectionDataSource source,String schema,String file) throws Exception {
        verifySchema(new JdbcTemplate(source),schema);
        var liquibase=new SpringLiquibase();
        liquibase.setDataSource(source);
        liquibase.setDefaultSchema(schema);
        liquibase.setLiquibaseSchema(schema);
        liquibase.setChangeLog("classpath:db/changelog/"+file);
        liquibase.afterPropertiesSet();
        verifySchema(new JdbcTemplate(source),schema);
    }
}
