package animation3d.renderer3d;

import java.awt.Color;
import java.util.Arrays;

import animation3d.gui.CroppingPanel;
import animation3d.textanim.CombinedTransform;
import animation3d.textanim.IKeywordFactory;
import animation3d.textanim.IRenderer3D;
import animation3d.textanim.RenderingState;
import ij.CompositeImage;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ij.process.LUT;

public class Renderer3D extends OpenCLRaycaster implements IRenderer3D  {

	private final ExtendedRenderingState rs;

	private float near;
	private float far;

	private final IKeywordFactory kwFactory = new KeywordFactory();

	public Renderer3D(ImagePlus image, int wOut, int hOut) {
		super(image, wOut, hOut);

		LUT[] luts = image.isComposite() ?
				image.getLuts() : new LUT[] {image.getProcessor().getLut()};

		final int nC = image.getNChannels();

		float[] pdIn = new float[] {
				(float)image.getCalibration().pixelWidth,
				(float)image.getCalibration().pixelHeight,
				(float)image.getCalibration().pixelDepth
		};

		Calibration cal = image.getCalibration();
		float pwOut = (float)(image.getWidth()  * cal.pixelWidth  / wOut);
		float phOut = (float)(image.getHeight() * cal.pixelHeight / hOut);
		float pdOut = pdIn[2];
		float[] p = new float[] {pwOut, phOut, pdOut};

		near = (float)CroppingPanel.getNear(image);
		far  = (float)CroppingPanel.getFar(image);
		float[] rotcenter = new float[] {
				image.getWidth()   * pdIn[0] / 2,
				image.getHeight()  * pdIn[1] / 2,
				image.getNSlices() * pdIn[2] / 2};

		RenderingSettings[] renderingSettings = new RenderingSettings[nC];
		for(int c = 0; c < nC; c++) {
			renderingSettings[c] = new RenderingSettings(
					(float)luts[c].min, (float)luts[c].max, 1,
					(float)luts[c].min, (float)luts[c].max, 2,
					1,
					0, 0, 0,
					image.getWidth(), image.getHeight(), image.getNSlices(),
					near, far);
		}
		Color[] channelColors = calculateChannelColors();

		CombinedTransform transformation = new CombinedTransform(pdIn, p, rotcenter);

		this.rs = new ExtendedRenderingState(0,
				image.getT(),
				renderingSettings,
				channelColors,
				Color.BLACK,
				RenderingAlgorithm.INDEPENDENT_TRANSPARENCY,
				transformation);
	}

	public void resetRenderingSettings(ExtendedRenderingState rs) {
		LUT[] luts = image.isComposite() ?
				image.getLuts() : new LUT[] {image.getProcessor().getLut()};
		Color[] channelColors = calculateChannelColors();
		for(int c = 0; c < luts.length; c++) {
			rs.setChannelProperty(c, ExtendedRenderingState.INTENSITY_MIN,   luts[c].min);
			rs.setChannelProperty(c, ExtendedRenderingState.INTENSITY_MAX,   luts[c].max);
			rs.setChannelProperty(c, ExtendedRenderingState.INTENSITY_GAMMA, 1);
			rs.setChannelProperty(c, ExtendedRenderingState.ALPHA_MIN,   luts[c].min);
			rs.setChannelProperty(c, ExtendedRenderingState.ALPHA_MAX,   luts[c].max);
			rs.setChannelProperty(c, ExtendedRenderingState.ALPHA_GAMMA, 2);
			rs.setChannelProperty(c, ExtendedRenderingState.WEIGHT, 1);
			rs.setChannelProperty(c, ExtendedRenderingState.CHANNEL_COLOR_RED,   channelColors[c].getRed());
			rs.setChannelProperty(c, ExtendedRenderingState.CHANNEL_COLOR_GREEN, channelColors[c].getGreen());
			rs.setChannelProperty(c, ExtendedRenderingState.CHANNEL_COLOR_BLUE,  channelColors[c].getBlue());
			rs.setChannelProperty(c, ExtendedRenderingState.USE_LIGHT, 0);
			rs.setChannelProperty(c, ExtendedRenderingState.LIGHT_K_OBJECT,   1);
			rs.setChannelProperty(c, ExtendedRenderingState.LIGHT_K_DIFFUSE,  0);
			rs.setChannelProperty(c, ExtendedRenderingState.LIGHT_K_SPECULAR, 0);
			rs.setChannelProperty(c, ExtendedRenderingState.LIGHT_SHININESS,  0);
		}
		rs.setNonChannelProperty(ExtendedRenderingState.RENDERING_ALGORITHM, RenderingAlgorithm.INDEPENDENT_TRANSPARENCY.ordinal());
	}

	@Override
	public IKeywordFactory getKeywordFactory() {
		return kwFactory;
	}

	@Override
	public ExtendedRenderingState getRenderingState() {
		return rs;
	}

	@Override
	public ImageProcessor render(RenderingState kf2) {
		return render(kf2, false);
	}

	public ImageProcessor render(RenderingState kf2, boolean forceUpdateProgram) {
		ExtendedRenderingState kf = (ExtendedRenderingState)kf2;
		adjustProgram(kf, forceUpdateProgram);
		rs.setFrom(kf);
		return super.project(kf);
	}

	private void adjustProgram(ExtendedRenderingState next, boolean forceUpdateProgram) {
		int nChannels = getNChannels();
		boolean[] pUseLights = rs.useLights();
		RenderingAlgorithm pAlgorithm = rs.getRenderingAlgorithm();
		boolean[] nUseLights = next.useLights();
		RenderingAlgorithm nAlgorithm = next.getRenderingAlgorithm();

		if(!forceUpdateProgram && Arrays.equals(pUseLights, nUseLights) && pAlgorithm.equals(nAlgorithm))
			return;

		String program = null;
		switch(nAlgorithm) {
		case INDEPENDENT_TRANSPARENCY:
			program = OpenCLProgram.makeSource(nChannels, false, false, false, nUseLights);
			break;
		case COMBINED_TRANSPARENCY:
			program = OpenCLProgram.makeSource(nChannels, false, true, false, nUseLights);
			break;
		case MAXIMUM_INTENSITY:
			program = OpenCLProgram.makeSource(nChannels, false, false, true, nUseLights);
			break;
		}

		setProgram(program);
	}

	@Override
	public void setTargetSize(int w, int h) {
		super.setTgtSize(w, h);
		Calibration cal = image.getCalibration();
		float pwOut = (float)(image.getWidth()  * cal.pixelWidth  / w);
		float phOut = (float)(image.getHeight() * cal.pixelHeight / h);
		float pdOut = rs.getFwdTransform().getOutputSpacing()[2];
		float[] p = new float[] {pwOut, phOut, pdOut};

		rs.getFwdTransform().setOutputSpacing(p);
	}

	public int getNChannels() {
		return image.getNChannels();
	}

	private Color[] calculateChannelColors() {
		int nChannels = image.getNChannels();
		Color[] channelColors = new Color[nChannels];
		if(!image.isComposite()) {
			LUT lut = image.getProcessor().getLut();
			if(lut != null) {
				channelColors[0] = getLUTColor(lut);
			} else {
				channelColors[0] = Color.WHITE;
			}
			return channelColors;
		}
		for(int c = 0; c < image.getNChannels(); c++) {
			image.setC(c + 1);
			channelColors[c] = getLUTColor(((CompositeImage)image).getChannelLut());
		}
		return channelColors;
	}

	private Color getLUTColor(LUT lut) {
		int index = lut.getMapSize() - 1;
		int r = lut.getRed(index);
		int g = lut.getGreen(index);
		int b = lut.getBlue(index);
		return new Color(r, g, b);
	}
}
