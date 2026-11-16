package sattya.piseth.crudrestapis.shared.domain;

public final class DomainChecks {
    private DomainChecks() {}

    public static String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1.." + maxLength + " characters");
        }
        return value;
    }
}
