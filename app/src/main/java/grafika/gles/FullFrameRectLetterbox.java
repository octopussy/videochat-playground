package grafika.gles;

import android.annotation.TargetApi;
import android.opengl.Matrix;
import android.os.Build;

@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class FullFrameRectLetterbox extends FullFrameRect {
    public FullFrameRectLetterbox(Texture2dProgram program) {
        super(program);
    }

    private void drawFrame(int textureId, float[] texMatrix, float[] matrix) {

        mProgram.draw(matrix, mRectDrawable.getVertexArray(), 0,
                mRectDrawable.getVertexCount(), mRectDrawable.getCoordsPerVertex(),
                mRectDrawable.getVertexStride(),
                texMatrix, mRectDrawable.getTexCoordArray(), textureId,
                mRectDrawable.getTexCoordStride());
    }

    private float[] makeMatrix() {

        float[] matrix = new float[16];
        Matrix.setIdentityM(matrix, 0);

        return matrix;
    }

    public void drawFrameY(int textureId, float[] texMatrix, int rotate, float scale) {

        float[] matrix = makeMatrix();

        if (rotate != 0) {
            Matrix.rotateM(matrix, 0, rotate, 0.0f, 0.0f, 1.0f);
        }
        if (scale != 1.0f) {
            Matrix.scaleM(matrix, 0, 1, scale, 1);
        }

        drawFrame(textureId, texMatrix, matrix);
    }

    public void drawFrameMirrorY(int textureId, float[] texMatrix, int rotate, float scale) {

        float[] matrix = makeMatrix();

        if (rotate != 0) {
            Matrix.rotateM(matrix, 0, rotate, 0.0f, 0.0f, 1.0f);
        }
        if (rotate == 90 || rotate == 270) {
            scale = -scale;
        }
        if (scale != 1.0f) {
            Matrix.scaleM(matrix, 0, 1, scale, 1);
        }
        if (rotate == 0 || rotate == 180) {
            Matrix.scaleM(matrix, 0, -1, 1, 1);
        }

        drawFrame(textureId, texMatrix, matrix);
    }

    public void drawFrameX(int textureId, float[] texMatrix, int rotate, float scale) {

        float[] matrix = makeMatrix();

        if (rotate != 0) {
            Matrix.rotateM(matrix, 0, rotate, 0.0f, 0.0f, 1.0f);
        }
        if (scale != 1.0f) {
            Matrix.scaleM(matrix, 0, scale, 1, 1);
        }

        drawFrame(textureId, texMatrix, matrix);
    }

    public void drawFrameMirrorX(int textureId, float[] texMatrix, int rotate, float scale) {

        float[] matrix = makeMatrix();

        if (rotate != 0) {
            Matrix.rotateM(matrix, 0, rotate, 0.0f, 0.0f, 1.0f);
        }
        if (rotate == 0 || rotate == 180) {
            scale = -scale;
        }
        if (scale != 1.0f) {
            Matrix.scaleM(matrix, 0, scale, 1, 1);
        }
        if (rotate == 90 || rotate == 270) {
            Matrix.scaleM(matrix, 0, 1, -1, 1);
        }

        drawFrame(textureId, texMatrix, matrix);
    }

    public void drawFrameY(boolean mirror, int textureId, float[] texMatrix, int rotate, float scale) {
        if (mirror) {
            this.drawFrameMirrorY(textureId, texMatrix, rotate, scale);
        } else {
            this.drawFrameY(textureId, texMatrix, rotate, scale);
        }

    }

    public void drawFrameX(boolean mirror, int textureId, float[] texMatrix, int rotate, float scale) {
        if (mirror) {
            this.drawFrameMirrorX(textureId, texMatrix, rotate, scale);
        } else {
            this.drawFrameX(textureId, texMatrix, rotate, scale);
        }

    }

}
