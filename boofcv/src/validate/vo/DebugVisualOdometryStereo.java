package validate.vo;

import boofcv.abst.feature.associate.AssociateDescTo2D;
import boofcv.abst.feature.associate.AssociateDescription2D;
import boofcv.abst.feature.associate.ScoreAssociation;
import boofcv.abst.feature.describe.DescribeRegionPoint;
import boofcv.abst.feature.detdesc.DetectDescribeMulti;
import boofcv.abst.feature.detdesc.DetectDescribeMultiFusion;
import boofcv.abst.feature.detdesc.DetectDescribePoint;
import boofcv.abst.feature.detect.extract.ConfigExtract;
import boofcv.abst.feature.detect.extract.NonMaxSuppression;
import boofcv.abst.feature.detect.intensity.GeneralFeatureIntensity;
import boofcv.abst.feature.detect.interest.DetectorInterestPointMulti;
import boofcv.abst.feature.detect.interest.GeneralToInterestMulti;
import boofcv.abst.feature.detect.interest.InterestPointDetector;
import boofcv.abst.feature.disparity.StereoDisparitySparse;
import boofcv.abst.feature.tracker.PkltConfig;
import boofcv.abst.feature.tracker.PointTracker;
import boofcv.abst.feature.tracker.PointTrackerAux;
import boofcv.abst.sfm.AccessPointTracks3D;
import boofcv.abst.sfm.ModelAssistedTrackerCalibrated;
import boofcv.abst.sfm.StereoVisualOdometry;
import boofcv.alg.feature.associate.AssociateMaxDistanceNaive;
import boofcv.alg.feature.detect.interest.GeneralFeatureDetector;
import boofcv.alg.filter.derivative.GImageDerivativeOps;
import boofcv.alg.geo.PerspectiveOps;
import boofcv.core.image.ConvertBufferedImage;
import boofcv.core.image.GeneralizedImageOps;
import boofcv.factory.feature.associate.FactoryAssociation;
import boofcv.factory.feature.describe.FactoryDescribeRegionPoint;
import boofcv.factory.feature.detdesc.FactoryDetectDescribe;
import boofcv.factory.feature.detect.extract.FactoryFeatureExtractor;
import boofcv.factory.feature.detect.intensity.FactoryIntensityPoint;
import boofcv.factory.feature.detect.interest.FactoryInterestPoint;
import boofcv.factory.feature.disparity.FactoryStereoDisparity;
import boofcv.factory.feature.tracker.FactoryPointSequentialTracker;
import boofcv.factory.sfm.FactoryVisualOdometry;
import boofcv.gui.d3.Polygon3DSequenceViewer;
import boofcv.gui.feature.VisualizeFeatures;
import boofcv.gui.image.ImageGridPanel;
import boofcv.gui.image.ShowImages;
import boofcv.struct.calib.StereoParameters;
import boofcv.struct.image.ImageFloat32;
import boofcv.struct.image.ImageSingleBand;
import georegression.geometry.RotationMatrixGenerator;
import georegression.metric.UtilAngle;
import georegression.struct.point.Point2D_F64;
import georegression.struct.point.Point3D_F64;
import georegression.struct.se.Se3_F64;
import georegression.transform.se.SePointOps_F64;
import org.ejml.data.DenseMatrix64F;
import org.ejml.ops.CommonOps;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

/**
 * @author Peter Abeles
 */
// TODO Remove rotational component from translational error vector by applying inverse to total rotation estimate
public class DebugVisualOdometryStereo<T extends ImageSingleBand> implements MouseListener {

	SequenceStereoImages data;
	StereoVisualOdometry<T> alg;

	ImageGridPanel imageDisplay;
	Polygon3DSequenceViewer viewer = null;//new Polygon3DSequenceViewer();

	T inputLeft;
	T inputRight;

	boolean paused;
	int numFaults = 0;
	int numSkipUpdate = 0;

	Se3_F64 previousWorldToLeftFound = new Se3_F64();
	Se3_F64 previousWorldToLeft = new Se3_F64();
	Se3_F64 initialWorldToLeft = new Se3_F64();

	Se3_F64 prevSkipEstimated = new Se3_F64();
	Se3_F64 prevSkipTruth = new Se3_F64();

	double totalErrorDistanceSkip;
	double totalErrorRotationSkip;
	double totalErrorDistance;
	double totalErrorRotation;
	double integralFoundDistance;
	double integralFoundRotation;
	double integralTrueDistance;
	double integralTrueRotation;
	double absoluteLocation;
	double absoluteRotation;

	int skipFrame = 20;

	int numEstimates;

	int frame;

	BufferedImage rgb;


	public DebugVisualOdometryStereo(SequenceStereoImages data,
									 StereoVisualOdometry<T> alg ,
									 Class<T> imageType )
	{
		this.data = data;
		this.alg = alg;

		inputLeft = GeneralizedImageOps.createSingleBand(imageType,1,1);
		inputRight = GeneralizedImageOps.createSingleBand(imageType,1,1);
	}

	public void initialize() {
		paused = false;
		alg.reset();

		totalErrorDistanceSkip = 0;
		totalErrorRotationSkip = 0;
		totalErrorDistance = 0;
		totalErrorRotation = 0;
		integralFoundDistance = 0;
		integralFoundRotation = 0;
		integralTrueDistance = 0;
		integralTrueRotation = 0;

		numEstimates = 0;

		numFaults = 0;
		numSkipUpdate = 0;

		frame = 0;

	}

	public void processSequence() {

		if( !data.next() )
			throw new RuntimeException("Failed to read first frame");

//		imageDisplay = new ImageGridPanel(2,1,data.getLeft(),data.getRight());
//		imageDisplay.addMouseListener(this);

		inputLeft.reshape(data.getLeft().getWidth(),data.getLeft().getHeight());
		inputRight.reshape(data.getRight().getWidth(),data.getRight().getHeight());

		boolean convertToRgb = data.getLeft().getType() == BufferedImage.TYPE_BYTE_GRAY;

		if( convertToRgb )
			rgb = new BufferedImage(inputLeft.width,inputLeft.height,BufferedImage.TYPE_INT_BGR);

		if( imageDisplay != null )
			ShowImages.showWindow(imageDisplay,"Input");

		previousWorldToLeftFound.reset();
		data.getLeftToWorld().invert(previousWorldToLeft);
		initialWorldToLeft.set(previousWorldToLeft);

		prevSkipEstimated.reset();
		prevSkipTruth.set(data.getLeftToWorld());

		if( data.isCalibrationFixed() )
			alg.setCalibration(data.getCalibration());

		if( viewer != null ) {
			DenseMatrix64F K = PerspectiveOps.calibrationMatrix(data.getCalibration().left,null);
			viewer.setK(K);
			viewer.setStepSize(data.getCalibration().getBaseline());
			viewer.setPreferredSize(new Dimension(inputLeft.width, inputLeft.height));
			viewer.setMaximumSize(viewer.getPreferredSize());

			ShowImages.showWindow(viewer,"3D");
		}

		processFrame();

		while( data.next() ) {
			if( imageDisplay != null ) {
				if( convertToRgb )
					rgb.getGraphics().drawImage(data.getLeft(),0,0,null);
				else
					rgb = data.getLeft();
				imageDisplay.setImages(rgb, data.getRight());
			}

			processFrame();

			if( imageDisplay != null ) {

				if( alg instanceof AccessPointTracks3D) {
					drawFeatures((AccessPointTracks3D)alg,rgb);
				}

				imageDisplay.repaint();
			}

			while( paused ) {
				synchronized ( this ) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					}
				}
			}
		}

		// todo add absolute location and absolute rotation?
		double integralDistance = Math.abs(integralFoundDistance - integralTrueDistance)/ integralTrueDistance;
		double integralRotation = Math.abs(integralFoundRotation - integralTrueRotation)/ integralTrueDistance;
		double averagePerEstDistance = totalErrorDistance/numEstimates;
		double averagePerEstRotation = totalErrorRotation/numEstimates;
		double averagePerDistDistance = totalErrorDistance/integralTrueDistance;
		double averagePerDistRotation = totalErrorRotation/integralTrueDistance;
		double averageSkipDistance = totalErrorDistanceSkip/integralTrueDistance;
		double averageSkipRotation = totalErrorRotationSkip/integralTrueDistance;


		System.out.printf("Ave per estimate:      distance %9.7f  rotation %11.9f\n",averagePerEstDistance,averagePerEstRotation);
		System.out.printf("Ave per distance:      distance %9.5f%% deg/unit = %5.2e\n",100*averagePerDistDistance,UtilAngle.radianToDegree(averagePerDistRotation));
		System.out.printf("Ave Skip %2d:           distance %9.5f%% deg/unit = %5.2e\n",skipFrame,100*averageSkipDistance,UtilAngle.radianToDegree(averageSkipRotation));
		System.out.printf("Absolute:              location %9.5f  rotation %6.2f degrees\n",absoluteLocation,
				UtilAngle.radianToDegree(absoluteRotation));
		System.out.printf("Integral per distance: distance %9.5f%% deg/unit = %5.2e\n",100*integralDistance,UtilAngle.radianToDegree(integralRotation));

	}

	private void updateView3D( Se3_F64 leftToWorld , Color color ) {
		StereoParameters config = data.getCalibration();

		double r = config.getBaseline();

		Point3D_F64 p1 = new Point3D_F64(-r,-r,0);
		Point3D_F64 p2 = new Point3D_F64(r,-r,0);
		Point3D_F64 p3 = new Point3D_F64(r,r,0);
		Point3D_F64 p4 = new Point3D_F64(-r,r,0);

		SePointOps_F64.transform(leftToWorld, p1, p1);
		SePointOps_F64.transform(leftToWorld,p2,p2);
		SePointOps_F64.transform(leftToWorld,p3,p3);
		SePointOps_F64.transform(leftToWorld,p4,p4);

		viewer.add(color,p1,p2,p3,p4);
		viewer.repaint();
	}

	private void processFrame() {
//		if( frame < 375 ) {
//			frame++;
//			return;
//		}

		ConvertBufferedImage.convertFrom(data.getLeft(), inputLeft);
		ConvertBufferedImage.convertFrom(data.getRight(), inputRight);

		long before = System.nanoTime();
		if( !data.isCalibrationFixed() )
			alg.setCalibration(data.getCalibration());
		boolean updated = alg.process(inputLeft,inputRight);
		long after = System.nanoTime();

		double fps = 1.0/((after-before)*1e-9);

		if( !updated ) {
			numSkipUpdate++;
			if( alg.isFault() ) {
				numFaults++;
			}
			System.out.printf("%d %6.2f NO UPDATE fault = %s\n",frame,fps,alg.isFault());
		} else {
			Se3_F64 found = alg.getLeftToWorld().concat(previousWorldToLeftFound,null);
			Se3_F64 expected = data.getLeftToWorld().concat(previousWorldToLeft,null);

			Se3_F64 diff = expected.concat(found.invert(null), null);

			double distanceError = diff.getT().norm();
			double distanceTruth = expected.getT().norm();

			double errorFrac = distanceError / distanceTruth;
			double errorAngle = rotationMatrixToRadian(diff.getR());

			System.out.printf("%5d %6.2f location error %f error frac %f  angle %6.3f\n", frame,fps,distanceError, errorFrac,errorAngle);
//			System.out.println("  expected "+expected.getT());
//			System.out.println("  found "+found.getT());

			if( (frame%skipFrame) == 0 ) {
				Se3_F64 deltaEstimated = alg.getLeftToWorld().concat(prevSkipEstimated.invert(null),null);
				Se3_F64 deltaTruth = data.getLeftToWorld().concat(prevSkipTruth.invert(null),null);

				Se3_F64 error = deltaEstimated.invert(null).concat(deltaTruth,null);

				totalErrorDistanceSkip += error.getT().norm();
				totalErrorRotationSkip += rotationMatrixToRadian(error.getR());

				prevSkipEstimated.set(alg.getLeftToWorld());
				prevSkipTruth.set(data.getLeftToWorld());
			}

			alg.getLeftToWorld().invert(previousWorldToLeftFound);
			data.getLeftToWorld().invert(previousWorldToLeft);

			numEstimates++;
			totalErrorDistance += distanceError;
			totalErrorRotation += errorAngle;

			integralFoundDistance += found.getT().norm();
			integralFoundRotation += rotationMatrixToRadian(found.getR());
			integralTrueDistance += distanceTruth;
			integralTrueRotation += rotationMatrixToRadian(expected.getR());

			// find difference in absolute location
			Se3_F64 leftToWorld = data.getLeftToWorld().concat(initialWorldToLeft,null);

			diff = leftToWorld.concat(alg.getLeftToWorld().invert(null),null);
			absoluteLocation = diff.getT().norm();
			absoluteRotation = rotationMatrixToRadian(diff.getR());

			if( viewer != null ) {
				updateView3D(alg.getLeftToWorld(),Color.BLACK);
				updateView3D(leftToWorld,Color.ORANGE);
			}

		}

		frame++;
	}

	private double computeAngularError( Se3_F64 found , Se3_F64 expected ) {
		DenseMatrix64F A = new DenseMatrix64F(3,3);

		CommonOps.multTransA(found.getR(), expected.getR(),A);

		return rotationMatrixToRadian(A);
	}

	private double rotationMatrixToRadian(DenseMatrix64F a) {
		double angles[] = RotationMatrixGenerator.matrixToEulerXYZ(a);

		double sum = angles[0]*angles[0] + angles[1]*angles[1] + angles[1]*angles[1];

		return Math.sqrt(sum);
	}

	private static void drawFeatures( AccessPointTracks3D tracker , BufferedImage image )  {

		Graphics2D g2 = image.createGraphics();

		List<Point2D_F64> points = tracker.getAllTracks();
		if( points.isEmpty() )
			return;


		int numInliers = 0;
		double ranges[] = new double[points.size() ];

		for( int i = 0; i < points.size(); i++ ) {
			ranges[i] = tracker.getTrackLocation(i).z;
		}
		Arrays.sort(ranges);
		double maxRange = ranges[(int)(ranges.length*0.8)];

		for( int i = 0; i < points.size(); i++ ) {
			Point2D_F64 pixel = points.get(i);

			Point3D_F64 p3 = tracker.getTrackLocation(i);

			double r = p3.z/maxRange;
			if( r < 0 ) r = 0;
			else if( r > 1 ) r = 1;

			int color = (255 << 16) | ((int)(255*r) << 8);


			VisualizeFeatures.drawPoint(g2,(int)pixel.x,(int)pixel.y,3,new Color(color));
		}

		for( int i = 0; i < points.size(); i++ ) {
			Point2D_F64 pixel = points.get(i);

			if( tracker.isNew(i) ) {
//				VisualizeFeatures.drawPoint(g2,(int)pixel.x,(int)pixel.y,3,Color.GREEN);
				continue;
			}

			if( tracker.isInlier(i) ) {
				numInliers++;
				VisualizeFeatures.drawPoint(g2,(int)pixel.x,(int)pixel.y,7,Color.WHITE,false);
				VisualizeFeatures.drawPoint(g2,(int)pixel.x,(int)pixel.y,5,Color.BLACK,false);
			}
		}


		g2.setColor(Color.BLACK);
		g2.fillRect(25,15,80,45);
		g2.setColor(Color.CYAN);
		g2.drawString("Total: " + tracker.getAllTracks().size(), 30, 30);
		g2.drawString("Inliers: "+numInliers,30,50);
	}


	@Override
	public void mouseClicked(MouseEvent e) {
		paused = !paused;
	}

	@Override
	public void mousePressed(MouseEvent e) {}

	@Override
	public void mouseReleased(MouseEvent e) {}

	@Override
	public void mouseEntered(MouseEvent e) {}

	@Override
	public void mouseExited(MouseEvent e) {}

	public static void main( String args[] ) {
		// TODO light invariant sparse stereo
		// TODO or preprocess images with edge detector?

//		SequenceStereoImages data = new WrapParseLeuven07(new ParseLeuven07("../data/leuven07"));
		SequenceStereoImages data = new WrapParseKITTI("../data/KITTI","00");

		Class imageType = ImageFloat32.class;
		Class derivType = GImageDerivativeOps.getDerivativeType(imageType);

		StereoDisparitySparse<ImageFloat32> disparity =
				FactoryStereoDisparity.regionSparseWta(10, 120, 2, 2, 30, 0.1, true, imageType);
		ModelAssistedTrackerCalibrated assistedTracker;

		int selection = 0;

		StereoVisualOdometry alg;

		if( selection == 0 ) {
//		ImagePointTracker<ImageFloat32> tracker =
//				FactoryPointSequentialTracker.dda_FAST_BRIEF(-1,200,5,9,10,imageType);
//		ImagePointTracker<ImageFloat32> tracker =
//				FactoryPointSequentialTracker.dda_ST_BRIEF(-1,200,5,500,imageType,null);
//		ImagePointTracker<ImageFloat32> tracker =
//				FactoryPointSequentialTracker.dda_FH_SURF(500,2,200,1,true,imageType);
			PointTracker<ImageFloat32> tracker =
					FactoryPointSequentialTracker.klt(-1,new int[]{1,2, 4, 8}, new ConfigExtract(5,300),3, imageType, ImageFloat32.class);
//		ImagePointTracker<ImageFloat32> tracker =
//				FactoryPointSequentialTracker.combined_FH_SURF_KLT(500, 200,3,1,3,new int[]{1, 2, 4, 8}, 100, false,imageType);
//		ImagePointTracker<ImageFloat32> tracker =
//				FactoryPointSequentialTracker.combined_ST_SURF_KLT(-1,10,10,3,new int[]{1, 2, 4, 8}, 80, null,null,imageType,null);

			assistedTracker = FactoryVisualOdometry.trackerP3P(tracker,1.5,200,100);
			alg = FactoryVisualOdometry.stereoDepth(200, 2,disparity, assistedTracker, imageType);
		} else if( selection == 1 ) {
//			DescribePointBrief brief = FactoryDescribePointAlgs.brief(FactoryBriefDefinition.gaussian2(new Random(123), 16, 512),
//					FactoryBlurFilter.gaussian(imageType, 0, 4));
//			DescribeRegionPoint descriptor = new WrapDescribeBrief(brief);
			DescribeRegionPoint descriptor = FactoryDescribeRegionPoint.pixelNCC(11,11,ImageFloat32.class);
//			DescribeRegionPoint descriptor = FactoryDescribeRegionPoint.surfStable(null,ImageFloat32.class);

			GeneralFeatureDetector corner = FactoryPointSequentialTracker.createShiTomasi( new ConfigExtract(8,200),-1, derivType);
//			GeneralFeatureDetector corner = FactoryDetectPoint.createFast(3,9,10,-1,ImageFloat32.class);

			InterestPointDetector detector = FactoryInterestPoint.wrapPoint(corner, 0.5, imageType, derivType);

			DetectDescribePoint detDesc = FactoryDetectDescribe.fuseTogether(detector, null, descriptor);


			assistedTracker = FactoryVisualOdometry.trackerAssistedDdaP3P(detDesc,1.5,5000,400,Double.MAX_VALUE,200);
			alg = FactoryVisualOdometry.stereoDepth(200, 2,disparity, assistedTracker, imageType);
		} else if( selection == 2 ) {
			PkltConfig config =
					PkltConfig.createDefault(imageType, derivType);
			config.pyramidScaling = new int[]{1,2,4,8};
			config.featureRadius = 3;
			config.typeInput = imageType;
			config.typeDeriv = derivType;

			GeneralFeatureDetector detector =
					FactoryPointSequentialTracker.createShiTomasi(new ConfigExtract(5,300),-1, config.typeDeriv);

			assistedTracker = FactoryVisualOdometry.trackerAssistedKltP3P(detector,config,1.5,200,100);
			alg = FactoryVisualOdometry.stereoDepth(200, 2,disparity, assistedTracker, imageType);
		} else if( selection == 3 ) {

			PointTrackerAux trackerLeft = FactoryPointSequentialTracker.
					klt(-1, new int[]{1, 2, 4, 8}, new ConfigExtract(5,300), 3,imageType, ImageFloat32.class);
			PointTrackerAux trackerRight = FactoryPointSequentialTracker.
					klt(-1, new int[]{1, 2, 4, 8}, new ConfigExtract(5,300), 3, imageType, ImageFloat32.class);

			alg = FactoryVisualOdometry.stereoFullPnP(150, 2,1.5,200,100,disparity,
					trackerLeft,trackerRight, imageType);
		} else if( selection == 4 ) {
			double associationMaxError = Double.MAX_VALUE;

//			DescribePointBrief<ImageFloat32> briefLeft = FactoryDescribePointAlgs.brief(
//					FactoryBriefDefinition.gaussian2(new Random(123), 16, 512),
//					FactoryBlurFilter.gaussian(imageType, 0, 4));
//			DescribePointBrief<ImageFloat32> briefRight = FactoryDescribePointAlgs.brief(
//					FactoryBriefDefinition.gaussian2(new Random(123), 16, 512),
//					FactoryBlurFilter.gaussian(imageType, 0, 4));

			DescribeRegionPoint descriptorLeft = FactoryDescribeRegionPoint.pixelNCC(11, 11, ImageFloat32.class);
			DescribeRegionPoint descriptorRight = FactoryDescribeRegionPoint.pixelNCC(11,11,ImageFloat32.class);

			GeneralFeatureDetector cornerLeft = FactoryPointSequentialTracker.createShiTomasi(
					new ConfigExtract(5,300),-1, derivType);
			GeneralFeatureDetector cornerRight = FactoryPointSequentialTracker.createShiTomasi(
					new ConfigExtract(5,300),-1, derivType);

			ScoreAssociation score = FactoryAssociation.defaultScore(descriptorLeft.getDescriptorType());
//			AssociateDescription2D associateLeft =
//					new AssociateDescTo2D(FactoryAssociation.greedy(score, associationMaxError, -1, true));
//			AssociateDescription2D associateRight =
					new AssociateDescTo2D(FactoryAssociation.greedy(score, associationMaxError, -1, true));
			AssociateDescription2D associateLeft = new AssociateMaxDistanceNaive(score,true,associationMaxError,150);
			AssociateDescription2D associateRight = new AssociateMaxDistanceNaive(score,true,associationMaxError,150);


			PointTrackerAux trackerLeft = FactoryPointSequentialTracker.
					ddaUser(cornerLeft, descriptorLeft, associateLeft, 0.5, imageType);
			PointTrackerAux trackerRight = FactoryPointSequentialTracker.
					ddaUser(cornerRight,descriptorRight, associateRight, 0.5, imageType);

			alg = FactoryVisualOdometry.stereoFullPnP(200, 2,1.5,200,50,disparity,
					trackerLeft,trackerRight, imageType);
		} else {
//			GeneralFeatureIntensity intensity =
//					FactoryIntensityPoint.hessian(HessianBlobIntensity.Type.DETERMINANT, imageType);
			GeneralFeatureIntensity intensity =
					FactoryIntensityPoint.shiTomasi(2,false,imageType);
			NonMaxSuppression nonmax = FactoryFeatureExtractor.nonmax(new ConfigExtract(4, 400, 0, true, false, true));
			GeneralFeatureDetector general = new GeneralFeatureDetector(intensity,nonmax);
			DetectorInterestPointMulti detector = new GeneralToInterestMulti(general,1,imageType,derivType);
//			DescribeRegionPoint describe = FactoryDescribeRegionPoint.brief(16,512,-1,4,true,imageType);
//			DescribeRegionPoint describe = FactoryDescribeRegionPoint.pixelNCC(11,11,imageType);
			DescribeRegionPoint describe = FactoryDescribeRegionPoint.surfFast(null,imageType);
			DetectDescribeMulti detDescMulti =  new DetectDescribeMultiFusion(detector,null,describe);

			alg = FactoryVisualOdometry.stereoQuadPnP(1.5, 0.1 , Double.MAX_VALUE, 5000, 50, detDescMulti, imageType);
		}

		DebugVisualOdometryStereo app = new DebugVisualOdometryStereo(data,alg,imageType);
		app.initialize();

		app.processSequence();

		System.out.println("DONE!!!");
		System.exit(0);
	}
}
