package com.android.server.wifi.hotspot2;

public class IconEvent {
    private final long mBSSID;
    private final String mFileName;
    private final int mSize;
    private final byte[] mData;

    public IconEvent(long bssid, String fileName, int size, byte[] data) {
        mBSSID = bssid;
        mFileName = fileName;
        mSize = size;
        mData = data;
    }

    public long getBSSID() {
        return mBSSID;
    }

    public String getFileName() {
        return mFileName;
    }

    public int getSize() {
        return mSize;
    }

    public byte[] getData() {
        return mData;
    }

    @Override
    public String toString() {
        return "IconEvent: " +
                "BSSID=" + String.format("%012x", mBSSID) +
                ", fileName='" + mFileName + '\'' +
                ", size=" + mSize;
    }
}
