package com.saamp.trading.support;

import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Accès JDBC des tests à l'instance locale historique explicitement autorisée. */
public final class LocalPostgres {
    private LocalPostgres() { }

    /**
     * Fixe la destination locale et lit le mot de passe uniquement dans l'environnement.
     * @return datasource locale, sans destination de repli
     * @throws IllegalStateException si TRADING_DB_PASSWORD est absent
     */
    public static DriverManagerDataSource dataSource() {
        String password=System.getenv("TRADING_DB_PASSWORD");
        if (password==null || password.isBlank()) throw new IllegalStateException("TRADING_DB_PASSWORD requis");
        return new DriverManagerDataSource("jdbc:postgresql://localhost:5432/trading","trading",password);
    }

    /**
     * Ouvre une connexion indépendante pour les assertions transactionnelles et les tests DDL.
     * @return connexion à fermer par le test
     * @throws SQLException si PostgreSQL local est inaccessible
     */
    public static Connection connection() throws SQLException { return dataSource().getConnection(); }
}
