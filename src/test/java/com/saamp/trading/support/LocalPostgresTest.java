package com.saamp.trading.support;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;

/** Vérifie les destinations avant toute écriture et l'absence de repli sur public. */
class LocalPostgresTest {
    @Test void localOnlyAndOwnedSchemaRequired() throws Exception {
        assertThatThrownBy(()->LocalPostgres.validateUrl("jdbc:postgresql://example.invalid:5432/trading")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->LocalPostgres.validateUrl("jdbc:postgresql://127.0.0.1:5432/another")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->LocalPostgres.dataSource("public")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->LocalPostgres.dropSchema("public")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->LocalPostgres.dropSchema("saamp_test_00000000000000000000000000000000")).isInstanceOf(IllegalArgumentException.class);
        var source=LocalPostgres.dataSource();source.setUrl("jdbc:postgresql://example.invalid:5432/trading");
        assertThatThrownBy(source::getConnection).isInstanceOf(java.sql.SQLException.class);
        try(var c=LocalPostgres.connection()) {
            assertThatThrownBy(()->c.setSchema("public")).isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test void springLiquibaseAndDirectJdbcShareSchemaWithoutChangingPublic() throws Exception {
        var jdbc=new JdbcTemplate(LocalPostgres.dataSource());
        var before=jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename");
        var checksum=jdbc.queryForList("SELECT id,md5sum FROM public.databasechangelog WHERE id='013-risk-monitor'");
        try(var context=new AnnotationConfigApplicationContext(LocalPostgres.Context.class)) {
            var spring=new JdbcTemplate(context.getBean(DataSource.class));
            assertThat(spring.queryForObject("SELECT current_schema()",String.class)).isEqualTo(LocalPostgres.schema()).startsWith("saamp_test_").isNotEqualTo("public");
            assertThat(spring.queryForObject("SELECT COUNT(*) FROM databasechangelog",Integer.class)).isEqualTo(13);
            assertThat(spring.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='databasechangelog'",Integer.class,LocalPostgres.schema())).isEqualTo(1);
            try(var c=LocalPostgres.connection();var s=c.createStatement();var r=s.executeQuery("SELECT current_schema()")) {
                r.next();assertThat(r.getString(1)).isEqualTo(LocalPostgres.schema());
            }
            assertThat(jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT id,md5sum FROM public.databasechangelog WHERE id='013-risk-monitor'")).isEqualTo(checksum);
        }
    }

    @Test void cleanupDropsOnlyTheGeneratedSchema() throws Exception {
        var jdbc=new JdbcTemplate(LocalPostgres.dataSource());
        var before=jdbc.queryForList("SELECT oid,nspname FROM pg_namespace ORDER BY oid");
        String temporary=LocalPostgres.createSchema();
        var isolated=new JdbcTemplate(LocalPostgres.dataSource(temporary));
        assertThat(isolated.queryForObject("SELECT current_schema()",String.class)).isEqualTo(temporary);
        isolated.execute("CREATE TABLE isolation_probe(id INTEGER)");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='isolation_probe'",Integer.class)).isZero();
        LocalPostgres.dropSchema(temporary);
        assertThat(jdbc.queryForList("SELECT oid,nspname FROM pg_namespace ORDER BY oid")).isEqualTo(before);
        assertThatThrownBy(()->LocalPostgres.dataSource(temporary)).isInstanceOf(IllegalArgumentException.class);
    }
}
