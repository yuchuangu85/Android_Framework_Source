/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package java.text;


/*
 * Default implementation of BreakIterator. Wraps libcore.icu.NativeBreakIterator.
 * We need this because BreakIterator.isBoundary and BreakIterator.preceding are non-abstract,
 * and we don't have Java implementations of those methods (other than the current ones, which
 * forward to the wrapped NativeBreakIterator).
 */
class IcuIteratorWrapper extends BreakIterator {

    /* The wrapped ICU implementation. Non-final for #clone() */
    private com.ibm.icu.text.BreakIterator wrapped;

    IcuIteratorWrapper(com.ibm.icu.text.BreakIterator iterator) {
        wrapped = iterator;
    }

    @Override public int current() {
        return wrapped.current();
    }

    @Override public int first() {
        return wrapped.first();
    }

    @Override public int following(int offset) {
        checkOffset(offset);
        return wrapped.following(offset);
    }

    @Override public CharacterIterator getText() {
        return wrapped.getText();
    }

    @Override public int last() {
        return wrapped.last();
    }

    @Override public int next() {
        return wrapped.next();
    }

    @Override public int next(int n) {
        return wrapped.next(n);
    }

    @Override public int previous() {
        return wrapped.previous();
    }

    @Override public void setText(String newText) {
        wrapped.setText(newText);
    }

    @Override public void setText(CharacterIterator newText) {
        newText.current();
        wrapped.setText(newText);
    }

    @Override public boolean isBoundary(int offset) {
        checkOffset(offset);
        return wrapped.isBoundary(offset);
    }

    @Override public int preceding(int offset) {
        checkOffset(offset);
        return wrapped.preceding(offset);
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof IcuIteratorWrapper)) {
            return false;
        }
        return wrapped.equals(((IcuIteratorWrapper) o).wrapped);
    }

    @Override public String toString() {
        return wrapped.toString();
    }

    @Override public int hashCode() {
        return wrapped.hashCode();
    }

    private void checkOffset(int offset) {
        final CharacterIterator it = wrapped.getText();
        if (it == null) {
            throw new IllegalArgumentException("BreakIterator has no text");
        }
        if (offset < it.getBeginIndex() || offset > it.getEndIndex()) {
            String message = "Valid range is [" + it.getBeginIndex() + " " + it.getEndIndex() + "]";
            throw new IllegalArgumentException(message);
        }
    }

    @Override public Object clone() {
        IcuIteratorWrapper cloned = (IcuIteratorWrapper) super.clone();
        cloned.wrapped = (com.ibm.icu.text.BreakIterator) wrapped.clone();
        return cloned;
    }
}
