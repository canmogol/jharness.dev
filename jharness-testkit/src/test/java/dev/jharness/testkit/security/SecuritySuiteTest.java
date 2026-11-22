package dev.jharness.testkit.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.testkit.security.SecurityCase.Outcome;

/** Spec 018 AC-08: every security case as a JUnit test named by its ID. */
class SecuritySuiteTest {

    @TempDir
    Path tmp;

    @TestFactory
    Stream<DynamicTest> everyCaseIsContained() {
        return SecuritySuite.all().stream().map(c -> DynamicTest.dynamicTest(c.id() + " " + c.attack(), () -> {
            Outcome o = SecuritySuite.run(c, tmp.resolve(c.id()));
            Assumptions.assumeFalse(o.status().equals("skip"), o.detail());
            // A recorded exception (D-056) is reported, not passed: the gate table counts it separately.
            Assumptions.assumeFalse(o.status().equals("exception"), "KNOWN EXCEPTION " + o.detail());
            assertEquals("pass", o.status(), c.id() + ": " + o.detail());
        }));
    }

    @Test
    void idsAreUniqueAndEverySuiteHasCases() {
        List<SecurityCase> all = SecuritySuite.all();
        Set<String> ids = new HashSet<>();
        for (SecurityCase c : all) {
            assertTrue(ids.add(c.id()), "duplicate " + c.id());
            assertTrue(c.id().matches("(INJ|REPO|ESC|RUN)-\\d\\d"), c.id());
            assertTrue(!c.attack().isBlank() && !c.containment().isBlank() && !c.layer().isBlank(), c.id());
        }
        for (SecurityCase.Suite s : SecurityCase.Suite.values()) {
            assertTrue(all.stream().anyMatch(c -> c.suite() == s), s.name());
        }
    }
}
