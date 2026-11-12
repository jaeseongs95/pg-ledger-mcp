package org.pgledger.domain.value;

import java.util.function.Function;

/** A field of an edit: either not mentioned ({@link Absent}) or set to a value, possibly {@code null} ({@link Set}). */
public sealed interface Patch<T> {

    record Absent<T>() implements Patch<T> {
    }

    record Set<T>(T value) implements Patch<T> {
    }

    static <T> Patch<T> absent() {
        return new Absent<>();
    }

    static <T> Patch<T> set(T value) {
        return new Set<>(value);
    }

    /** The new value when set, otherwise the current one. */
    default T orElse(T current) {
        return switch (this) {
            case Absent<T> a -> current;
            case Set<T> s -> s.value();
        };
    }

    /** The new value when set, otherwise {@code null}: the value of a field that was not mentioned when a row is created. */
    default T orNull() {
        return switch (this) {
            case Absent<T> a -> null;
            case Set<T> s -> s.value();
        };
    }

    default <R> Patch<R> map(Function<T, R> conversion) {
        return switch (this) {
            case Absent<T> a -> absent();
            case Set<T> s -> set(conversion.apply(s.value()));
        };
    }

    default boolean mentioned() {
        return this instanceof Set<T>;
    }

    /** True when the patch is set to a value that is not {@code null}. */
    default boolean hasValue() {
        return this instanceof Set<T> s && s.value() != null;
    }
}
