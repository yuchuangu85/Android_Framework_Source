/* GENERATED SOURCE. DO NOT MODIFY. */
// © 2022 and later: Unicode, Inc. and others.
// License & terms of use: https://www.unicode.org/copyright.html

package android.icu.message2;

import android.icu.text.FormattedValue;

/**
 * An immutable, richer formatting result, encapsulating a {@link FormattedValue},
 * the original value to format, and we are considering adding some more info.
 * Very preliminary.
 *
 * @deprecated This API is for ICU internal use only.
 * @hide Only a subset of ICU is exposed in Android
 * @hide draft / provisional / internal are hidden on Android
 */
@Deprecated
public class FormattedPlaceholder {
    private final FormattedValue formattedValue;
    private final Object inputValue;
    private final Directionality directionality;
    private final boolean isolate;

    /**
     * Constructor creating the {@code FormattedPlaceholder}.
     *
     * @param inputValue the original value to be formatted.
     * @param formattedValue the result of formatting the placeholder.
     * @param directionality the directionality of the formatted placeholder.
     * @param isolate true if the placeholder should be considered a bidi isolate. 
     *
     * @deprecated This API is for ICU internal use only.
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public FormattedPlaceholder(Object inputValue, FormattedValue formattedValue, Directionality directionality, boolean isolate) {
        if (formattedValue == null) {
            throw new IllegalAccessError("Should not try to wrap a null formatted value");
        }
        this.inputValue = inputValue;
        this.formattedValue = formattedValue;
        this.directionality = directionality;
        this.isolate = isolate;
    }

    /**
     * Constructor creating the {@code FormattedPlaceholder}.
     *
     * @param inputValue the original value to be formatted.
     * @param formattedValue the result of formatting the placeholder.
     *
     * @deprecated This API is for ICU internal use only.
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public FormattedPlaceholder(Object inputValue, FormattedValue formattedValue) {
        this(inputValue, formattedValue, Directionality.LTR, false);
    }

    /**
     * Retrieve the original input value that was formatted.
     *
     * @return the original value to be formatted.
     *
     * @deprecated This API is for ICU internal use only.
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public Object getInput() {
        return inputValue;
    }

    /**
     * Retrieve the formatted value.
     *
     * @return the result of formatting the placeholder.
     *
     * @deprecated This API is for ICU internal use only.
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public FormattedValue getFormattedValue() {
        return formattedValue;
    }

    /**
     * Retrieve the directionality of the formatted the placeholder.
     *
     * @return the directionality.
     *
     * @deprecated This API is for ICU internal use only.
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public Directionality getDirectionality() {
        return directionality;
    }

    /**
     * Retrieve the BiDi isolate setting of the formatted the placeholder.
     *
     * @return the BiDi isolate setting.
     *
     * @deprecated This API is for ICU internal use only.
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public boolean getIsolate() {
        return isolate;
    }

    /**
     * Returns a string representation of the object.
     * It can be null, which is unusual, and we plan to change that.
     *
     * @return a string representation of the object.
     *
     * @deprecated This API is for ICU internal use only.
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    @Override
    public String toString() {
        return formattedValue.toString();
    }
}
