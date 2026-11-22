package dev.oseer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class BundledResources {
    private BundledResources() {}

    public static String readText(String resource) {
        try (var input = BundledResources.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing bundled resource " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new UncheckedIOException("cannot read bundled resource " + resource, failed);
        }
    }
}
