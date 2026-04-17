/* GENERATED SOURCE. DO NOT MODIFY. */
// © 2022 and later: Unicode, Inc. and others.
// License & terms of use: https://www.unicode.org/copyright.html

package android.icu.message2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This maps closely to the official specification.
 * Since it is not final, we will not add javadoc everywhere.
 *
 * <p>See <a target="github" href="https://github.com/unicode-org/message-format-wg/blob/main/spec/data-model/README.md">the
 * latest description</a>.</p>
 *
 * @deprecated This API is for technology preview only.
 * @hide Only a subset of ICU is exposed in Android
 * @hide draft / provisional / internal are hidden on Android
 */
@Deprecated
@SuppressWarnings("javadoc")
public class MFDataModel {

    private MFDataModel() {
        // Prevent instantiation
    }

    // Messages

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public interface Message {
        // Provides a common type for PatternMessage and SelectMessage.
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public static class PatternMessage implements Message {
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final List<Declaration> declarations;
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final Pattern pattern;

        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public PatternMessage(List<Declaration> declarations, Pattern pattern) {
            this.declarations = declarations;
            this.pattern = pattern;
        }
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public static class SelectMessage implements Message {
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final List<Declaration> declarations;
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final List<Expression> selectors;
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final List<Variant> variants;

        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public SelectMessage(
                List<Declaration> declarations,
                List<Expression> selectors,
                List<Variant> variants) {
            this.declarations = declarations;
            this.selectors = selectors;
            this.variants = variants;
        }
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public interface Declaration {
        // Provides a common type for InputDeclaration, and LocalDeclaration
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public static class InputDeclaration implements Declaration {
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final String name;
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final VariableExpression value;

        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public InputDeclaration(String name, VariableExpression value) {
            this.name = name;
            this.value = value;
        }
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public static class LocalDeclaration implements Declaration {
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final String name;
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final Expression value;

        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public LocalDeclaration(String name, Expression value) {
            this.name = name;
            this.value = value;
        }
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public interface LiteralOrCatchallKey {
        // Provides a common type for the selection keys: Variant, Literal, or CatchallKey.
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public static class Variant implements LiteralOrCatchallKey {
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final List<LiteralOrCatchallKey> keys;
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final Pattern value;

        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public Variant(List<LiteralOrCatchallKey> keys, Pattern value) {
            this.keys = keys;
            this.value = value;
        }
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public static class CatchallKey implements LiteralOrCatchallKey {
        final static String AS_KEY_STRING = "<<::CatchallKey::>>";
        // String value; // Always '*' in MF2

        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public static boolean isCatchAll(String key) {
            return AS_KEY_STRING.equals(key);
        }
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public CatchallKey() {}
    }

    // Patterns

    // type Pattern = Array<string | Expression | Markup>;
    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public static class Pattern {
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final List<PatternPart> parts;

        Pattern() {
            this.parts = new ArrayList<>();
        }
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public interface PatternPart {
        // Provides a common type for StringPart and Expression.
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public static class StringPart implements PatternPart {
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final String value;

        StringPart(String value) {
            this.value = value;
        }
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public interface Expression extends PatternPart {
        // Provides a common type for all kind of expressions:
        // LiteralExpression, VariableExpression, FunctionExpression, Markup
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public static class LiteralExpression implements Expression {
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final Literal arg;
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final FunctionRef function;
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final List<Attribute> attributes;

        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public LiteralExpression(Literal arg, FunctionRef function, List<Attribute> attributes) {
            this.arg = arg;
            this.function = function;
            this.attributes = attributes;
        }
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public static class VariableExpression implements Expression {
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final VariableRef arg;
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final FunctionRef function;
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final List<Attribute> attributes;

        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public VariableExpression(
                VariableRef arg, FunctionRef function, List<Attribute> attributes) {
            this.arg = arg;
            this.function = function;
            this.attributes = attributes;
        }
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public static class FunctionRef {
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final String name;
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final Map<String, Option> options;

        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public FunctionRef(String name, Map<String, Option> options) {
            this.name = name;
            this.options = options;
        }
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public static class FunctionExpression implements Expression {
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final FunctionRef function;
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final List<Attribute> attributes;

        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public FunctionExpression(FunctionRef function, List<Attribute> attributes) {
            this.function = function;
            this.attributes = attributes;
        }
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public static class Attribute {
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final String name;
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final LiteralOrVariableRef value;

        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public Attribute(String name, LiteralOrVariableRef value) {
            this.name = name;
            this.value = value;
        }
    }

    // Expressions

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public interface LiteralOrVariableRef {
        // Provides a common type for Literal and VariableRef,
        // to represent things like `foo` / `|foo|` / `1234` (literals)
        // and `$foo` (VariableRef), as argument for placeholders or value in options.
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public static class Literal implements LiteralOrVariableRef, LiteralOrCatchallKey {
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final String value;

        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public Literal(String value) {
            this.value = value;
        }
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public static class VariableRef implements LiteralOrVariableRef {
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final String name;

        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public VariableRef(String name) {
            this.name = name;
        }
    }

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public static class Option {
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final String name;
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final LiteralOrVariableRef value;

        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public Option(String name, LiteralOrVariableRef value) {
            this.name = name;
            this.value = value;
        }
    }

    // Markup

    /**
     * @deprecated This API is for technology preview only.
     * @hide Only a subset of ICU is exposed in Android
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    public static class Markup implements Expression {
        enum Kind {
            OPEN,
            CLOSE,
            STANDALONE
        }

        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final Kind kind;
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final String name;
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final Map<String, Option> options;
        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public final List<Attribute> attributes;

        /**
         * @deprecated This API is for technology preview only.
         * @hide draft / provisional / internal are hidden on Android
         */
        @Deprecated
        public Markup(
                Kind kind, String name, Map<String, Option> options, List<Attribute> attributes) {
            this.kind = kind;
            this.name = name;
            this.options = options;
            this.attributes = attributes;
        }
    }
}
