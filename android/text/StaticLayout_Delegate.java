package android.text;

import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.icu.text.BreakIterator;
import android.text.Layout.BreakStrategy;
import android.text.Layout.HyphenationFrequency;
import android.text.Primitive.PrimitiveType;
import android.text.StaticLayout.LineBreaks;

import java.text.CharacterIterator;
import java.util.ArrayList;
import java.util.List;

import javax.swing.text.Segment;

/**
 * Delegate that provides implementation for native methods in {@link android.text.StaticLayout}
 * <p/>
 * Through the layoutlib_create tool, selected methods of StaticLayout have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 */
public class StaticLayout_Delegate {

    private static final char CHAR_SPACE     = 0x20;
    private static final char CHAR_TAB       = 0x09;
    private static final char CHAR_NEWLINE   = 0x0A;
    private static final char CHAR_ZWSP      = 0x200B;  // Zero width space.

    // ---- Builder delegate manager ----
    private static final DelegateManager<Builder> sBuilderManager =
        new DelegateManager<Builder>(Builder.class);

    @LayoutlibDelegate
    /*package*/ static long nInit(
            @BreakStrategy int breakStrategy,
            @HyphenationFrequency int hyphenationFrequency,
            boolean isJustified,
            @Nullable int[] indents,
            @Nullable int[] leftPaddings,
            @Nullable int[] rightPaddings) {
        Builder builder = new Builder();
        builder.mBreakStrategy = breakStrategy;
        return sBuilderManager.addNewDelegate(builder);
    }

    @LayoutlibDelegate
    /*package*/ static void nFinish(long nativePtr) {
        sBuilderManager.removeJavaReferenceFor(nativePtr);
    }

    @LayoutlibDelegate
    /*package*/ static int nComputeLineBreaks(
            /* non zero */ long nativePtr,

            // Inputs
            @NonNull char[] text,
            long measuredTextPtr,
            int length,
            float firstWidth,
            int firstWidthLineCount,
            float restWidth,
            @Nullable int[] variableTabStops,
            int defaultTabStop,
            int indentsOffset,

            // Outputs
            @NonNull LineBreaks recycle,
            int recycleLength,
            @NonNull int[] recycleBreaks,
            @NonNull float[] recycleWidths,
            @NonNull float[] recycleAscents,
            @NonNull float[] recycleDescents,
            @NonNull int[] recycleFlags,
            @NonNull float[] charWidths) {
        Builder builder = sBuilderManager.getDelegate(nativePtr);
        if (builder == null) {
            return 0;
        }

        builder.mText = text;
        builder.mWidths = new float[length];
        builder.mLineWidth = new LineWidth(firstWidth, firstWidthLineCount, restWidth);
        builder.mTabStopCalculator = new TabStops(variableTabStops, defaultTabStop);

        MeasuredParagraph_Delegate.computeRuns(measuredTextPtr, builder);

        // compute all possible breakpoints.
        BreakIterator it = BreakIterator.getLineInstance();
        it.setText((CharacterIterator) new Segment(builder.mText, 0, length));

        // average word length in english is 5. So, initialize the possible breaks with a guess.
        List<Integer> breaks = new ArrayList<Integer>((int) Math.ceil(length / 5d));
        int loc;
        it.first();
        while ((loc = it.next()) != BreakIterator.DONE) {
            breaks.add(loc);
        }

        List<Primitive> primitives =
                computePrimitives(builder.mText, builder.mWidths, length, breaks);
        switch (builder.mBreakStrategy) {
            case Layout.BREAK_STRATEGY_SIMPLE:
                builder.mLineBreaker = new GreedyLineBreaker(primitives, builder.mLineWidth,
                        builder.mTabStopCalculator);
                break;
            case Layout.BREAK_STRATEGY_HIGH_QUALITY:
                // TODO
//                break;
            case Layout.BREAK_STRATEGY_BALANCED:
                builder.mLineBreaker = new OptimizingLineBreaker(primitives, builder.mLineWidth,
                        builder.mTabStopCalculator);
                break;
            default:
                assert false : "Unknown break strategy: " + builder.mBreakStrategy;
                builder.mLineBreaker = new GreedyLineBreaker(primitives, builder.mLineWidth,
                        builder.mTabStopCalculator);
        }
        builder.mLineBreaker.computeBreaks(recycle);
        System.arraycopy(builder.mWidths, 0, charWidths, 0, builder.mWidths.length);
        return recycle.breaks.length;
    }

    /**
     * Compute metadata each character - things which help in deciding if it's possible to break
     * at a point or not.
     */
    @NonNull
    private static List<Primitive> computePrimitives(@NonNull char[] text, @NonNull float[] widths,
            int length, @NonNull List<Integer> breaks) {
        // Initialize the list with a guess of the number of primitives:
        // 2 Primitives per non-whitespace char and approx 5 chars per word (i.e. 83% chars)
        List<Primitive> primitives = new ArrayList<Primitive>(((int) Math.ceil(length * 1.833)));
        int breaksSize = breaks.size();
        int breakIndex = 0;
        for (int i = 0; i < length; i++) {
            char c = text[i];
            if (c == CHAR_SPACE || c == CHAR_ZWSP) {
                primitives.add(PrimitiveType.GLUE.getNewPrimitive(i, widths[i]));
            } else if (c == CHAR_TAB) {
                primitives.add(PrimitiveType.VARIABLE.getNewPrimitive(i));
            } else if (c != CHAR_NEWLINE) {
                while (breakIndex < breaksSize && breaks.get(breakIndex) < i) {
                    breakIndex++;
                }
                Primitive p;
                if (widths[i] != 0) {
                    if (breakIndex < breaksSize && breaks.get(breakIndex) == i) {
                        p = PrimitiveType.PENALTY.getNewPrimitive(i, 0, 0);
                    } else {
                        p = PrimitiveType.WORD_BREAK.getNewPrimitive(i, 0);
                    }
                    primitives.add(p);
                }

                primitives.add(PrimitiveType.BOX.getNewPrimitive(i, widths[i]));
            }
        }
        // final break at end of everything
        primitives.add(
                PrimitiveType.PENALTY.getNewPrimitive(length, 0, -PrimitiveType.PENALTY_INFINITY));
        return primitives;
    }

    // TODO: Rename to LineBreakerRef and move everything other than LineBreaker to LineBreaker.
    /**
     * Java representation of the native Builder class.
     */
    public static class Builder {
        char[] mText;
        float[] mWidths;
        private LineBreaker mLineBreaker;
        private int mBreakStrategy;
        private LineWidth mLineWidth;
        private TabStops mTabStopCalculator;
    }

    public abstract static class Run {
        int mStart;
        int mEnd;

        Run(int start, int end) {
            mStart = start;
            mEnd = end;
        }

        abstract void addTo(Builder builder);
    }
}
