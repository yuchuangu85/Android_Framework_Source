package com.android.server.wifi.hotspot2;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class Chronograph extends Thread {
    private final Map<Long, Set<AlarmEntry>> mAlarmEntryMap = new TreeMap<Long, Set<AlarmEntry>>();
    private boolean mRecalculate;

    private static class AlarmEntry {
        private final long mAt;
        private final AlarmHandler mAlarmHandler;
        private final Object mToken;

        private AlarmEntry(long at, AlarmHandler alarmHandler, Object token) {
            mAt = at;
            mAlarmHandler = alarmHandler;
            mToken = token;
        }

        private void callout() {
            mAlarmHandler.wake(mToken);
        }
    }

    public Chronograph()
    {
        setName("Chronograph");
        setDaemon(true);
    }

    public Object addAlarm(long interval, AlarmHandler handler, Object token) {
        long at = System.currentTimeMillis() + interval;
        synchronized (mAlarmEntryMap) {
            AlarmEntry alarmEntry = new AlarmEntry(at, handler, token);
            Set<AlarmEntry> entries = mAlarmEntryMap.get(at);
            if (entries == null) {
                entries = new HashSet<AlarmEntry>(1);
                mAlarmEntryMap.put(at, entries);
            }
            entries.add(alarmEntry);
            mRecalculate = true;
            mAlarmEntryMap.notifyAll();
            return alarmEntry;
        }
    }

    public boolean cancelAlarm(Object key) {
        if (key == null || key.getClass() != AlarmEntry.class) {
            throw new IllegalArgumentException("Not an alarm key");
        }

        AlarmEntry alarmEntry = (AlarmEntry)key;

        synchronized (mAlarmEntryMap) {
            Set<AlarmEntry> entries = mAlarmEntryMap.get(alarmEntry.mAt);
            if (entries == null) {
                return false;
            }
            if (entries.remove(alarmEntry)) {
                mRecalculate = true;
                mAlarmEntryMap.notifyAll();
                return true;
            }
            return false;
        }
    }

    @Override
    public void run() {

        for(;;) {

            long now = System.currentTimeMillis();
            List<Set<AlarmEntry>> pending = new ArrayList<Set<AlarmEntry>>();

            long nextExpiration = 0;

            synchronized (mAlarmEntryMap) {

                Iterator<Map.Entry<Long,Set<AlarmEntry>>> entries =
                        mAlarmEntryMap.entrySet().iterator();

                while (entries.hasNext()) {
                    Map.Entry<Long,Set<AlarmEntry>> entry = entries.next();
                    if (entry.getKey() <= now) {
                        pending.add(entry.getValue());
                        entries.remove();
                    }
                    else {
                        nextExpiration = entry.getKey();
                        break;
                    }
                }
            }

            for (Set<AlarmEntry> alarmEntries : pending) {
                for (AlarmEntry alarmEntry : alarmEntries) {
                    alarmEntry.callout();
                }
            }

            now = System.currentTimeMillis();

            synchronized (mAlarmEntryMap) {
                long sleep = nextExpiration - now;
                while (sleep > 0 && !mRecalculate) {
                    try {
                        mAlarmEntryMap.wait(sleep);
                    }
                    catch (InterruptedException ie) {
                        /**/
                    }
                    sleep = nextExpiration - System.currentTimeMillis();
                }
            }
        }
    }

    public static void main(String[] args) throws InterruptedException{
        Chronograph chronograph = new Chronograph();
        chronograph.start();

        chronograph.addAlarm(3000L, new AlarmHandler() {
            @Override
            public void wake(Object token) {
                System.out.println("3: " + token);
            }
        }, "3s" );

        Object key = chronograph.addAlarm(7500L, new AlarmHandler() {
            @Override
            public void wake(Object token) {
                System.out.println("7: " + token);
            }
        }, "7.5s" );

        chronograph.addAlarm(10000L, new AlarmHandler() {
            @Override
            public void wake(Object token) {
                System.out.println("10: " + token);
            }
        }, "10.00s" );

        System.out.println(chronograph.cancelAlarm(key));

        chronograph.join();
    }
}
