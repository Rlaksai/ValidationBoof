package boofcv.metrics.sba;

import boofcv.alg.geo.bundle.cameras.BundleAdjustmentPinholeSimplified;
import georegression.struct.point.Point2D_F64;

/**
 * Bundler and Bundle Adjustment in the Large use a different coordinate system. This
 * converts it into what BoofCV understands by applying a negative sign to the Z coordinate.
 *
 * @author Peter Abeles
 */
public class SnavelyPinhole extends BundleAdjustmentPinholeSimplified {
    @Override
    public void project(double camX, double camY, double camZ, Point2D_F64 output) {
        super.project(camX, camY, -camZ, output);
    }

    @Override
    public void jacobian(double X, double Y, double Z,
                         double[] inputX, double[] inputY, boolean computeIntrinsic, double[] calibX, double[] calibY) {

        Z = -Z;

        double normX = X/Z;
        double normY = Y/Z;

        double n2 = normX*normX + normY*normY;

        double n2_X = 2*normX/Z;
        double n2_Y = 2*normY/Z;
        double n2_Z = -2*n2/Z;


        double r = 1.0 + (k1 + k2*n2)*n2;
        double kk = k1 + 2*k2*n2;

        double r_Z = n2_Z*kk;

        // partial X
        inputX[0] = (f/Z)*(r + 2*normX*normX*kk);
        inputY[0] = f*normY*n2_X*kk;

        // partial Y
        inputX[1] = f*normX*n2_Y*kk;
        inputY[1] = (f/Z)*(r + 2*normY*normY*kk);

        // partial Z
        inputX[2] = f*normX*(r/Z - r_Z); // you have no idea how many hours I lost before I realized the mistake here
        inputY[2] = f*normY*(r/Z - r_Z);

        if(!computeIntrinsic)
            return;

        // partial f
        calibX[0] = r*normX;
        calibY[0] = r*normY;

        // partial k1
        calibX[1] = f*normX*n2;
        calibY[1] = f*normY*n2;

        // partial k2
        calibX[2] = f*normX*n2*n2;
        calibY[2] = f*normY*n2*n2;
    }
}
