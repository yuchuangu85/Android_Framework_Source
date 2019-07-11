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

import java.util.ArrayList;

/**
 * Previously used in main vCard handling code but now exists only for testing.
 *
 * TODO: remove this and relevant classes. Given we can have appropriate test cases for
 * VCardEntry and VCardProperty, we won't need redundancy here.
 */
public class VNode {
    public ArrayList<PropertyNode> propList = new ArrayList<PropertyNode>();
}
