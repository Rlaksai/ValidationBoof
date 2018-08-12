package boofcv.metrics.sba;

import boofcv.abst.geo.bundle.BundleAdjustmentObservations;
import boofcv.abst.geo.bundle.BundleAdjustmentObservations.View;
import boofcv.abst.geo.bundle.BundleAdjustmentSceneStructure;
import boofcv.struct.geo.PointIndex2D_F64;
import georegression.geometry.ConvertRotation3D_F64;
import georegression.struct.point.Point3D_F64;
import georegression.struct.se.Se3_F64;
import georegression.struct.so.Rodrigues_F64;

import java.io.*;

/**
 * Reading and writing data in the Bundle Adjustment in the Large format.
 *
 * @author Peter Abeles
 */
public class CodecBundleAdjustmentInTheLarge {
    public BundleAdjustmentSceneStructure scene;
    public BundleAdjustmentObservations observations;

    public void parse( File file ) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(file));

        String words[] = reader.readLine().split("\\s+");

        if( words.length != 3 )
            throw new IOException("Unexpected number of words on first line");

        int numCameras = Integer.parseInt(words[0]);
        int numPoints = Integer.parseInt(words[1]);
        int numObservations = Integer.parseInt(words[2]);

        scene = new BundleAdjustmentSceneStructure(false);
        scene.initialize(numCameras,numCameras,numPoints);

        observations = new BundleAdjustmentObservations(numCameras);

        for (int i = 0; i < numObservations; i++) {
            words = reader.readLine().split("\\s+");
            if (words.length != 4)
                throw new IOException("Unexpected number of words in obs");
            int cameraID = Integer.parseInt(words[0]);
            int pointID = Integer.parseInt(words[1]);
            float pixelX = Float.parseFloat(words[2]);
            float pixelY = Float.parseFloat(words[3]);

            if( pointID >= numPoints ) {
                throw new RuntimeException("Out of bounds pointID");
            }
            if( cameraID >= numCameras ) {
                throw new RuntimeException("Out of bounds cameraID");
            }

            observations.getView(cameraID).add(pointID,pixelX,pixelY);
        }

        Se3_F64 worldToCameraGL = new Se3_F64();
        Rodrigues_F64 rod = new Rodrigues_F64();
        for (int i = 0; i < numCameras; i++) {
            rod.unitAxisRotation.x = Double.parseDouble(reader.readLine());
            rod.unitAxisRotation.y = Double.parseDouble(reader.readLine());
            rod.unitAxisRotation.z = Double.parseDouble(reader.readLine());

            rod.theta = rod.unitAxisRotation.norm();
            if( rod.theta != 0 )
                rod.unitAxisRotation.divide(rod.theta);

            worldToCameraGL.T.x = Double.parseDouble(reader.readLine());
            worldToCameraGL.T.y = Double.parseDouble(reader.readLine());
            worldToCameraGL.T.z = Double.parseDouble(reader.readLine());

            ConvertRotation3D_F64.rodriguesToMatrix(rod,worldToCameraGL.R);

            SnavelyPinhole camera = new SnavelyPinhole();

            camera.f = Double.parseDouble(reader.readLine());
            camera.k1 = Double.parseDouble(reader.readLine());
            camera.k2 = Double.parseDouble(reader.readLine());

            scene.setCamera(i,false,camera);
            scene.setView(i,false,worldToCameraGL);
            scene.connectViewToCamera(i,i);
        }

        Point3D_F64 P = new Point3D_F64();
        for (int i = 0; i < numPoints; i++) {
            P.x = Float.parseFloat(reader.readLine());
            P.y = Float.parseFloat(reader.readLine());
            P.z = Float.parseFloat(reader.readLine());

//            GeometryMath_F64.mult(glToCv.R,P,P);

            scene.setPoint(i,P.x,P.y,P.z);
        }

        for (int i = 0; i < observations.views.length; i++) {
            View v = observations.getView(i);

            for (int j = 0; j < v.point.size; j++) {
                scene.connectPointToView(v.getPointId(j),i);
            }
        }
        reader.close();

        observations.checkOneObservationPerView();
    }

    public void save( File file ) throws IOException {
        PrintStream writer = new PrintStream(file);

        writer.println(scene.views.length+" "+scene.points.length+" "+observations.getObservationCount());

        PointIndex2D_F64 o = new PointIndex2D_F64();
        for (int viewIdx = 0; viewIdx < observations.views.length; viewIdx++) {
            BundleAdjustmentObservations.View view = observations.views[viewIdx];

            for (int obsIdx = 0; obsIdx < view.size(); obsIdx++) {
                view.get(obsIdx,o);
                writer.printf("%d %d %.8f %.8f\n",viewIdx,o.index,o.x,o.y);
            }
        }

        Rodrigues_F64 axisAngle = new Rodrigues_F64();
        for (int viewIdx = 0; viewIdx < scene.views.length; viewIdx++) {
            BundleAdjustmentSceneStructure.View view = scene.views[viewIdx];
            SnavelyPinhole camera = scene.cameras[view.camera].getModel();

            ConvertRotation3D_F64.matrixToRodrigues(view.worldToView.R,axisAngle);

            double axisX = axisAngle.unitAxisRotation.x*axisAngle.theta;
            double axisY = axisAngle.unitAxisRotation.y*axisAngle.theta;
            double axisZ = axisAngle.unitAxisRotation.z*axisAngle.theta;

            writer.printf("%.10f\n%.10f\n%.10f\n",axisX,axisY,axisZ);
            writer.printf("%.10f\n%.10f\n%.10f\n",view.worldToView.T.x,view.worldToView.T.y,view.worldToView.T.z);
            writer.printf("%.10f\n%.10f\n%.10f\n",camera.f,camera.k1,camera.k2);
        }

        for (int pointId = 0; pointId < scene.points.length; pointId++) {
            BundleAdjustmentSceneStructure.Point p = scene.points[pointId];
            writer.printf("%.10f\n%.10f\n%.10f\n",p.coordinate[0],p.coordinate[1],p.coordinate[2]);
        }
        writer.close();
    }

    public static void main(String[] args) throws IOException {
        CodecBundleAdjustmentInTheLarge alg = new CodecBundleAdjustmentInTheLarge();

        alg.parse(new File("data/bundle_adjustment/ladybug/problem-49-7776-pre.txt"));

        System.out.println("Done!");
    }
}
