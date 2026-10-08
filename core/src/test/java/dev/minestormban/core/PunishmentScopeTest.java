package dev.minestormban.core;

import dev.minestormban.core.model.Punishment;
import dev.minestormban.core.model.PunishmentScope;
import dev.minestormban.core.model.PunishmentType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentScopeTest {

    private static Punishment ban(PunishmentScope scope, String server) {
        return new Punishment(1L, PunishmentType.BAN, UUID.randomUUID(), "Steve", "Admin", "test",
                0L, -1L, server, scope, true, null, 0L, 0L);
    }

    @Test
    void serverBanOnlyAppliesToItsOwnServer() {
        Punishment p = ban(PunishmentScope.SERVER, "survival");
        assertTrue(p.appliesTo("survival"));
        assertFalse(p.appliesTo("lobby"));
    }

    @Test
    void globalBanAppliesEverywhere() {
        Punishment p = ban(PunishmentScope.GLOBAL, "survival");
        assertTrue(p.appliesTo("survival"));
        assertTrue(p.appliesTo("lobby"));
    }

    @Test
    void parsesConfigValuesLeniently() {
        assertEquals(PunishmentScope.GLOBAL, PunishmentScope.parse("Global", PunishmentScope.SERVER));
        assertEquals(PunishmentScope.GLOBAL, PunishmentScope.parse("network", PunishmentScope.SERVER));
        assertEquals(PunishmentScope.SERVER, PunishmentScope.parse("local", PunishmentScope.GLOBAL));
        assertEquals(PunishmentScope.SERVER, PunishmentScope.parse("nonsense", PunishmentScope.SERVER));
        assertEquals(PunishmentScope.GLOBAL, PunishmentScope.parse(null, PunishmentScope.GLOBAL));
    }
}
