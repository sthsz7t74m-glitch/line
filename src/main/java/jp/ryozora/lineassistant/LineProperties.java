package jp.ryozora.lineassistant;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "line.bot")
public record LineProperties(
        String channelSecret,
        String channelAccessToken,
        String ownerUserId
) {}
