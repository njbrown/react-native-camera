/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.google.android.cameraview;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import org.reactnative.camera.R;

@TargetApi(14)
class TextureViewPreview extends PreviewImpl {

    public interface TextureFrameListener
    {
        void onFramePreview(byte[] data, int width, int height, int orientation);
    }

    private final TextureView mTextureView;

    private int mDisplayOrientation;

    private TextureFrameListener frameListener;

    TextureViewPreview(Context context, ViewGroup parent) {
        final View view = View.inflate(context, R.layout.texture_view, parent);
        mTextureView = (TextureView) view.findViewById(R.id.texture_view);
        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {

            int[] argb;
            byte[] yuv;

            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                setSize(width, height);
                argb = new int[width * height];
                yuv = new byte[height * width + 2 * (int) Math.ceil(height/2.0) *(int) Math.ceil(width/2.0)];

                configureTransform();
                dispatchSurfaceChanged();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                setSize(width, height);
                argb = new int[width * height];
                yuv = new byte[height * width + 2 * (int) Math.ceil(height/2.0) *(int) Math.ceil(width/2.0)];
                
                configureTransform();
                dispatchSurfaceChanged();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                setSize(0, 0);
                dispatchSurfaceDestroyed();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

                Bitmap bitmap = TextureViewPreview.this.mTextureView.getBitmap();

                // convert surface to yuv12
                byte[] data = getYV12(bitmap.getWidth(), bitmap.getHeight(), bitmap);

                // trigger callback
                if(TextureViewPreview.this.frameListener != null) {
                    // Constants.ORIENTATION_UP
                    TextureViewPreview.this.frameListener.onFramePreview(data, bitmap.getWidth(), bitmap.getHeight(), 90 );
                }
            }

            // https://gist.github.com/wobbals/5725412
            private byte [] getYV12(int inputWidth, int inputHeight, Bitmap scaled) {

                //int [] argb = new int[inputWidth * inputHeight];

                scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight);

                //byte [] yuv = new byte[inputWidth*inputHeight*3/2];
                // https://stackoverflow.com/questions/5960247/convert-bitmap-array-to-yuv-ycbcr-nv21
                //byte [] yuv = new byte[inputHeight * inputWidth + 2 * (int) Math.ceil(inputHeight/2.0) *(int) Math.ceil(inputWidth/2.0)];
                encodeYV12(yuv, argb, inputWidth, inputHeight);

                //scaled.recycle();

                return yuv;
            }

            private void encodeYV12(byte[] yuv420sp, int[] argb, int width, int height) {
                final int frameSize = width * height;

                int yIndex = 0;
                int uIndex = frameSize;
                int vIndex = frameSize + (frameSize / 4);

                int a, R, G, B, Y, U, V;
                int index = 0;
                for (int j = 0; j < height; j++) {
                    for (int i = 0; i < width; i++) {

                        a = (argb[index] & 0xff000000) >> 24; // a is not used obviously
                        R = (argb[index] & 0xff0000) >> 16;
                        G = (argb[index] & 0xff00) >> 8;
                        B = (argb[index] & 0xff) >> 0;

                        // well known RGB to YUV algorithm
                        Y = ( (  66 * R + 129 * G +  25 * B + 128) >> 8) +  16;
                        U = ( ( -38 * R -  74 * G + 112 * B + 128) >> 8) + 128;
                        V = ( ( 112 * R -  94 * G -  18 * B + 128) >> 8) + 128;

                        // YV12 has a plane of Y and two chroma plans (U, V) planes each sampled by a factor of 2
                        //    meaning for every 4 Y pixels there are 1 V and 1 U.  Note the sampling is every other
                        //    pixel AND every other scanline.
                        yuv420sp[yIndex++] = (byte) ((Y < 0) ? 0 : ((Y > 255) ? 255 : Y));
                        if (j % 2 == 0 && index % 2 == 0) {
                            yuv420sp[uIndex++] = (byte)((V<0) ? 0 : ((V > 255) ? 255 : V));
                            yuv420sp[vIndex++] = (byte)((U<0) ? 0 : ((U > 255) ? 255 : U));
                        }

                        index ++;
                    }
                }
            }
        });
    }

    public void setFrameListener(TextureFrameListener listener)
    {
        this.frameListener = listener;
    }

    // This method is called only from Camera2.
    @TargetApi(15)
    @Override
    void setBufferSize(int width, int height) {
        mTextureView.getSurfaceTexture().setDefaultBufferSize(width, height);
    }

    @Override
    Surface getSurface() {
        return new Surface(mTextureView.getSurfaceTexture());
    }

    @Override
    SurfaceTexture getSurfaceTexture() {
        return mTextureView.getSurfaceTexture();
    }

    @Override
    View getView() {
        return mTextureView;
    }

    @Override
    Class getOutputClass() {
        return SurfaceTexture.class;
    }

    @Override
    void setDisplayOrientation(int displayOrientation) {
        mDisplayOrientation = displayOrientation;
        configureTransform();
    }

    @Override
    boolean isReady() {
        return mTextureView.getSurfaceTexture() != null;
    }

    /**
     * Configures the transform matrix for TextureView based on {@link #mDisplayOrientation} and
     * the surface size.
     */
    void configureTransform() {
        Matrix matrix = new Matrix();
        if (mDisplayOrientation % 180 == 90) {
            final int width = getWidth();
            final int height = getHeight();
            // Rotate the camera preview when the screen is landscape.
            matrix.setPolyToPoly(
                    new float[]{
                            0.f, 0.f, // top left
                            width, 0.f, // top right
                            0.f, height, // bottom left
                            width, height, // bottom right
                    }, 0,
                    mDisplayOrientation == 90 ?
                            // Clockwise
                            new float[]{
                                    0.f, height, // top left
                                    0.f, 0.f, // top right
                                    width, height, // bottom left
                                    width, 0.f, // bottom right
                            } : // mDisplayOrientation == 270
                            // Counter-clockwise
                            new float[]{
                                    width, 0.f, // top left
                                    width, height, // top right
                                    0.f, 0.f, // bottom left
                                    0.f, height, // bottom right
                            }, 0,
                    4);
        } else if (mDisplayOrientation == 180) {
            matrix.postRotate(180, getWidth() / 2, getHeight() / 2);
        }
        mTextureView.setTransform(matrix);
    }

}
