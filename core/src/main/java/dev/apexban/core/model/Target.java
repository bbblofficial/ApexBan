package dev.apexban.core.model;

import java.util.UUID;

/** A resolved player (UUID + canonical name). */
public record Target(UUID uuid, String name) {
}
