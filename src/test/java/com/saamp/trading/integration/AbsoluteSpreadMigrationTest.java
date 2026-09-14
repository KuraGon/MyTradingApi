package com.saamp.trading.integration;

import com.saamp.trading.support.LocalPostgres;
import liquibase.*;
import liquibase.database.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class AbsoluteSpreadMigrationTest {
    @Test void upgradePreservesHistoricalValuesAndFreezesOrderSnapshots() throws Exception {
        String schema=LocalPostgres.createSchema();
        try(var connection=LocalPostgres.dataSource(schema).getConnection()) {
            var jdbc=new JdbcTemplate(LocalPostgres.dataSource(schema));
            assertThat(jdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo(schema);
            Database db=DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            db.setDefaultSchemaName(schema);db.setLiquibaseSchemaName(schema);
            var migration=new Liquibase("db/changelog/db.changelog-master.yaml",new ClassLoaderResourceAccessor(),db);
            migration.update(15,new Contexts(),new LabelExpression());connection.setAutoCommit(true);
            jdbc.update("INSERT INTO trading_account(id,company_id,base_currency,as400_ste,as400_nucli_trading) VALUES(1,3,'EUR','B',10002)");
            jdbc.update("INSERT INTO trading_spread(company_id,asset,spread_buy,spread_sell,config_version) VALUES(3,'XAG',0.001234,0.005678,1)");
            jdbc.update("""
                INSERT INTO trading_order(account_id,company_id,asset,pair,side,requested_quantity,requested_unit,quantity_oz,status,idempotency_key,cl_ord_id,spread_applied,spread_config_version)
                VALUES(1,3,'XAG','XAGEUR','SELL',1,'OZ',1,'PENDING_UNKNOWN','OLD','OLD',0.005678,1)
                """);
            var oldValues=jdbc.queryForList("SELECT id,spread_buy,spread_sell,config_version FROM trading_spread");
            var history=jdbc.queryForList("SELECT id,md5sum FROM databasechangelog ORDER BY orderexecuted");
            migration.update(1,new Contexts(),new LabelExpression());connection.setAutoCommit(true);
            assertThat(jdbc.queryForList("SELECT id,spread_buy,spread_sell,config_version FROM trading_spread")).isEqualTo(oldValues);
            assertThat(jdbc.queryForList("SELECT id,md5sum FROM databasechangelog WHERE orderexecuted<=15 ORDER BY orderexecuted")).isEqualTo(history);
            assertThat(jdbc.queryForList("SELECT spread_type FROM trading_spread",String.class)).containsExactly("PERCENTAGE");
            assertThat(jdbc.queryForObject("SELECT spread_applied FROM trading_order",java.math.BigDecimal.class)).isEqualByComparingTo("0.005678");
            assertThat(jdbc.queryForObject("SELECT spread_quote_currency FROM trading_order",String.class)).isEqualTo("EUR");
            for(String assignment:new String[]{"spread_applied=0.1","spread_type='ABSOLUTE'","spread_config_version=2","pair='XAGUSD'","spread_quote_scale=2"})
                assertThatThrownBy(()->jdbc.update("UPDATE trading_order SET "+assignment)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            migration.rollback(1,new Contexts(),new LabelExpression());connection.setAutoCommit(true);
            assertThat(jdbc.queryForList("SELECT id,spread_buy,spread_sell,config_version FROM trading_spread")).isEqualTo(oldValues);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM databasechangelog",Integer.class)).isEqualTo(15);
            migration.update(1,new Contexts(),new LabelExpression());connection.setAutoCommit(true);
            jdbc.update("INSERT INTO trading_spread(company_id,asset,spread_buy,spread_sell,config_version,spread_type) VALUES(3,'XPT',12345678901234.123456,0,1,'ABSOLUTE')");
            assertThat(jdbc.queryForObject("SELECT spread_buy FROM trading_spread WHERE asset='XPT'",java.math.BigDecimal.class)).isEqualByComparingTo("12345678901234.123456");
            for(String values:new String[]{"'PERCENTAGE',0,0,'OZ'","'PERCENTAGE',1,1,'OZ'","'ABSOLUTE',-1,0,'OZ'","'ABSOLUTE',1,1,'KG'"})
                assertThatThrownBy(()->jdbc.update("INSERT INTO trading_spread(company_id,asset,config_version,spread_type,spread_buy,spread_sell,price_unit) VALUES(4,'XAU',1,"+values+")")).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->migration.rollback(1,new Contexts(),new LabelExpression())).isInstanceOf(liquibase.exception.LiquibaseException.class).hasStackTraceContaining("ABSOLUTE_SPREAD_ROLLBACK_REQUIRES_MANUAL_REVIEW");
            connection.rollback();connection.setAutoCommit(true);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM databasechangelog",Integer.class)).isEqualTo(16);
        } finally { LocalPostgres.dropSchema(schema); }
    }
}
