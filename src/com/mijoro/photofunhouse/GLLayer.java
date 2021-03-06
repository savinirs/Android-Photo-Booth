
package com.mijoro.photofunhouse;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import com.google.android.apps.analytics.GoogleAnalyticsTracker;
import com.mijoro.photofunhouse.CameraPreviewSink.TextureRatio;
import com.mijoro.photofunhouse.shaders.BulgeShader;
import com.mijoro.photofunhouse.shaders.DuotoneShader;
import com.mijoro.photofunhouse.shaders.InverseShader;
import com.mijoro.photofunhouse.shaders.KaleidomirrorShader;
import com.mijoro.photofunhouse.shaders.PinchShader;
import com.mijoro.photofunhouse.shaders.ShaderProgram;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.opengl.GLSurfaceView.Renderer;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;

public class GLLayer extends GLSurfaceView implements Renderer, OnSeekBarChangeListener {
    private static final float ANIMATION_DURATION = 600.0f;

    private CameraPreviewSink sink;
    private TextureRatio mTexRatio;
    private int mWidth, mHeight;
    private boolean mSaveNextFrame = false;
    private boolean mOverviewMode = true;
    private float mTime = 0.0f;
    private float mTouchX = 0.5f;
    private float mTouchY = 0.5f;
    private float mLevel = 0.5f;
    private long mAnimationStartTime = 0;
    private GoogleAnalyticsTracker mTracker;
    
    boolean mIsTablet = false;
    
    private HostApplication mHostApplication;
    
    static {
        System.loadLibrary("yuv420sp2rgb");
    }
    
    public static interface HostApplication {
        public void photoTaken(Bitmap b);
        public void overviewModeShowing(boolean showing);
        public void showSlider(boolean show);
    }

    /**
     * native function, that converts a byte array from ycbcr420 to RGB
     * 
     * @param in
     * @param width
     * @param height
     * @param textureSize
     * @param out
     */
    public native void yuv420sp2rgb(byte[] in, int width, int height, int textureSize, byte[] out);
    static GLLayer layer;
    // What an ugly hack to make this a static method without having to refactor the jni code :)
    public static void decode(byte[] in, int width, int height, int textureSize, byte[] out) {
        if (layer == null) return;
        layer.yuv420sp2rgb(in, width, height, textureSize, out);
    }

    
    public GLLayer(Context c, AttributeSet attrs) {
        super(c, attrs);
        layer = this;
        setEGLContextClientVersion(2);
        this.setRenderer(this);
        mainQuadVertices = ByteBuffer.allocateDirect(mTriangleVerticesData.length
                * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mainQuadVertices.put(mTriangleVerticesData).position(0);
        setOnTouchListener(new OnTouchListener() {
            private static final float TAP_HYSTERESIS = 10.0f;
            private MotionEvent downEvent;
            private boolean moving = false;
            
            public boolean onTouch(View v, MotionEvent event) {
                if (!mOverviewMode) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        downEvent = MotionEvent.obtain(event);
                        moving = false;
                    } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                        if (Math.abs(event.getX() - downEvent.getX()) > TAP_HYSTERESIS ||
                            Math.abs(event.getY() - downEvent.getY()) > TAP_HYSTERESIS) {
                            moving = true;
                            mTouchX = event.getX() / getWidth();
                            mTouchY = event.getY() / getHeight();
                        }
                    } else if (event.getAction() == MotionEvent.ACTION_UP) {
                        if (!moving) toggleOverview();
                        moving = false;
                        return false;
                    }
                }
                if (mOverviewMode && event.getAction() == MotionEvent.ACTION_UP) {
                    int xindex = (int) (3.0f * event.getX() / (float)getWidth());
                    int yindex = (int) (3.0f * event.getY() / (float)getHeight());
                    int index = 3 * yindex + xindex % 3;
                   setProgramIndex(index);
                }
                return false;
            }
        });
        setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                
            }
        });
    }
    
    public void setTracker(GoogleAnalyticsTracker tracker) {
        mTracker = tracker;
    }
    
    public void setIsTablet(boolean isTablet) {
        
    }
    
    public boolean isOverviewMode() {
        return mOverviewMode;
    }
    
    public void setProgramIndex(int index) {
        if (index >= mPrograms.length) return;
        mProgramCounter = index;
        mProgram = mPrograms[mProgramCounter];
        mTouchX = 0.5f;
        mTouchY = 0.5f;
        mLevel = 0.5f;
        mHostApplication.showSlider(mProgram.usesValueSlider());
        setOverviewMode(false);
    }
    
    public void setCameraPreviewSink(CameraPreviewSink sink) {
        this.sink = sink;
        mTexRatio = sink.getTextureRatio();
        setTextureRatio(mTexRatio.width, mTexRatio.height);
    }
    
    public void setHostApplication(HostApplication l) {
        mHostApplication = l;
        mHostApplication.overviewModeShowing(mOverviewMode);
    }
    
    @Override
    public void onPause() {
        sink.onPause();
        super.onPause();
    }
    
    public void setOverviewMode(boolean overview) {
        mLevel = 0.5f;
        mOverviewMode = overview;
        mHostApplication.overviewModeShowing(overview);
        if (overview) 
            mTracker.trackPageView("/overview");
        else 
            mTracker.trackPageView("/" + getEffectName());
    }
    
    private String getEffectName() {
        if (mProgramCounter < 0 || mProgramCounter >= mShaderNames.length)
            return "unknown";
        return mShaderNames[mProgramCounter];
    }
    
    @Override
    public void onResume() {
        setOverviewMode(true);
        sink.onResume();
        setFrontFacing(sink.isFrontFacing());
        super.onResume();
    }
    
    public void toggleOverview() {
        mAnimationStartTime = System.currentTimeMillis();
        mOverviewMode = !mOverviewMode;
        mHostApplication.overviewModeShowing(mOverviewMode);
    }
    
    public void nextProgram() {
        ++mProgramCounter;
        if (mProgramCounter >= mPrograms.length) mProgramCounter = 0;
        mProgram = mPrograms[mProgramCounter]; 
    }
    public void previousProgram() {
        --mProgramCounter;
        if (mProgramCounter < 0) mProgramCounter = mPrograms.length - 1;
        mProgram = mPrograms[mProgramCounter]; 
    }
    
    public void saveImage() {
        mSaveNextFrame = true;
    }
    
    private void glSaveImage() {
        int w = mWidth;
        int h = mHeight;
        int b[]=new int[w*h];
        int bt[]=new int[w*h];
        IntBuffer ib=IntBuffer.wrap(b);
        ib.position(0);
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ib);
        //remember, that OpenGL bitmap is incompatible with Android bitmap
        //and so, some correction need
        for(int i=0; i<h; i++) {    
             for(int j=0; j<w; j++)
             {
                  int pix=b[i*w+j];
                  int pb=(pix>>16)&0xff;
                  int pr=(pix<<16)&0x00ff0000;
                  int pix1=(pix&0xff00ff00) | pr | pb;
                  bt[(h-i-1)*w+j]=pix1;
             }
        }                  
        Bitmap sb = Bitmap.createBitmap(bt, w, h, Bitmap.Config.ARGB_8888);
        mHostApplication.photoTaken(sb);
    }
    
    public void onDrawFrame(GL10 gl) {
        mTime += 0.01f;
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        sink.bindTexture();
        if (!mOverviewMode) {
            Matrix.setIdentityM(mMVPMatrix, 0);
            mProgram.drawQuad(mainQuadVertices, mMVPMatrix, mTime, mLevel);
        } else {
            long time = System.currentTimeMillis();
            float percent = Math.min((time - mAnimationStartTime) / ANIMATION_DURATION, 1.0f);
            Matrix.setIdentityM(mMVPMatrix, 0);
            Matrix.scaleM(mMVPMatrix, 0, 1.0f - 0.666f * percent, 1.0f - 0.666f * percent, 1.0f);
            float x = -2.0f + 2.0f * (mProgramCounter % 3);
            float y = 2.0f - 2.0f * (float)(Math.floor(mProgramCounter / 3));
            Matrix.translateM(mMVPMatrix, 0, x*percent, y*percent, 0.0f);
            mProgram.drawQuad(mainQuadVertices, mMVPMatrix, mTime, mLevel);
            for (int i = 0; i < mPrograms.length; ++i) {
                if (i == mProgramCounter) continue;
                Matrix.setIdentityM(mMVPMatrix, 0);
                Matrix.scaleM(mMVPMatrix, 0, 0.333f, 0.333f, 1.0f);
                x = -2.0f + 2.0f * (i % 3);
                y = 2.0f - 2.0f * (float)(Math.floor(i / 3));
                Matrix.translateM(mMVPMatrix, 0, x, y, 0.0f);
                mPrograms[i].drawQuad(mainQuadVertices, mMVPMatrix, mTime, mLevel);
            }
        }
        
        if (mSaveNextFrame) {
            glSaveImage();
            mSaveNextFrame = false;
        }
    }

    public void onSurfaceChanged(GL10 gl, int width, int height) {
        mWidth = width;
        mHeight = height;
        GLES20.glViewport(0, 0, width, height);
    }
    
    private String[] mShaderNames = {
            "solarize",
            "inverse",
            "duotone",
            "mirror",
            "trippy",
            "bulge",
            "kaleidomirror",
            "pinch",
            "pixellate"
    };

    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        mPrograms = new ShaderProgram[9];
        mPrograms[0] = new ShaderProgram(mTexRatio, ShaderProgram.buildFShader(getContext(), R.raw.solarize));
        mPrograms[1] = new InverseShader(mTexRatio);
        mPrograms[2] = new DuotoneShader(mTexRatio);
        if (mIsTablet)
            mPrograms[3] = new ShaderProgram(mTexRatio, ShaderProgram.buildFShader(getContext(), R.raw.mirrortablet));
        else
            mPrograms[3] = new ShaderProgram(mTexRatio, ShaderProgram.buildFShader(getContext(), R.raw.mirror));
        mPrograms[4] = new ShaderProgram(mTexRatio, ShaderProgram.buildFShader(getContext(), R.raw.trippy));
        mPrograms[5] = new BulgeShader(mTexRatio);
        mPrograms[6] = new KaleidomirrorShader(mTexRatio);
        mPrograms[7] = new PinchShader(mTexRatio);        
        mPrograms[8] = new ShaderProgram(mTexRatio, ShaderProgram.buildFShader(getContext(), R.raw.pixellate));
        mProgram = mPrograms[0];

        Matrix.setIdentityM(mMVPMatrix, 0);
    }
    
    public void setTextureRatio(float width, float height) {
        for (int index : mTriangleHeights) {
            mainQuadVertices.put(index, height);
        }
        for (int index : mTriangleWidths) {
            mainQuadVertices.put(index, width);
        }
    }
    
    public void setFrontFacing(boolean frontFacing) {
        float pos = frontFacing ? 1.0f : -1.0f;
        for (int index : mNegativeTriangleValues ) {
            mainQuadVertices.put(index, pos);
        }
        for (int index : mPositiveTriangleValues) {
            mainQuadVertices.put(index, pos * -1.0f);
        }
    }

    FloatBuffer makeFloatBuffer(float[] arr) {
        ByteBuffer bb = ByteBuffer.allocateDirect(arr.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(arr);
        fb.position(0);
        return fb;
    }

    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        if (shader != 0) {
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader " + shaderType + ":");
                Log.e(TAG, GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
        }
        return shader;
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) {
            return 0;
        }

        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0) {
            return 0;
        }

        int program = GLES20.glCreateProgram();
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader);
            checkGlError("glAttachShader");
            GLES20.glAttachShader(program, pixelShader);
            checkGlError("glAttachShader");
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: ");
                Log.e(TAG, GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
        }
        return program;
    }

    private void checkGlError(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(TAG, op + ": glError " + error);
            //throw new RuntimeException(op + ": glError " + error);
        }
    }

    private static final int FLOAT_SIZE_BYTES = 4;

    private final float[] mTriangleVerticesData = {
            // X,   Y,   Z,  U,    V
            -1.0f, -1.0f, 0, 0.0f, 0.0f,// 0  1  2  3  4
            1.0f, -1.0f, 0, 1.0f, 0.0f,// 5  6  7  8  9
            1.0f, 1.0f, 0, 1.0f, 1.0f,// 10 11 12 13 14

            -1.0f, -1.0f, 0,  0.0f, 0.0f,// 15 16 17 18 19
            -1.0f, 1.0f,  0, 0.0f, 1.0f,// 20 21 22 23 24
            1.0f, 1.0f,  0, 1.0f, 1.0f,// 25 26 27 28 29
    };
    // Indices of the widths and heights to update in the vertex data buffer in order to set
    // the texture bounds since we need to use a power of two texture, but have non-power-of-two
    // images.
    private int[] mTriangleHeights = {4, 9, 19};
    private int[] mTriangleWidths = {8, 13, 28};
    private int[] mPositiveTriangleValues = {5, 10, 25};
    private int[] mNegativeTriangleValues = {0, 15, 20};

    private FloatBuffer mainQuadVertices;

    private float[] mMVPMatrix = new float[16];
    ShaderProgram mProgram;
    private ShaderProgram[] mPrograms;
    private int mProgramCounter = 0;

    private static String TAG = "Photo Funhouse";
   
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        mLevel = (float)progress / 100.0f;
    }
    public void onStartTrackingTouch(SeekBar seekBar) {}
    public void onStopTrackingTouch(SeekBar seekBar) {}
}
