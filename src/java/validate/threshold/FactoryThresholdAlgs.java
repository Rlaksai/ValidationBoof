package validate.threshold;

import boofcv.alg.filter.binary.GThresholdImageOps;
import boofcv.alg.filter.binary.ThresholdImageOps;
import boofcv.alg.misc.ImageStatistics;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.GrayU8;

/**
 * @author Peter Abeles
 */
public class FactoryThresholdAlgs {

	public static ThresholdText globalMean() {
		return new ThresholdText() {
			@Override
			public void process(GrayF32 input, GrayU8 output) {
				float mean = (float)ImageStatistics.mean(input);
				ThresholdImageOps.threshold(input,output,mean,true);
			}
		};
	}

	public static ThresholdText globalOtsu() {
		return new ThresholdText() {
			@Override
			public void process(GrayF32 input, GrayU8 output) {
				int threshold = GThresholdImageOps.computeOtsu(input,0,255);
				ThresholdImageOps.threshold(input,output,threshold,true);
			}
		};
	}

	public static ThresholdText globalEntropy() {
		return new ThresholdText() {
			@Override
			public void process(GrayF32 input, GrayU8 output) {
				int threshold = GThresholdImageOps.computeEntropy(input, 0, 255);
				ThresholdImageOps.threshold(input,output,threshold,true);
			}
		};
	}

	public static ThresholdText localSquare() {
		return new ThresholdText() {
			@Override
			public void process(GrayF32 input, GrayU8 output) {
				GThresholdImageOps.localMean(input, output, 30, 1.0, true, null, null);
			}
		};
	}

	public static ThresholdText localGaussian() {
		return new ThresholdText() {
			@Override
			public void process(GrayF32 input, GrayU8 output) {
				GThresholdImageOps.localGaussian(input, output, 35, 1.0, true, null, null);
			}
		};
	}

	public static ThresholdText localSauvola() {
		return new ThresholdText() {
			@Override
			public void process(GrayF32 input, GrayU8 output) {
				GThresholdImageOps.localSauvola(input, output, 15, 0.30f, true);//15 0.30 0.8895
			}
		};
	}

	public static ThresholdText localBlockMinMax() {
		return new ThresholdText() {
			@Override
			public void process(GrayF32 input, GrayU8 output) {
				GThresholdImageOps.localBlockMinMax(input, output, 15, 1.0, true,15);
			}
		};
	}
}
