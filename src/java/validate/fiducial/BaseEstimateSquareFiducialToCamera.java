package validate.fiducial;

import boofcv.abst.fiducial.FiducialDetector;
import boofcv.core.image.ConvertBufferedImage;
import boofcv.io.UtilIO;
import boofcv.io.image.UtilImageIO;
import boofcv.misc.BoofMiscOps;
import boofcv.struct.calib.IntrinsicParameters;
import boofcv.struct.image.ImageBase;
import georegression.struct.point.Vector3D_F64;
import georegression.struct.se.Se3_F64;
import org.ejml.data.DenseMatrix64F;
import validate.DataSetDoesNotExist;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

/**
 * Estimates the location of fiducials in the input images.  Results are saved to the specified output directory.
 * The detector should be configured such that the fiducial is of size 1.  THe actual size will be read later on
 * and the translation adjusted.
 *
 * @author Peter Abeles
 */
public abstract class BaseEstimateSquareFiducialToCamera<T extends ImageBase> {

	File baseDirectory;
	File outputDirectory = new File(".");

	FiducialDetector<T> detector;

	public BaseEstimateSquareFiducialToCamera(FiducialDetector<T> detector) {
		this.detector = detector;
	}

	public void initialize( File baseDirectory ) {
		this.baseDirectory = baseDirectory;
	}

	public void setOutputDirectory(File outputDirectory) {
		this.outputDirectory = outputDirectory;
	}

	public abstract void configureDetector( File datasetDir );

	public void process( String dataset ) throws IOException {

		File dataSetDir = new File(baseDirectory,dataset);
		if( !dataSetDir.exists() ) {
			throw new DataSetDoesNotExist("The data set directory doesn't exist. "+dataSetDir.getPath());
		}

		configureDetector(dataSetDir);
		FiducialCommon.Scenario scenario = FiducialCommon.parseScenario(new File(dataSetDir, "expected.txt"));



		List<String> files = BoofMiscOps.directoryList(dataSetDir.getAbsolutePath(), "png");
		if( files.size() == 0 ) {
			files = BoofMiscOps.directoryList(dataSetDir.getAbsolutePath(), "jpg");
		}
		if( files.size() == 0 ) {
			throw new IllegalArgumentException("No images found.  paths correct?");
		}
		T image = detector.getInputType().createImage(1,1);

		IntrinsicParameters intrinsic = UtilIO.loadXML(new File(dataSetDir,"intrinsic.xml").getAbsolutePath());
		detector.setIntrinsic(intrinsic);
		for( String path : files ) {

			System.out.println("processing "+path);
			BufferedImage orig = UtilImageIO.loadImage(path);
			image.reshape(orig.getWidth(),orig.getHeight());
			ConvertBufferedImage.convertFrom(orig,image,true);

			detector.detect(image);

			File f = new File(path);
			String inputName = f.getName();
			File outFile = new File(outputDirectory,inputName.substring(0,inputName.length()-3)+"csv");
			PrintStream out = new PrintStream(outFile);
			out.println("# Detected fiducials inside of "+inputName);
			out.println("# 4 lines for each detection. line 1 = detected fiducial.  lines 2-4 = rigid body transform, row major");
			Se3_F64 fiducialToSensor = new Se3_F64();
			for (int i = 0; i < detector.totalFound(); i++) {
				int which = detector.getId(i);
				double fiducialWidth = scenario.getWidth(which);
				detector.getFiducialToWorld(i,fiducialToSensor);


				DenseMatrix64F R = fiducialToSensor.getR();
				Vector3D_F64 T = fiducialToSensor.getT();

				// adjust translation for actual fiducial size
				T.x *= fiducialWidth;
				T.y *= fiducialWidth;
				T.z *= fiducialWidth;

				out.println(which);
				out.printf("%.15f %.15f %.15f %.15f\n",R.get(0,0),R.get(0,1),R.get(0,2),T.x);
				out.printf("%.15f %.15f %.15f %.15f\n",R.get(1,0),R.get(1,1),R.get(1,2),T.y);
				out.printf("%.15f %.15f %.15f %.15f\n",R.get(2,0),R.get(2,1),R.get(2,2),T.z);
			}
			out.close();
		}
	}

	protected static File setupOutput() {
		File outputDirectory = new File("tmp");
		if( outputDirectory.exists() ) {
			for( File f : outputDirectory.listFiles() ) {
				if( !f.delete() ) {
					throw new RuntimeException("Couldn't delete a file in tmp. "+f.getName());
				}
			}
		} else {
			outputDirectory.mkdirs();
		}
		return outputDirectory;
	}

}