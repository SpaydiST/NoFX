package dev.nofx.scan;

public record Probe(String key, Type type, String fallback) {

    public enum Type {
        KEYBIND,
        TRANSLATE
    }
}
