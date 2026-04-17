/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.admin.metadata;

import android.annotation.NonNull;
import android.app.admin.PolicyIdentifier;
import android.app.admin.PolicyValueTransport;
import android.app.admin.metadata.LongPolicyMetadata;

import java.util.List;

/**
 * Helper to convert a plain policy value to and from a policy transport.
 * Use {@link PolicyTransportValueConvertor#getInstance} to obtain the correct type-safe converter
 * for a policy.
 *
 * @param <T> What value kind this convertor works on
 * @hide
 */
public abstract class PolicyTransportValueConvertor<T> {
    private static final PolicyTransportValueConvertor<Boolean> BOOLEAN_CONVERTOR =
            new PolicyTransportValueConvertor<>() {
                @Override
                @NonNull
                public PolicyValueTransport toTransport(@NonNull Boolean value) {
                    return PolicyValueTransport.booleanField(value);
                }

                @NonNull
                @Override
                public Boolean fromTransport(@NonNull PolicyValueTransport transport) {
                    if (transport.getTag() != PolicyValueTransport.booleanField) {
                        throw new IllegalArgumentException(
                                "Policy value " + transport + " is not a boolean"
                        );
                    }

                    return transport.getBooleanField();
                }
            };
    private static final PolicyTransportValueConvertor<Long> LONG_CONVERTOR =
            new PolicyTransportValueConvertor<>() {
                @Override
                @NonNull
                public PolicyValueTransport toTransport(@NonNull Long value) {
                    return PolicyValueTransport.longField(value);
                }

                @NonNull
                @Override
                public Long fromTransport(@NonNull PolicyValueTransport transport) {
                    if (transport.getTag() != PolicyValueTransport.longField) {
                        throw new IllegalArgumentException(
                                "Policy value " + transport + " is not a long");
                    }

                    return transport.getLongField();
                }
            };
    private static final PolicyTransportValueConvertor<Integer> INTEGER_CONVERTOR =
            new PolicyTransportValueConvertor<>() {
                @Override
                @NonNull
                public PolicyValueTransport toTransport(@NonNull Integer value) {
                    return PolicyValueTransport.integerField(value);
                }

                @NonNull
                @Override
                public Integer fromTransport(@NonNull PolicyValueTransport transport) {
                    if (transport.getTag() != PolicyValueTransport.integerField) {
                        throw new IllegalArgumentException(
                                "Policy value " + transport + " is not an integer"
                        );
                    }

                    return transport.getIntegerField();
                }
            };
    private static final PolicyTransportValueConvertor<String> STRING_CONVERTOR =
            new PolicyTransportValueConvertor<>() {
                @Override
                @NonNull
                public PolicyValueTransport toTransport(@NonNull String value) {
                    return PolicyValueTransport.stringField(value);
                }

                @NonNull
                @Override
                public String fromTransport(@NonNull PolicyValueTransport transport) {
                    if (transport.getTag() != PolicyValueTransport.stringField) {
                        throw new IllegalArgumentException(
                                "Policy value " + transport + " is not a string"
                        );
                    }

                    return transport.getStringField();
                }
            };
    private static final PolicyTransportValueConvertor<List<String>> LIST_OF_STRING_CONVERTOR =
            new PolicyTransportValueConvertor<>() {
                @Override
                @NonNull
                public PolicyValueTransport toTransport(@NonNull List<String> value) {
                    return PolicyValueTransport.listOfStringField(value);
                }

                @NonNull
                @Override
                public List<String> fromTransport(@NonNull PolicyValueTransport transport) {
                    if (transport.getTag() != PolicyValueTransport.listOfStringField) {
                        throw new IllegalArgumentException(
                                "Policy value " + transport + " is not a list of string"
                        );
                    }

                    return transport.getListOfStringField();
                }
            };


    protected PolicyTransportValueConvertor() {
    }

    /**
     * Get a convertor from the policy metadata.
     *
     * @param metadata The metadata for a policy
     * @param <T>      The type of the policy and the resulting convertor
     * @return A convertor that works for values of the policy described in metadata
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @NonNull
    public static <T> PolicyTransportValueConvertor<T> getInstance(PolicyMetadata<T> metadata) {
        // Cast is safe since metadata already checked the type when building.
        return (PolicyTransportValueConvertor<T>) switch (metadata) {
            case BooleanPolicyMetadata m -> BOOLEAN_CONVERTOR;
            case IntegerPolicyMetadata m -> INTEGER_CONVERTOR;
            case LongPolicyMetadata m -> LONG_CONVERTOR;
            case EnumPolicyMetadata m -> INTEGER_CONVERTOR;
            case StringPolicyMetadata m -> STRING_CONVERTOR;
            // Need to use a raw type here since we can't extract the element E of T=List<E>.
            case ListPolicyMetadata m -> getListInstance(m);
            default -> throw new UnsupportedOperationException(
                    "Unsupported policy conversion for "
                            + metadata.getId()
            );
        };
    }

    /**
     * Get a convertor from the list policy metadata.
     *
     * @param metadata The metadata for a policy
     * @param <T>      The type of the elements
     * @return A convertor that works for values of the policy described in metadata
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @NonNull
    private static <T> PolicyTransportValueConvertor<List<T>> getListInstance(
            ListPolicyMetadata<T> metadata
    ) {
        // Cast is safe since metadata already checked the type when building.
        // Can't cast to PolicyTransportValueConvertor<List<T>> since List<T> is not a superclass
        // of List<String> (and other list types at the same time), we need to use a raw class
        // instead.
        return (PolicyTransportValueConvertor) switch(metadata.getElementMetadata()) {
            case StringPolicyMetadata m -> LIST_OF_STRING_CONVERTOR;
            default -> throw new UnsupportedOperationException(
                    "Unsupported list policy conversion for "
                            + metadata.getId()
            );
        };
    }

    /**
     * Get a convertor from the policy identifier.
     *
     * @param id  The policy identifier
     * @param <T> The type of the policy and the resulting convertor.
     * @return A convertor that works for values of the policy
     */
    @NonNull
    public static <T> PolicyTransportValueConvertor<T> getInstance(PolicyIdentifier<T> id) {
        return getInstance(GeneratedPolicyMetadata.getPolicyMetadata(id));
    }

    /**
     * Wrap a policy value in a transport.
     */
    @NonNull
    public abstract PolicyValueTransport toTransport(@NonNull T value);

    /**
     * Extract a policy from its transport.
     *
     * @throws IllegalArgumentException if transport contains the wrong type
     */
    @NonNull
    public abstract T fromTransport(@NonNull PolicyValueTransport transport);
}
