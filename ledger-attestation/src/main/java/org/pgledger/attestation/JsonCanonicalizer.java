package org.pgledger.attestation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The canonical text of a JSON value, the JSON Canonicalization Scheme (JCS) of RFC 8785
 * (https://www.rfc-editor.org/rfc/rfc8785): no insignificant white space, the members of an object sorted by the
 * UTF-16 code units of their names (section 3.2.3), strings with the minimal escapes of section 3.2.2.2, and numbers
 * as the text of the IEEE 754 double that ECMAScript's {@code Number.prototype.toString} gives (section 3.2.2.3, the
 * algorithm of ECMA-262 "Number::toString"). The text is UTF-8 when it is written out, as the RFC asks.
 *
 * <p><b>Why this is self-built</b> (principle 2.1): the JDK has no JSON canonicaliser, and Jackson (which has none
 * either) is not a dependency of this module, which is JDK only so that the client hook and the server share the very
 * same class. The ECMAScript number text is made from the shortest decimal digits that read back as the same double:
 * the exact value is rounded to 1, 2, ... 17 significant digits until the result converts back to the double, which is
 * the definition of the algorithm of the RFC (and does not depend on how {@code Double.toString} of a given JDK
 * chooses its digits).
 *
 * <p>What the RFC makes an error is an error here, and, for integers, what the digest could not tell apart:
 * <ul>
 * <li>A decimal number ({@link Double}, {@link Float}, {@link java.math.BigDecimal}, the form a JSON number with a
 *     fraction or an exponent has) is canonicalised by its IEEE 754 double, as the RFC says (section 3.1 "JSON number
 *     data MUST be expressible as IEEE 754 double-precision values", section 3.2.2.3), so {@code 0.10} (whose decimal
 *     value is not exactly the double {@code 0.1}) and {@code 0.1} are one value. That is not ambiguous for the server,
 *     because a JSON number with a fraction or exponent reaches it as a double as well. The sender passes the original
 *     text on unchanged.</li>
 * <li>An integer ({@link Byte}, {@link Short}, {@link Integer}, {@link Long}, {@link java.math.BigInteger}, the form a
 *     JSON number without fraction and exponent has) must lie in [-(2**53)+1, (2**53)-1], the range in which every
 *     integer is exactly a double. Outside it the double of {@code 9007199254740993} is {@code 9007199254740992}, so two
 *     different integers would have one canonical form and one digest, while the server binds each of them as its own
 *     exact {@code long}: a signature for one would then be valid for the other. RFC 7493 (I-JSON) section 2.2 says "An
 *     I-JSON sender cannot expect a receiver to treat an integer whose absolute value is greater than 9007199254740991
 *     (i.e., that is outside the range [-(2**53)+1, (2**53)-1]) as an exact value" and recommends strings for such
 *     numbers (https://www.rfc-editor.org/rfc/rfc7493#section-2.2); RFC 8785 section 3.1 asks the same of its input
 *     (https://www.rfc-editor.org/rfc/rfc8785#section-3.1). Such an integer is an {@link IllegalArgumentException}
 *     here: the hook does not sign it and the server does not verify it.</li>
 * <li>NaN and Infinity (a value that overflows a double, such as {@code 1e999}) "MUST cause a compliant JCS
 *     implementation to terminate with an appropriate error" (section 3.2.2.3).</li>
 * </ul>
 * A string with an unpaired surrogate is invalid Unicode and likewise "MUST cause a compliant JCS implementation to
 * terminate with an appropriate error" (the note of section 3.2.2.2).
 *
 * <p>The value is a Java model of JSON: {@code null}, {@link Boolean}, {@link String}, a {@link Number} of the types
 * above (read as a double once it is in range, so {@code 1}, {@code 1.0} and {@code 1e0} are the same value, as in JSON
 * of the RFC), {@link List} and {@link Map} with {@link String} keys. Anything else (also any other {@link Number}
 * type), a non-finite number, an integer outside the range above and a string that holds an unpaired surrogate (it has
 * no UTF-8 form) is an {@link IllegalArgumentException}: the caller built something that is not canonical JSON.
 */
public final class JsonCanonicalizer {

    private static final int ES_MAX_INTEGER_DIGITS = 21;
    private static final int ES_MIN_FRACTION_EXPONENT = -6;
    private static final int MAX_PRECISION = 17;
    /** The largest integer below which every integer is exactly a double: 2**53 - 1 (ECMAScript Number.MAX_SAFE_INTEGER). */
    private static final long MAX_SAFE_INTEGER = (1L << 53) - 1;
    /** Deeper values are refused: the canonical form is built recursively, and real tool arguments are shallow. */
    private static final int MAX_DEPTH = 256;

    private JsonCanonicalizer() {
    }

    /**
     * The canonical text of the value.
     *
     * @throws IllegalArgumentException if the value is not JSON as described in the class comment
     */
    public static String canonicalize(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON value nested deeper than " + MAX_DEPTH);
        }
        switch (value) {
            case null -> out.append("null");
            case Boolean bool -> out.append(bool ? "true" : "false");
            case String text -> string(out, text);
            case Number number -> out.append(number(number));
            case Map<?, ?> map -> object(out, map, depth);
            case List<?> list -> array(out, list, depth);
            default -> throw new IllegalArgumentException("not a JSON value: " + value.getClass().getName());
        }
    }

    private static void object(StringBuilder out, Map<?, ?> map, int depth) {
        List<String> names = new ArrayList<>(map.size());
        for (Object key : map.keySet()) {
            if (!(key instanceof String name)) {
                throw new IllegalArgumentException("a JSON object member name must be a String");
            }
            names.add(name);
        }
        // String.compareTo orders by UTF-16 code units, which is exactly the order RFC 8785 section 3.2.3 asks for
        names.sort(null);
        out.append('{');
        boolean first = true;
        for (String name : names) {
            if (!first) {
                out.append(',');
            }
            first = false;
            string(out, name);
            out.append(':');
            write(out, map.get(name), depth + 1);
        }
        out.append('}');
    }

    private static void array(StringBuilder out, List<?> list, int depth) {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            write(out, list.get(i), depth + 1);
        }
        out.append(']');
    }

    /** RFC 8785 section 3.2.2.2: only {@code "} {@code \} and the control characters are escaped. */
    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xf, 16));
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                            throw new IllegalArgumentException("a string with an unpaired surrogate has no UTF-8 form");
                        }
                        out.append(c).append(text.charAt(++i));
                    } else if (Character.isLowSurrogate(c)) {
                        throw new IllegalArgumentException("a string with an unpaired surrogate has no UTF-8 form");
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** RFC 8785 section 3.2.2.3 and ECMA-262 Number::toString: k digits, the decimal exponent n, five layouts. */
    static String number(Number number) {
        double value = doubleOf(number);
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("a JSON number must be finite");
        }
        if (value == 0) {
            return "0";
        }
        boolean negative = value < 0;
        double magnitude = Math.abs(value);
        BigDecimal shortest = shortestDigits(magnitude);
        String digits = shortest.unscaledValue().toString();
        int k = digits.length();
        int n = k - shortest.scale();
        StringBuilder out = new StringBuilder();
        if (negative) {
            out.append('-');
        }
        if (k <= n && n <= ES_MAX_INTEGER_DIGITS) {
            out.append(digits).append("0".repeat(n - k));
        } else if (0 < n && n <= ES_MAX_INTEGER_DIGITS) {
            out.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (ES_MIN_FRACTION_EXPONENT < n && n <= 0) {
            out.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            int exponent = n - 1;
            out.append(digits.charAt(0));
            if (k > 1) {
                out.append('.').append(digits, 1, k);
            }
            out.append('e').append(exponent < 0 ? '-' : '+').append(Math.abs(exponent));
        }
        return out.toString();
    }

    /**
     * The double that stands for the number: a decimal number as it is, an integer only when it lies in the range in
     * which an integer is exactly a double (see the class comment).
     */
    private static double doubleOf(Number number) {
        return switch (number) {
            case Double decimal -> decimal.doubleValue();
            case Float decimal -> decimal.doubleValue();
            case BigDecimal decimal -> decimal.doubleValue();
            case Byte integer -> integer.doubleValue();
            case Short integer -> integer.doubleValue();
            case Integer integer -> integer.doubleValue();
            case Long integer -> safeInteger(integer);
            case BigInteger integer -> safeInteger(integer);
            default -> throw new IllegalArgumentException("not a JSON number type: " + number.getClass().getName());
        };
    }

    private static double safeInteger(long integer) {
        if (integer < -MAX_SAFE_INTEGER || integer > MAX_SAFE_INTEGER) {
            throw outOfRange();
        }
        return integer;
    }

    private static double safeInteger(BigInteger integer) {
        if (integer.abs().compareTo(BigInteger.valueOf(MAX_SAFE_INTEGER)) > 0) {
            throw outOfRange();
        }
        return integer.doubleValue();
    }

    private static IllegalArgumentException outOfRange() {
        return new IllegalArgumentException("an integer outside [-(2**53)+1, (2**53)-1] has no exact canonical form "
                + "(RFC 7493 section 2.2); send it as a string");
    }

    /** The decimal with the fewest significant digits (trailing zeros stripped) that converts back to the double. */
    private static BigDecimal shortestDigits(double magnitude) {
        BigDecimal exact = new BigDecimal(magnitude);
        for (int precision = 1; precision < MAX_PRECISION; precision++) {
            BigDecimal candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            if (candidate.doubleValue() == magnitude) {
                return candidate.stripTrailingZeros();
            }
        }
        return exact.round(new MathContext(MAX_PRECISION, RoundingMode.HALF_EVEN)).stripTrailingZeros();
    }
}
