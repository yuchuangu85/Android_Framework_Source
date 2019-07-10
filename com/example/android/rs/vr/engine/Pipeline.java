/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.example.android.rs.vr.engine;

/**
 * The interface to the vr rendering pipeline
 * This defines the various rendering stages that the system goes through
 */
public interface Pipeline {
    /**
     * cancel the current execution
     */
    public void cancel();

    /**
     * @return true if the rendering was canceled the output is not valid if true
     */
    public boolean isCancel();

    /**
     * The pipline uses this stage to setup buffers it needs
     * @param state
     */
    public void initBuffers(VrState state);

    /**
     * This stage does needed transformations on the triangles to screen space
     * @param state
     */
    public void setupTriangles(VrState state);

    /**
     * This stage converts triangles into buffers used in the raycast
     * @param state
     */
    public void rasterizeTriangles(VrState state);

    /**
     * This stage generates the final image
     * @param state
     */
    public void raycast(VrState state);
}
