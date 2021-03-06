package animation3d.textanim;

import ij.ImagePlus;
import ij.process.ImageProcessor;

public interface IRenderer3D {

	public IKeywordFactory getKeywordFactory();

	public RenderingState getRenderingState();

	public ImageProcessor render(RenderingState kf);

	public ImagePlus getImage();

	public void setTargetSize(int w, int h);

	public int getTargetWidth();

	public int getTargetHeight();

}
