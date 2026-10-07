package fr.eternom.eterVelocityLobby.module.orchestrator;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * La table eterlobby_servers, SEULE source de vérité des lobbys créés par l'orchestrateur : un serveur du panel qui n'y
 * figure pas n'est jamais supprimé (le panel héberge aussi les serveurs des clients). Même base que le réseau (accès lus
 * dans le modèle de config d'EterLib). Pilote MariaDB téléchargé au premier démarrage (libs/), pas inclus dans le jar :
 * Velocity n'a pas le chargement de bibliothèques de Paper. Une connexion par opération : peu d'appels, pas de pool.
 */
final class LobbyStore {

    enum State { CREATING, ACTIVE, DRAINING }

    /** Un lobby géré. panelId est null tant que le panel n'a pas répondu à la création. */
    record Row(String name, Integer panelId, String identifier, String externalId, String version, State state,
               String host, int port, long stateSince) {

        Row with(State newState) {
            return new Row(name, panelId, identifier, externalId, version, newState, host, port, System.currentTimeMillis());
        }

        /** Créé par le panel : son identifiant, et l'adresse où il l'a déployé. */
        Row withPanel(int id, String newIdentifier, String newHost, int newPort) {
            return new Row(name, id, newIdentifier, externalId, version, state, newHost, newPort, stateSince);
        }
    }

    private static final String DRIVER_VERSION = "3.5.6";
    private static final String TABLE = "eterlobby_servers";

    private final Driver driver;
    private final String jdbcUrl;
    private final Properties credentials = new Properties();

    LobbyStore(Path libs, String host, int port, String database, String username, String password,
               Consumer<Path> addToClasspath) throws IOException, SQLException {
        this.driver = loadDriver(libs, addToClasspath);
        this.jdbcUrl = "jdbc:mariadb://" + host + ":" + port + "/" + database;
        credentials.setProperty("user", username);
        credentials.setProperty("password", password);
        try (Connection connection = connect(); PreparedStatement create = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS " + TABLE + " (name VARCHAR(32) PRIMARY KEY, panel_id INT NULL,"
                        + " identifier VARCHAR(16) NULL, external_id VARCHAR(64) NOT NULL, version VARCHAR(1024) NOT NULL,"
                        + " state VARCHAR(16) NOT NULL, host VARCHAR(255) NOT NULL, port INT NOT NULL, state_since BIGINT NOT NULL)"
                        + " DEFAULT CHARSET = utf8mb4")) {
            create.executeUpdate();
        }
    }

    List<Row> all() throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement select = connection.prepareStatement("SELECT * FROM " + TABLE);
             ResultSet result = select.executeQuery()) {
            while (result.next()) {
                int panelId = result.getInt("panel_id");
                rows.add(new Row(result.getString("name"), result.wasNull() ? null : panelId, result.getString("identifier"),
                        result.getString("external_id"), result.getString("version"), State.valueOf(result.getString("state")),
                        result.getString("host"), result.getInt("port"), result.getLong("state_since")));
            }
        }
        return rows;
    }

    void save(Row row) throws SQLException {
        execute("REPLACE INTO " + TABLE + " (name, panel_id, identifier, external_id, version, state, host, port, state_since)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", row.name(), row.panelId(), row.identifier(), row.externalId(),
                row.version(), row.state().name(), row.host(), row.port(), row.stateSince());
    }

    /**
     * Lobby supprimé : sa ligne, et ses traces dans les tables des plugins (serveurs d'EterLib, lobbys d'EterHub),
     * pour qu'il ne reste pas « hors ligne » dans les menus. Une table absente est ignorée.
     */
    void delete(String name) throws SQLException {
        execute("DELETE FROM " + TABLE + " WHERE name = ?", name);
        for (String table : List.of("eter_servers", "eterhub_lobbies")) {
            try {
                execute("DELETE FROM " + table + " WHERE name = ?", name);
            } catch (SQLException missingTable) {
                // plugin pas encore installé sur le réseau
            }
        }
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
    }

    private Connection connect() throws SQLException {
        return driver.connect(jdbcUrl, credentials);
    }

    /** libs/mariadb-java-client-<version>.jar, téléchargé depuis Maven Central s'il manque, puis ajouté au proxy. */
    private static Driver loadDriver(Path libs, Consumer<Path> addToClasspath) throws IOException {
        Path jar = libs.resolve("mariadb-java-client-" + DRIVER_VERSION + ".jar");
        if (!Files.exists(jar)) {
            Files.createDirectories(libs);
            URI source = URI.create("https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/" + DRIVER_VERSION
                    + "/mariadb-java-client-" + DRIVER_VERSION + ".jar");
            Path temporary = libs.resolve(jar.getFileName() + ".part");
            try (InputStream in = source.toURL().openStream()) {
                Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temporary, jar, StandardCopyOption.REPLACE_EXISTING);
        }
        addToClasspath.accept(jar);
        try {
            return (Driver) Class.forName("org.mariadb.jdbc.Driver", true, LobbyStore.class.getClassLoader())
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IOException("Pilote MariaDB illisible : " + jar, e);
        }
    }
}
