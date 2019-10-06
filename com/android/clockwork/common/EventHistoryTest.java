package com.android.clockwork.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class EventHistoryTest {

    public class MockEvent extends EventHistory.Event {
        private final String event;
        MockEvent(String event) {
            this.event = event;
        }

        @Override
        public String getName() {
            return event;
        }

        @Override
        public long getTimestampMs() {
            return 0;
        }

        @Override
        public boolean isDuplicateOf(EventHistory.Event event) {
            return this.getName().equals(event.getName());
        }
    }

    private EventHistory<MockEvent> mEventHistory;

    @Test
    public void testBasicRecordEvents() {
        mEventHistory = new EventHistory<>("foo", 10, true);

        MockEvent evt1 = new MockEvent("One");
        MockEvent evt2 = new MockEvent("Two");

        assertTrue(mEventHistory.recordEvent(evt1));
        assertEquals(evt1, mEventHistory.getMostRecentEvent());
        assertEquals(Collections.singletonList(evt1), mEventHistory.getAllEvents());

        assertTrue(mEventHistory.recordEvent(evt2));
        assertEquals(evt2, mEventHistory.getMostRecentEvent());
        assertEquals(Arrays.asList(evt2, evt1), mEventHistory.getAllEvents());
    }

    @Test
    public void testEventHistoryNoDups() {
        mEventHistory = new EventHistory<>("foo", 10, false);

        MockEvent evt1 = new MockEvent("One");
        MockEvent evt1Copy = new MockEvent("One");
        MockEvent evt2 = new MockEvent("Two");
        MockEvent evt3 = new MockEvent("Three");

        assertTrue(mEventHistory.recordEvent(evt1));
        Assert.assertFalse(mEventHistory.recordEvent(evt1Copy));
        assertEquals(evt1, mEventHistory.getMostRecentEvent());
        assertEquals(Collections.singletonList(evt1), mEventHistory.getAllEvents());

        assertTrue(mEventHistory.recordEvent(evt2));
        assertTrue(mEventHistory.recordEvent(evt3));
        assertTrue(mEventHistory.recordEvent(evt1Copy));
        assertEquals(evt1Copy, mEventHistory.getMostRecentEvent());
        assertEquals(
                Arrays.asList(evt1Copy, evt3, evt2, evt1),
                mEventHistory.getAllEvents());
    }

    @Test
    public void testEventHistoryAllowsDups() {
        mEventHistory = new EventHistory<>("foo", 10, true);

        MockEvent evt1 = new MockEvent("One");
        MockEvent evt1Copy = new MockEvent("One");

        assertTrue(mEventHistory.recordEvent(evt1));
        assertTrue(mEventHistory.recordEvent(evt1Copy));
        assertEquals(evt1Copy, mEventHistory.getMostRecentEvent());
        assertEquals(Arrays.asList(evt1Copy, evt1), mEventHistory.getAllEvents());
    }

    @Test
    public void testSizeLimit() {
        mEventHistory = new EventHistory<>("foo", 2, true);

        MockEvent evt1 = new MockEvent("One");
        MockEvent evt2 = new MockEvent("Two");
        MockEvent evt3 = new MockEvent("Three");
        MockEvent evt4 = new MockEvent("Four");

        assertTrue(mEventHistory.recordEvent(evt1));
        assertTrue(mEventHistory.recordEvent(evt2));
        assertEquals(Arrays.asList(evt2, evt1), mEventHistory.getAllEvents());

        assertTrue(mEventHistory.recordEvent(evt3));
        assertEquals(Arrays.asList(evt3, evt2), mEventHistory.getAllEvents());

        assertTrue(mEventHistory.recordEvent(evt4));
        assertEquals(Arrays.asList(evt4, evt3), mEventHistory.getAllEvents());
    }
}
