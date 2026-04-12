package auracore.key49.api.portal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import auracore.key49.core.service.PasswordHasher;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;

/**
 * Tests unitarios para PortalSessionService — login por contraseña.
 */
@ExtendWith(MockitoExtension.class)
class PortalSessionServiceTest {

    @Mock
    org.jboss.logging.Logger log;

    @Mock
    RedisDataSource redisDS;

    @Mock
    DataSource dataSource;

    @Mock
    PasswordHasher passwordHasher;

    @Mock
    Connection connection;

    @Mock
    PreparedStatement stmt;

    @Mock
    ResultSet rs;

    @Mock
    @SuppressWarnings("rawtypes")
    HashCommands hashCommands;

    @Mock
    @SuppressWarnings("rawtypes")
    KeyCommands keyCommands;

    @InjectMocks
    PortalSessionService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redisDS.hash(String.class, String.class, String.class)).thenReturn(hashCommands);
        lenient().when(redisDS.key(String.class)).thenReturn(keyCommands);
    }

    @Nested
    @DisplayName("Login con contraseña")
    class PasswordLogin {

        @Test
        @DisplayName("login exitoso con email y contraseña válidos")
        void shouldLoginWithValidCredentials() throws Exception {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(stmt);
            when(stmt.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(true);
            when(rs.getObject(eq("tenant_id"), eq(UUID.class))).thenReturn(UUID.randomUUID());
            when(rs.getString("schema_name")).thenReturn("tenant_test");
            when(rs.getString("legal_name")).thenReturn("Test Corp");
            when(rs.getString("portal_password_hash")).thenReturn("$2a$12$hashvalue");
            when(passwordHasher.verify("password123", "$2a$12$hashvalue")).thenReturn(true);

            var sessionId = service.loginWithPassword("user@test.com", "password123");

            assertNotNull(sessionId);
        }

        @Test
        @DisplayName("login falla con contraseña incorrecta")
        void shouldRejectWrongPassword() throws Exception {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(stmt);
            when(stmt.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(true);
            when(rs.getString("portal_password_hash")).thenReturn("$2a$12$hashvalue");
            when(passwordHasher.verify("wrongpass", "$2a$12$hashvalue")).thenReturn(false);

            var sessionId = service.loginWithPassword("user@test.com", "wrongpass");

            assertNull(sessionId);
        }

        @Test
        @DisplayName("login falla con email inexistente")
        void shouldRejectUnknownEmail() throws Exception {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(stmt);
            when(stmt.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(false);

            var sessionId = service.loginWithPassword("unknown@test.com", "password");

            assertNull(sessionId);
        }

        @Test
        @DisplayName("login falla con email nulo o vacío")
        void shouldRejectNullOrEmptyEmail() {
            assertNull(service.loginWithPassword(null, "password"));
            assertNull(service.loginWithPassword("", "password"));
            assertNull(service.loginWithPassword("  ", "password"));
        }

        @Test
        @DisplayName("login falla con contraseña nula o vacía")
        void shouldRejectNullOrEmptyPassword() {
            assertNull(service.loginWithPassword("user@test.com", null));
            assertNull(service.loginWithPassword("user@test.com", ""));
            assertNull(service.loginWithPassword("user@test.com", "  "));
        }

        @Test
        @DisplayName("login falla si tenant no tiene password hash configurado")
        void shouldRejectWhenNoPasswordHash() throws Exception {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(stmt);
            when(stmt.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(true);
            when(rs.getString("portal_password_hash")).thenReturn(null);

            var sessionId = service.loginWithPassword("user@test.com", "password");

            assertNull(sessionId);
        }

        @Test
        @DisplayName("email se normaliza a minúsculas")
        void shouldNormalizeEmail() throws Exception {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(stmt);
            when(stmt.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(true);
            when(rs.getObject(eq("tenant_id"), eq(UUID.class))).thenReturn(UUID.randomUUID());
            when(rs.getString("schema_name")).thenReturn("tenant_test");
            when(rs.getString("legal_name")).thenReturn("Test Corp");
            when(rs.getString("portal_password_hash")).thenReturn("$2a$12$hash");
            when(passwordHasher.verify("pass", "$2a$12$hash")).thenReturn(true);

            var sessionId = service.loginWithPassword("  USER@Test.COM  ", "pass");

            assertNotNull(sessionId);
        }
    }
}
