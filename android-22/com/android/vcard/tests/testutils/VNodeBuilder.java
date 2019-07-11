/*
 * Copyright (C) 2009 The Android Open Source Project
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
package com.android.vcard.tests.testutils;

import com.android.vcard.VCardConfig;
import com.android.vcard.VCardInterpreter;
import com.android.vcard.VCardProperty;
import com.android.vcard.VCardUtils;

import android.content.ContentValues;
import android.util.Base64;
import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * The class storing the parse result to custom datastruct:
 * {@link VNode}, and {@link PropertyNode}.
 * Maybe several vcard instance, so use vNodeList to store.
 * </p>
 * <p>
 * This is called VNode, not VCardNode, since it was used for expressing vCalendar (iCal).
 * </p>
 */
public class VNodeBuilder implements VCardInterpreter {
    private static String LOG_TAG = "VNodeBuilder";

    private List<VNode> mVNodeList = new ArrayList<VNode>();
    private VNode mCurrentVNode;

    /**
     * The charset using which VParser parses the text.
     */
    private String mSourceCharset;

    /**
     * The charset with which byte array is encoded to String.
     */
    private String mTargetCharset;

    private boolean mStrictLineBreakParsing;

    public VNodeBuilder() {
        this(VCardConfig.DEFAULT_IMPORT_CHARSET, false);
    }

    public VNodeBuilder(String targetCharset, boolean strictLineBreakParsing) {
        mSourceCharset = VCardConfig.DEFAULT_INTERMEDIATE_CHARSET;
        if (targetCharset != null) {
            mTargetCharset = targetCharset;
        } else {
            mTargetCharset = VCardConfig.DEFAULT_IMPORT_CHARSET;
        }
        mStrictLineBreakParsing = strictLineBreakParsing;
    }

    @Override
    public void onVCardStarted() {
    }

    @Override
    public void onVCardEnded() {
    }

    @Override
    public void onEntryStarted() {
        mCurrentVNode = new VNode();
        mVNodeList.add(mCurrentVNode);
    }

    @Override
    public void onEntryEnded() {
        int lastIndex = mVNodeList.size() - 1;
        mVNodeList.remove(lastIndex--);
        mCurrentVNode = lastIndex >= 0 ? mVNodeList.get(lastIndex) : null;
    }

    @Override
    public void onPropertyCreated(VCardProperty property) {
        // TODO: remove PropertyNode.
        PropertyNode propNode = new PropertyNode();
        propNode.propName = property.getName();
        List<String> groupList = property.getGroupList();
        if (groupList != null) {
            propNode.propGroupSet.addAll(groupList);
        }
        Map<String, Collection<String>> propertyParameterMap = property.getParameterMap();
        for (String paramType : propertyParameterMap.keySet()) {
            Collection<String> paramValueList = propertyParameterMap.get(paramType);
            if (paramType.equalsIgnoreCase("TYPE")) {
                propNode.paramMap_TYPE.addAll(paramValueList);
            } else {
                for (String paramValue : paramValueList) {
                    propNode.paramMap.put(paramType, paramValue);
                }
            }
        }

        // TODO: just redundant

        if (property.getRawValue() == null) {
            propNode.propValue_bytes = null;
            propNode.propValue_vector.clear();
            propNode.propValue_vector.add("");
            propNode.propValue = "";
            return;
        }

        final List<String> values = property.getValueList();
        if (values == null || values.size() == 0) {
            propNode.propValue_vector.clear();
            propNode.propValue_vector.add("");
            propNode.propValue = "";
        } else {
            propNode.propValue_vector.addAll(values);
            propNode.propValue = listToString(propNode.propValue_vector);
        }
        propNode.propValue_bytes = property.getByteValue();

        mCurrentVNode.propList.add(propNode);
    }

    private String listToString(List<String> list){
        int size = list.size();
        if (size > 1) {
            StringBuilder typeListB = new StringBuilder();
            for (String type : list) {
                typeListB.append(type).append(";");
            }
            int len = typeListB.length();
            if (len > 0 && typeListB.charAt(len - 1) == ';') {
                return typeListB.substring(0, len - 1);
            }
            return typeListB.toString();
        } else if (size == 1) {
            return list.get(0);
        } else {
            return "";
        }
    }

    public String getResult(){
        throw new RuntimeException("Not supported");
    }

    public List<VNode> getVNodeList() {
        return mVNodeList;
    }

    public VNode getCurrentVNode() {
        return mCurrentVNode;
    }
}
