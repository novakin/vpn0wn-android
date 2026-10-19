/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.config;

import com.vpn0wn.crypto.Key;
import com.vpn0wn.crypto.KeyFormatException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Validated, explicitly present AmneziaWG interface attributes. */
public final class AmneziaSettings {
    static final String PROTOCOL_MARKER = "# vpn0wn:protocol=AmneziaWG";
    private static final long UINT32_MAX = 0xffff_ffffL;
    private static final int MAX_UDP_PAYLOAD = 65507;
    private static final int MAX_JUNK_PACKETS = 128;
    private static final int MAX_INITIATION_EXTRA_BYTES = 1024 * 1024;
    private final Map<Field, String> values;

    private AmneziaSettings(final Map<Field, String> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public enum Field {
        JC("Jc", "jc"), JMIN("Jmin", "jmin"), JMAX("Jmax", "jmax"),
        I1("I1", "i1"), I2("I2", "i2"), I3("I3", "i3"), I4("I4", "i4"), I5("I5", "i5"),
        S1("S1", "s1"), S2("S2", "s2"), S3("S3", "s3"), S4("S4", "s4"),
        H1("H1", "h1"), H2("H2", "h2"), H3("H3", "h3"), H4("H4", "h4"),
        HEADER_PROTECTION_KEY("HeaderProtectionKey", "header_protection_key"),
        CONTENT_PADDING_ADDITION("ContentPaddingAddition", "content_padding_addition"),
        REKEY_AFTER_TIME("RekeyAfterTime", "rekey_after_time"), REKEY_TIMEOUT("RekeyTimeout", "rekey_timeout"),
        REJECT_AFTER_TIME("RejectAfterTime", "reject_after_time"), KEEPALIVE_TIMEOUT("KeepaliveTimeout", "keepalive_timeout"),
        MAX_HANDSHAKE_ATTEMPTS("MaxHandshakeAttempts", "max_handshake_attempts"),
        RANDOM_TRAILERS("RandomTrailers", "random_trailers"), DISABLE_COOKIES("DisableCookies", "disable_cookies");

        private final String configName;
        private final String userspaceName;

        Field(final String configName, final String userspaceName) {
            this.configName = configName;
            this.userspaceName = userspaceName;
        }

        public String getConfigName() {
            return configName;
        }

        public static Field fromConfigName(final String name) {
            for (final Field field : values()) {
                if (field.configName.equalsIgnoreCase(name))
                    return field;
            }
            return null;
        }
    }

    public Map<Field, String> getValues() {
        return values;
    }

    void validateMtu(final int mtu) throws BadConfigException {
        final Field[] fields = {Field.S1, Field.S2, Field.S3, Field.S4};
        final long[] overheads = {148, 92, 64, (long) mtu + 32 + 15};
        for (int i = 0; i < fields.length; ++i) {
            final long padding = Long.parseLong(values.getOrDefault(fields[i], "0"));
            if (padding + overheads[i] > MAX_UDP_PAYLOAD)
                throw invalid(fields[i]);
        }
    }

    public String toQuickString() {
        final StringBuilder output = new StringBuilder(PROTOCOL_MARKER + "\n");
        values.forEach((field, value) -> output.append(field.configName).append(" = ").append(value).append('\n'));
        return output.toString();
    }

    public String toUserspaceString() {
        final StringBuilder output = new StringBuilder();
        values.forEach((field, value) -> {
            String encoded = value;
            if (field == Field.HEADER_PROTECTION_KEY) {
                try {
                    encoded = Key.fromBase64(value).toHex();
                } catch (final KeyFormatException impossible) {
                    throw new IllegalStateException("Validated AmneziaWG key became invalid", impossible);
                }
            }
            output.append(field.userspaceName).append('=').append(encoded).append('\n');
        });
        return output.toString();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof AmneziaSettings && values.equals(((AmneziaSettings) other).values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "AmneziaSettings(" + values.size() + " fields)";
    }

    static long[] parseRange(final String value) {
        if (!value.matches("[0-9]+(?:-[0-9]+)?"))
            throw new IllegalArgumentException("Invalid unsigned range");
        final String[] parts = value.split("-", -1);
        final long low = Long.parseLong(parts[0]);
        final long high = parts.length == 1 ? low : Long.parseLong(parts[1]);
        if (low > high || high > UINT32_MAX || high - low == UINT32_MAX)
            throw new IllegalArgumentException("Unsigned range exceeds engine bounds");
        return new long[]{low, high};
    }

    private static BadConfigException invalid(final Field field) {
        return new BadConfigException(BadConfigException.Section.INTERFACE,
                BadConfigException.Location.AMNEZIA_WG, BadConfigException.Reason.INVALID_VALUE, field.configName);
    }

    public static final class Builder {
        private final Map<Field, String> values = new LinkedHashMap<>();

        public Builder put(final Field field, final String value) throws BadConfigException {
            Objects.requireNonNull(field);
            final String trimmed = value.trim();
            try {
                validateValue(field, trimmed);
            } catch (final IllegalArgumentException | KeyFormatException failure) {
                throw invalid(field);
            }
            if (values.containsKey(field) && !values.get(field).equals(trimmed))
                throw invalid(field);
            values.put(field, trimmed);
            return this;
        }

        private static void validateValue(final Field field, final String value) throws KeyFormatException {
            switch (field) {
                case HEADER_PROTECTION_KEY:
                    Key.fromBase64(value);
                    return;
                case RANDOM_TRAILERS:
                case DISABLE_COOKIES:
                    if (!value.equals("0") && !value.equals("1") && !value.equals("true") && !value.equals("false"))
                        throw new IllegalArgumentException("Expected true, false, 0 or 1");
                    return;
                case I1:
                case I2:
                case I3:
                case I4:
                case I5:
                    signatureSize(value);
                    return;
                default:
                    final long[] range = parseRange(value);
                    if (field == Field.CONTENT_PADDING_ADDITION && range[1] > Integer.MAX_VALUE)
                        throw new IllegalArgumentException("Padding exceeds native 32-bit integer");
                    if (field == Field.REJECT_AFTER_TIME && range[1] > Long.MAX_VALUE / 3 / 1_000_000_000L)
                        throw new IllegalArgumentException("Key lifetime overflows native timer");
                    if (field == Field.MAX_HANDSHAKE_ATTEMPTS && range[1] > UINT32_MAX - 2)
                        throw new IllegalArgumentException("Handshake counter overflows");
                    if (field == Field.JC && (range[1] > MAX_JUNK_PACKETS || value.contains("-")))
                        throw new IllegalArgumentException("Too many junk packets");
                    if ((field == Field.JMIN || field == Field.JMAX || field.name().startsWith("S"))
                            && (range[1] > MAX_UDP_PAYLOAD || value.contains("-")))
                        throw new IllegalArgumentException("Invalid packet size");
            }
        }

        public AmneziaSettings build() throws BadConfigException {
            final long min = scalar(Field.JMIN, 0);
            final long max = scalar(Field.JMAX, 0);
            if (min > max)
                throw invalid(Field.JMIN);
            long extraBytes = scalar(Field.JC, 0) * max;
            for (final Field field : new Field[]{Field.I1, Field.I2, Field.I3, Field.I4, Field.I5}) {
                if (values.containsKey(field))
                    extraBytes += signatureSize(values.get(field));
            }
            if (extraBytes > MAX_INITIATION_EXTRA_BYTES)
                throw invalid(Field.JC);
            final Field[] headers = {Field.H1, Field.H2, Field.H3, Field.H4};
            for (int i = 0; i < headers.length; ++i) {
                final long[] left = parseRange(values.getOrDefault(headers[i], String.valueOf(i + 1)));
                for (int j = i + 1; j < headers.length; ++j) {
                    final long[] right = parseRange(values.getOrDefault(headers[j], String.valueOf(j + 1)));
                    if (left[0] <= right[1] && right[0] <= left[1])
                        throw invalid(headers[j]);
                }
            }
            if (values.containsKey(Field.HEADER_PROTECTION_KEY)) {
                try {
                    final byte[] key = Key.fromBase64(values.get(Field.HEADER_PROTECTION_KEY)).getBytes();
                    boolean nonzero = false;
                    for (final byte value : key)
                        nonzero |= value != 0;
                    if (nonzero) {
                        for (final Field field : new Field[]{Field.S1, Field.S2, Field.S3, Field.S4}) {
                            if (scalar(field, 0) < 12)
                                throw invalid(field);
                        }
                    }
                } catch (final KeyFormatException impossible) {
                    throw new IllegalStateException("Validated AmneziaWG key became invalid", impossible);
                }
            }
            return new AmneziaSettings(values);
        }

        private long scalar(final Field field, final long fallback) {
            return values.containsKey(field) ? Long.parseLong(values.get(field)) : fallback;
        }
    }

    private static int signatureSize(final String value) {
        if (value.isEmpty())
            return 0;
        int offset = 0;
        long size = 0;
        while (offset < value.length()) {
            if (value.charAt(offset) != '<')
                throw new IllegalArgumentException("Expected CPS tag");
            final int end = value.indexOf('>', offset + 1);
            if (end < 0)
                throw new IllegalArgumentException("Unclosed CPS tag");
            final String[] tag = value.substring(offset + 1, end).trim().split("\\s+");
            switch (tag[0]) {
                case "b":
                    if (tag.length != 2 || !tag[1].matches("(?:0x)?(?:[0-9a-fA-F]{2})+"))
                        throw new IllegalArgumentException("Invalid CPS bytes");
                    size += (tag[1].startsWith("0x") ? tag[1].length() - 2 : tag[1].length()) / 2;
                    break;
                case "d":
                case "ds":
                    if (tag.length != 1)
                        throw new IllegalArgumentException("Data tag takes no argument");
                    break;
                case "t":
                    if (tag.length != 1)
                        throw new IllegalArgumentException("Timestamp takes no argument");
                    size += 4;
                    break;
                case "r":
                case "rc":
                case "rd":
                case "dz":
                    if (tag.length != 2 || !tag[1].matches("[0-9]+"))
                        throw new IllegalArgumentException("Invalid CPS random length");
                    final long length = Long.parseLong(tag[1]);
                    if (length > MAX_UDP_PAYLOAD)
                        throw new IllegalArgumentException("CPS length exceeds UDP payload");
                    size += length;
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported CPS tag");
            }
            if (size > MAX_UDP_PAYLOAD)
                throw new IllegalArgumentException("CPS packet exceeds UDP payload");
            offset = end + 1;
        }
        return (int) size;
    }
}
