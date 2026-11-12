package com.ataiva.serengeti.core.config;

/** A setting is missing, malformed or inconsistent. The message names the setting and is safe to log. */
public final class ConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }
}
