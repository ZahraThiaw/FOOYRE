package com.fooyre.shared.error;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;
import org.springframework.validation.FieldError;

/** Correspondance contraintes Jakarta Validation → codes stables de champ. */
final class FieldErrorCodes {

    private static final Map<String, FieldErrorCode> BY_CONSTRAINT = Map.ofEntries(
            Map.entry("NotNull", FieldErrorCode.REQUIRED),
            Map.entry("NotBlank", FieldErrorCode.REQUIRED),
            Map.entry("NotEmpty", FieldErrorCode.REQUIRED),
            Map.entry("Pattern", FieldErrorCode.INVALID_FORMAT),
            Map.entry("Email", FieldErrorCode.INVALID_FORMAT),
            Map.entry("typeMismatch", FieldErrorCode.INVALID_FORMAT));

    private FieldErrorCodes() {
    }

    static FieldErrorCode of(String constraint, Object rejectedValue, Object[] arguments) {
        if ("Size".equals(constraint)) {
            return sizeCode(rejectedValue, arguments);
        }
        return constraint == null
                ? FieldErrorCode.INVALID_VALUE
                : BY_CONSTRAINT.getOrDefault(constraint, FieldErrorCode.INVALID_VALUE);
    }

    static FieldErrorCode of(FieldError error) {
        return of(error.getCode(), error.getRejectedValue(), error.getArguments());
    }

    /** {@code @Size} : arguments de Spring triés par nom d'attribut → [champ, max, min]. */
    private static FieldErrorCode sizeCode(Object rejectedValue, Object[] arguments) {
        if (arguments != null && arguments.length >= 3 && arguments[1] instanceof Integer max) {
            Integer length = lengthOf(rejectedValue);
            return length != null && length > max ? FieldErrorCode.TOO_LONG : FieldErrorCode.TOO_SHORT;
        }
        return FieldErrorCode.INVALID_VALUE;
    }

    private static Integer lengthOf(Object value) {
        if (value instanceof CharSequence text) {
            return text.length();
        }
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        if (value != null && value.getClass().isArray()) {
            return Array.getLength(value);
        }
        return null;
    }
}
