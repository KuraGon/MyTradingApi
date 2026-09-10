package com.saamp.trading.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import liquibase.integration.spring.SpringLiquibase;

/** Isole chaque JVM de tests dans ses seuls schémas PostgreSQL locaux, sans repli vers public. */
public final class LocalPostgres {
    private static final String URL="jdbc:postgresql://127.0.0.1:5432/trading";
    private static final Set<String> OWNED=ConcurrentHashMap.newKeySet();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            for(String name:Set.copyOf(OWNED)) {
                try { dropSchema(name); }
                catch(Exception failure) { System.err.println("TEST_SCHEMA_CLEANUP_FAILED "+name); }
            }
        },"local-postgres-cleanup"));
    }
    private LocalPostgres() { }
    private static class Run { static final String SCHEMA=createSchema(); }

    /** @return schéma commun aux connexions Spring et JDBC de cette JVM */
    public static String schema() { return Run.SCHEMA; }

    private static String password() {
        String value=System.getenv("TRADING_DB_PASSWORD");
        if(value==null || value.isBlank()) throw new IllegalStateException("TRADING_DB_PASSWORD requis");
        return value;
    }
    static void validateUrl(String url) {
        if(!URL.equals(url)) throw new IllegalArgumentException("Only the fixed local test database is allowed");
    }
    private static Connection administrativeConnection() throws SQLException {
        validateUrl(URL);
        var c=new DriverManagerDataSource(URL,"trading",password()).getConnection();
        try(var s=c.createStatement()) {
            s.setQueryTimeout(5);
            try(var r=s.executeQuery("SELECT host(inet_server_addr()),inet_server_port(),current_database(),current_schema(),current_user,has_database_privilege(current_user,current_database(),'CREATE')")) {
                if(!r.next() || !"127.0.0.1".equals(r.getString(1)) || r.getInt(2)!=5432 || !"trading".equals(r.getString(3))
                        || !"public".equals(r.getString(4)) || !"trading".equals(r.getString(5)) || !r.getBoolean(6))
                    throw new SQLException("Local test database identity or CREATE privilege invalid");
            }
            return c;
        } catch(Exception failure) { c.close();throw failure; }
    }
    private static void requireOwned(String name) {
        if(name==null || !name.matches("saamp_test_[a-f0-9]{32}") || !OWNED.contains(name))
            throw new IllegalArgumentException("Schema is not owned by this test JVM");
    }
    private static void verify(Connection c,String expected) throws SQLException {
        requireOwned(expected);
        try(var s=c.createStatement()) {
            s.setQueryTimeout(5);
            try(var r=s.executeQuery("SELECT current_schema(),host(inet_server_addr()),current_database(),current_user")) {
                if(!r.next() || !expected.equals(r.getString(1)) || !"127.0.0.1".equals(r.getString(2))
                        || !"trading".equals(r.getString(3)) || !"trading".equals(r.getString(4)))
                    throw new SQLException("Unexpected test connection destination/schema");
            }
        }
    }
    /** @return nouveau schéma détenu exclusivement par cette JVM, sans tables préexistantes */
    public static String createSchema() {
        String name="saamp_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=administrativeConnection();var s=c.createStatement()) {
            s.setQueryTimeout(5);s.execute("CREATE SCHEMA "+name);OWNED.add(name);
            c.setSchema(name);verify(c,name);
            System.out.println("TEST_SCHEMA_CREATED "+name+" current_schema="+name);
            return name;
        } catch(SQLException e) { throw new IllegalStateException("Cannot create isolated local test schema",e); }
    }
    /** Supprime exclusivement un schéma créé par cette JVM après vérification de sa connexion.
     * @param name schéma détenu par cette exécution
     * @throws SQLException si la suppression échoue
     */
    public static void dropSchema(String name) throws SQLException {
        requireOwned(name);
        try(var c=administrativeConnection();var s=c.createStatement()) {
            c.setSchema(name);verify(c,name);s.setQueryTimeout(10);
            s.execute("DROP SCHEMA "+name+" CASCADE");OWNED.remove(name);
            System.out.println("TEST_SCHEMA_DROPPED "+name);
        }
    }
    /** @return datasource vérifiant le schéma commun à chaque ouverture de connexion */
    public static DriverManagerDataSource dataSource() { return dataSource(schema()); }
    /** @param name schéma créé par ce run @return datasource sans repli vers public */
    public static DriverManagerDataSource dataSource(String name) {
        requireOwned(name);
        return new DriverManagerDataSource(URL+"?currentSchema="+name,"trading",password()) {
            @Override public Connection getConnection() throws SQLException {
                if (!(URL+"?currentSchema="+name).equals(getUrl()) || !"trading".equals(getUsername()))
                    throw new SQLException("Test datasource destination cannot be overridden");
                var c=super.getConnection();
                try {
                    verify(c,name);
                    return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                        if (method.getName().equals("setSchema") && !name.equals(args[0]))
                            throw new SQLException("Test schema cannot be changed");
                        try { return method.invoke(c,args); }
                        catch(java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                    });
                } catch(Exception failure) { c.close();throw failure; }
            }
            @Override public Connection getConnection(String user,String secret) throws SQLException {
                throw new SQLException("Test credentials cannot be overridden");
            }
        };
    }
    /** @return connexion JDBC au même schéma que Spring @throws SQLException si connexion refusée */
    public static Connection connection() throws SQLException { return dataSource().getConnection(); }

    /** Remplace uniquement le câblage des contextes de test, sans configuration de production. */
    @TestConfiguration(proxyBeanMethods=false)
    public static class Context {
        @Bean DataSource dataSource() { return LocalPostgres.dataSource(); }
        @Bean SpringLiquibase liquibase(DataSource source) {
            var migration=new SpringLiquibase();migration.setDataSource(source);
            migration.setDefaultSchema(schema());migration.setLiquibaseSchema(schema());
            migration.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");return migration;
        }
    }
}
