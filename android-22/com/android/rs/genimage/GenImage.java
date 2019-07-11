/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.rs.genimage;

import android.content.Context;

import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import android.opengl.GLUtils;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

public class GenImage implements GLSurfaceView.Renderer {
    private Bitmap mTestImage;

    private Triangle mTriangle;


    private Bitmap loadBitmap(Context context, int resource) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeResource(context.getResources(), resource, options);
    }

    GenImage(Context context) {

        mTestImage = loadBitmap(context, R.drawable.test_pattern);

    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {

        // Set the background frame color
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        mTriangle = new Triangle(mTestImage);
    }

    @Override
    public void onDrawFrame(GL10 unused) {

        // Draw background color
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Draw triangle
        mTriangle.draw();
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        // Adjust the viewport based on geometry changes,
        // such as screen rotation
        GLES20.glViewport(0, 0, 512, 512);
    }

    public static int loadShader(int type, String shaderCode){

        // create a vertex shader type (GLES20.GL_VERTEX_SHADER)
        // or a fragment shader type (GLES20.GL_FRAGMENT_SHADER)
        int shader = GLES20.glCreateShader(type);

        // add the source code to the shader and compile it
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);

        return shader;
    }

}



class Triangle {
    int mTextureIDs[] = new int[1];

    private final String vertexShaderCode =
        "varying vec2 vTex0;" +
        "varying vec2 vPos0;" +
        "attribute vec4 aPosition;" +
        "void main() {" +
        "  gl_Position = aPosition;" +
        "  vPos0 = aPosition.xy;" +
        "  vTex0 = ((aPosition.xy + 1.0) * 0.6);" +
        //"  vTex0 = (aPosition.xy * 1.7) + 0.5;" +
        "}";

    private final String fragmentShaderCode =
        "precision mediump float;" +
        "varying vec2 vTex0;" +
        "varying vec2 vPos0;" +
        "uniform sampler2D uSamp;" +
        "void main() {" +
        "  vec2 tc = vTex0;" +
        //"  tc.x *= pow(vPos0.y + 1.0, 2.0);" +
        //"  tc.y *= pow(vPos0.x + 1.0, 2.0);" +
        "  vec4 c = texture2D(uSamp, tc);" +
        "  c.a = 1.0;" +
        "  gl_FragColor = c;" +
        "}";

    private final FloatBuffer vertexBuffer;
    private final int mProgram;

    // number of coordinates per vertex in this array
    static float triangleCoords[] = { // in counterclockwise order:
       -1.0f,  1.0f, 0.0f,   // top left
       -1.0f, -1.0f, 0.0f,   // bottom left
        1.0f, -1.0f, 0.0f,   // bottom right

       -1.0f,  1.0f, 0.0f,   // top left
        1.0f, -1.0f, 0.0f,   // bottom right
        1.0f,  1.0f, 0.0f    // top right
    };

    FloatBuffer createFloatBuffer(float buf[]) {
        ByteBuffer bb = ByteBuffer.allocateDirect(buf.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(buf);
        fb.position(0);
        return fb;
    }

    public String setup(int key) {
        String s = new String();
        int tmp;

        tmp = key % 2;
        key /= 2;
        if (tmp != 0) {
            s += "N";
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        } else {
            s += "L";
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        }

        tmp = key % 2;
        key /= 2;
        if (tmp != 0) {
            s += "N";
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        } else {
            s += "L";
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        }

        tmp = key % 3;
        key /= 3;
        switch(tmp) {
        case 0:
            s += "_CE";
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            break;
        case 1:
            s += "_RE";
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
            break;
        case 2:
            s += "_MR";
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_MIRRORED_REPEAT);
            break;
        }

        tmp = key % 3;
        key /= 3;
        switch(tmp) {
        case 0:
            s += "_CE";
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            break;
        case 1:
            s += "_RE";
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
            break;
        case 2:
            s += "_MR";
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_MIRRORED_REPEAT);
            break;
        }

        if (key > 0) done = true;
        return s;
    }

    public Triangle(Bitmap testImage) {
        vertexBuffer = createFloatBuffer(triangleCoords);

        // prepare shaders and OpenGL program
        int vertexShader = GenImage.loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = GenImage.loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);

        mProgram = GLES20.glCreateProgram();             // create empty OpenGL Program
        GLES20.glAttachShader(mProgram, vertexShader);   // add the vertex shader to program
        GLES20.glAttachShader(mProgram, fragmentShader); // add the fragment shader to program
        GLES20.glLinkProgram(mProgram);                  // create OpenGL program executables

        GLES20.glGenTextures(1, mTextureIDs, 0);

        // Bind to the texture in OpenGL
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureIDs[0]);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, testImage, 0);
    }

    boolean done = false;
    int key = 0;

    public void draw() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        String ext = setup(key++);

        // Add program to OpenGL environment
        GLES20.glUseProgram(mProgram);

        int posA = GLES20.glGetAttribLocation(mProgram, "aPosition");
        GLES20.glEnableVertexAttribArray(posA);
        GLES20.glVertexAttribPointer(posA, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);

        int sampUni = GLES20.glGetUniformLocation(mProgram, "uSamp");
        GLES20.glUniform1i(sampUni, 0);

        // Draw the triangle
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, triangleCoords.length / 3);

        if (!done) {
            IntBuffer ib = IntBuffer.allocate(512*512);
            ib.position(0);
            GLES20.glReadPixels(0,0, 512, 512, GLES20.GL_RGBA,
                                GLES20.GL_UNSIGNED_BYTE, ib);

            Bitmap bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            bmp.setPixels(ib.array(), 0, 512, 0, 0, 512, 512);

            try {
                String s = new String("/sdcard/imgs/RsSampImg_");
                s += ext + ".png";
                FileOutputStream out = new FileOutputStream(s);
                bmp.compress(Bitmap.CompressFormat.PNG, 95, out);
                out.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            bmp.recycle();
        }
    }
}
