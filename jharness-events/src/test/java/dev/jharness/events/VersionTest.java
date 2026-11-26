package dev.jharness.events;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Spec 062: the version comes from the pom, through the filtered version.properties. */
class VersionTest {

    @Test
    void theVersionIsThePoms() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        String parent = pom.substring(pom.indexOf("<parent>"), pom.indexOf("</parent>"));
        String expected = parent.replaceAll("(?s).*<version>([^<]+)</version>.*", "$1");
        assertEquals(expected, Version.CURRENT);
        assertTrue(Version.CURRENT.matches("[0-9]+\\.[0-9]+\\.[0-9]+(-SNAPSHOT)?"), Version.CURRENT);
    }
}
