package com.saamp.trading.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExecutionGateRepository {
    private final JdbcTemplate jdbc;
    public ExecutionGateRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean isOpen() {
        Boolean open = jdbc.queryForObject("SELECT open FROM trading_execution_gate WHERE id=1", Boolean.class);
        return Boolean.TRUE.equals(open);
    }

    public void close(String reason) {
        jdbc.update("UPDATE trading_execution_gate SET open=FALSE,reason=?,updated_at=NOW() WHERE id=1", reason);
    }

    public void open() {
        jdbc.update("UPDATE trading_execution_gate SET open=TRUE,reason=NULL,updated_at=NOW() WHERE id=1");
    }
}
