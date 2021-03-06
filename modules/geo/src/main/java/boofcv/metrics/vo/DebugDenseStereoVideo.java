package boofcv.metrics.vo;

import boofcv.abst.feature.disparity.StereoDisparity;
import boofcv.alg.distort.ImageDistort;
import boofcv.alg.geo.PerspectiveOps;
import boofcv.alg.geo.RectifyImageOps;
import boofcv.alg.geo.rectify.RectifyCalibrated;
import boofcv.alg.misc.GImageMiscOps;
import boofcv.core.image.GeneralizedImageOps;
import boofcv.core.image.border.BorderType;
import boofcv.factory.feature.disparity.DisparityAlgorithms;
import boofcv.factory.feature.disparity.FactoryStereoDisparity;
import boofcv.gui.image.ImagePanel;
import boofcv.gui.image.ShowImages;
import boofcv.gui.image.VisualizeImageData;
import boofcv.gui.stereo.RectifiedPairPanel;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.calib.StereoParameters;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.ImageGray;
import georegression.struct.se.Se3_F64;
import org.ejml.data.DMatrixRMaj;
import org.ejml.data.FMatrixRMaj;
import org.ejml.ops.ConvertMatrixData;

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;

/**
 * @author Peter Abeles
 */
@SuppressWarnings("unchecked")
public class DebugDenseStereoVideo<T extends ImageGray<T>> implements MouseListener {

	SequenceStereoImages data;
	StereoDisparity<T,GrayF32> alg;

	ImagePanel imageLeft;
	ImagePanel imageRight;

	T inputLeft;
	T inputRight;

	T rectifiedLeft;
	T rectifiedRight;

	boolean paused;

	Class<T> imageType;

	ImagePanel disparityView;
	RectifiedPairPanel rectifiedView;

	public DebugDenseStereoVideo(StereoDisparity<T, GrayF32> alg,
								 SequenceStereoImages data) {
		this.alg = alg;
		this.data = data;
		imageType = alg.getInputType();

		inputLeft = GeneralizedImageOps.createSingleBand(imageType, 1, 1);
		inputRight = GeneralizedImageOps.createSingleBand(imageType,1,1);

		rectifiedLeft = GeneralizedImageOps.createSingleBand(imageType,1,1);
		rectifiedRight = GeneralizedImageOps.createSingleBand(imageType,1,1);
	}

	public void processSequence() {

		if( !data.next() )
			throw new RuntimeException("Failed to read first frame");

		imageLeft = new ImagePanel(data.getLeft());
		imageRight = new ImagePanel(data.getRight());

		imageLeft.addMouseListener(this);
		imageRight.addMouseListener(this);

		inputLeft.reshape(data.getLeft().getWidth(),data.getLeft().getHeight());
		inputRight.reshape(data.getRight().getWidth(),data.getRight().getHeight());
		rectifiedLeft.reshape(data.getLeft().getWidth(), data.getLeft().getHeight());
		rectifiedRight.reshape(data.getRight().getWidth(), data.getRight().getHeight());

		ShowImages.showWindow(imageLeft, "Left");
		ShowImages.showWindow(imageRight,"Right");

		processFrame();

		while( data.next() ) {
			imageLeft.setImage(data.getLeft());
			imageRight.setImage(data.getRight());

			processFrame();

			imageLeft.repaint();
			imageRight.repaint();

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
	}

	private void processFrame() {
		ConvertBufferedImage.convertFrom(data.getLeft(), inputLeft,true);
		ConvertBufferedImage.convertFrom(data.getRight(), inputRight,true);

		StereoParameters param = data.getCalibration();

		// Compute rectification
		RectifyCalibrated rectifyAlg = RectifyImageOps.createCalibrated();
		Se3_F64 leftToRight = param.getRightToLeft().invert(null);

		// original camera calibration matrices
		DMatrixRMaj K1 = PerspectiveOps.pinholeToMatrix(param.getLeft(), (DMatrixRMaj)null);
		DMatrixRMaj K2 = PerspectiveOps.pinholeToMatrix(param.getRight(), (DMatrixRMaj)null);

		rectifyAlg.process(K1,new Se3_F64(),K2,leftToRight);

		// rectification matrix for each image
		DMatrixRMaj rect1 = rectifyAlg.getRect1();
		DMatrixRMaj rect2 = rectifyAlg.getRect2();

		FMatrixRMaj rect1_F32 = new FMatrixRMaj(rect1.numRows,rect1.numCols);
		FMatrixRMaj rect2_F32 = new FMatrixRMaj(rect2.numRows,rect2.numCols);

		ConvertMatrixData.convert(rect1,rect1_F32);
		ConvertMatrixData.convert(rect2,rect2_F32);
		// New calibration matrix,
		DMatrixRMaj rectK = rectifyAlg.getCalibrationMatrix();

		// Adjust the rectification to make the view area more useful
		RectifyImageOps.fullViewLeft(param.left, rect1, rect2, rectK);

		// undistorted and rectify images
		ImageDistort<T,T> imageDistortLeft =
				RectifyImageOps.rectifyImage(param.getLeft(), rect1_F32, BorderType.ZERO, inputLeft.getImageType());
		ImageDistort<T,T> imageDistortRight =
				RectifyImageOps.rectifyImage(param.getRight(), rect2_F32, BorderType.ZERO, inputRight.getImageType());

		GImageMiscOps.fill(rectifiedLeft, 0);
		GImageMiscOps.fill(rectifiedRight,0);

		imageDistortLeft.apply(inputLeft, rectifiedLeft);
		imageDistortRight.apply(inputRight, rectifiedRight);

		alg.process(rectifiedLeft,rectifiedRight);


		GrayF32 disparity = alg.getDisparity();

		int min = alg.getMinDisparity();
		int max = alg.getMaxDisparity();

		BufferedImage visualized = VisualizeImageData.disparity(disparity, null, min,max, 0);

		BufferedImage visualizedRectL = ConvertBufferedImage.convertTo(inputLeft,null,true);
		BufferedImage visualizedRectR = ConvertBufferedImage.convertTo(inputRight,null,true);


		if( disparityView == null ) {
			disparityView = ShowImages.showWindow(visualized,"Disparity");
			rectifiedView = new RectifiedPairPanel(true,visualizedRectL,visualizedRectR);
			ShowImages.showWindow(rectifiedView,"Rectified");
		} else {
			disparityView.setImageRepaint(visualized);
			rectifiedView.setImages(visualizedRectL,visualizedRectR);
			rectifiedView.repaint();
		}
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
//		SequenceStereoImages data = new WrapParseLeuven07(new ParseLeuven07("data/leuven07"));
		SequenceStereoImages data = new WrapParseKITTI("data/KITTI","00");

		Class imageType = GrayF32.class;

		StereoDisparity alg = FactoryStereoDisparity.regionSubpixelWta(DisparityAlgorithms.RECT,
				10, 120, 2, 2, 30, 0, 0.1, imageType);

		DebugDenseStereoVideo app = new DebugDenseStereoVideo(alg,data);
		app.processSequence();
	}
}
