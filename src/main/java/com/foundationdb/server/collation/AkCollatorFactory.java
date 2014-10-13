/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.server.collation;

import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.foundationdb.server.error.InvalidCollationSchemeException;
import com.foundationdb.server.error.UnsupportedCollationException;
import com.ibm.icu.text.Collator;
import com.ibm.icu.text.RuleBasedCollator;

/**
 * Provides Collator instances. Collator is not threadsafe, so this class keeps
 * SoftReferences to thread-local instances.
 *
 * @author peter
 *
 */
public class AkCollatorFactory {

    public final static int UCS_BINARY_ID = 0;

    public final static String UCS_BINARY = "UCS_BINARY";

    public final static AkCollator UCS_BINARY_COLLATOR = new AkCollatorBinary();

    private final static Map<String, Collator> sourceMap = new HashMap<>();

    private final static Map<String, SoftReference<AkCollator>> collatorMap = new ConcurrentHashMap<>();

    private final static Map<Integer, SoftReference<AkCollator>> collationIdMap = new ConcurrentHashMap<>();

    private final static Map<String, Integer> schemeToIdMap = new ConcurrentHashMap<>();

    private final static AtomicInteger collationIdGenerator = new AtomicInteger(UCS_BINARY_ID);

    private volatile static Mode mode = Mode.STRICT;

    /*
     * Note: used only in a single-threaded unit test.
     */
    private static int cacheHits;

    public enum Mode {
        STRICT, LOOSE, DISABLED
    }

    /**
     * Set factory to one of three modes specified by case-insensitive string:
     * <dl>
     * <dt>disabled</dt>
     * <dd>always uses UCS_BINARY_COLLATOR</dd>
     * <dt>strict</dt>
     * <dd>disallows unimplemented collators</dd>
     * <dt>loose</dt>
     * <dd>returns UCS_BINARY_COLLATOR for any unrecognized name</dd>
     * </dl
     */
    public static void setCollationMode(String modeString) {
        try {
            setCollationMode(Mode.valueOf(modeString.toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Collation mode must be STRICT, LOOSE or DISABLED: " + modeString);
        }
    }

    public static void setCollationMode(Mode m) {
        if (m == null) {
            throw new NullPointerException();
        }
        synchronized (collatorMap) {
            collationIdMap.clear();
            collatorMap.clear();
            mode = m;
        }
    }

    public static Mode getCollationMode() {
        return mode;
    }

    public static AkCollator getAkCollator(final String scheme) {
        if (mode == Mode.DISABLED || scheme == null) {
            return UCS_BINARY_COLLATOR;
        }

        if (scheme.equalsIgnoreCase(UCS_BINARY)) {
            return mapToBinary(scheme);
        }

        SoftReference<AkCollator> ref = collatorMap.get(scheme);
        if (ref != null) {
            AkCollator akCollator = ref.get();
            if (akCollator != null) {
                cacheHits++;
                return akCollator;
            }
        }

        synchronized (collatorMap) {

            Integer collationId = schemeToIdMap.get(scheme);
            if (collationId == null) {
                collationId = collationIdGenerator.incrementAndGet();
            }

            final AkCollator akCollator;
            try {
                akCollator = new AkCollatorICU(scheme, collationId);
            } catch (InvalidCollationSchemeException | UnsupportedCollationException e) {
                if (mode == Mode.LOOSE) {
                    return mapToBinary(scheme);
                }
                throw e;
            }

            ref = new SoftReference<>(akCollator);
            collatorMap.put(scheme, ref);
            collationIdMap.put(collationId, ref);
            schemeToIdMap.put(scheme, collationId);

            return akCollator;
        }
    }

    public static AkCollator getAkCollator(final int collatorId) {
        final SoftReference<AkCollator> ref = collationIdMap.get(collatorId);
        AkCollator collator = (ref == null ? null : ref.get());
        if (collator == null) {
            if (collatorId == UCS_BINARY_ID) {
                return UCS_BINARY_COLLATOR;
            }
            else {
                String scheme = getKeyByValue(schemeToIdMap, collatorId);
                if (scheme == null) return null;
                return getAkCollator(scheme);
            }
        } else {
            cacheHits++;
        }

        return collator;
    }

    public static <T, E> T getKeyByValue(Map<T, E> map, E value) {
        for (Entry<T, E> entry : map.entrySet()) {
            if (value.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Construct an actual ICU Collator given a collation specifier. The
     * result is a Collator that must be use in a thread-private manner.
     */
    static synchronized Collator forScheme(final CollationSpecifier specifier) {
        RuleBasedCollator collator = (RuleBasedCollator) sourceMap.get(specifier.toString());
        if (collator == null) {
            collator = specifier.createCollator();
            sourceMap.put(specifier.toString(), collator);
        }
        collator = collator.cloneAsThawed();
        return collator;
    }

    private static AkCollator mapToBinary(final String scheme) {
        collatorMap.put(scheme, new SoftReference<>(UCS_BINARY_COLLATOR));
        return UCS_BINARY_COLLATOR;
    }

    /**
     * Intended only for unit tests.
     *
     * @return Number of times either getAkCollator() method has returned a
     *         cached value.
     */
    static int getCacheHits() {
        return cacheHits;
    }
}
