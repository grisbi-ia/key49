package auracore.key49.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request para configurar SMTP personalizado de un tenant.
 */
public record SmtpConfigRequest(
        String host,
        Integer port,
        String user,
        String password,
        @JsonProperty("from")
        String fromAddress,
        @JsonProperty("email_notifications_enabled")
        Boolean emailNotificationsEnabled,
        @JsonProperty("notify_final_consumer")
        Boolean notifyFinalConsumer) {

}
