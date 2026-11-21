package comp3011;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import org.bytedeco.javacv.Frame;

/**
 * Burns speech-to-text subtitles (white, black outline, sans serif)
 * Can be switched on and off while the video is playing
 */

public class FrameSubtitler extends FrameProcessor {
	
	private record Overlay(int width, int height, int[] argb) {
		
	}
	
	private volatile boolean enabled;
	private ConcurrentSkipListMap<Long, SubtitleCue> cues = new ConcurrentSkipListMap<>();
	
	// Single-entry cache of the rendered cue (process() is syncronised per processor by the model).
	
	private SubtitleCue overlayCue;
	private Overlay overlay;
	
	public FrameSubtitler() {
		this(true);
	}
	
	public FrameSubtitler(boolean enabled) {
		this.enabled = enabled;
	}
	
	public boolean isEnabled() {
		return enabled;
	}
	
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}
	
	private SubtitleFetcher fetcher;
	
	@Override
	public void initialise(InfoVideo videoInfo) throws Exception {
		super.initialise(videoInfo);
		
		// Initialise runs again after a stop or restart, so shut down any previous fetcher.
		if (fetcher != null) {
			fetcher.stop();
		}
		
		cues = new ConcurrentSkipListMap<>();
		overlayCue = null;
		overlay = null;
		
		long durationUs = videoInfo.frameRate() > 0 ? (long) (videoInfo.totalVideoFrames() * 1_000_000.0 / videoInfo.frameRate()) : 0;
		
		fetcher = new SubtitleFetcher(videoInfo.mediaPath(), durationUs, cues, () -> enabled);
		
		Thread thread = new Thread(fetcher, "subtitle-fetcher");
		thread.setDaemon(true); // don't keep the JVM alive after the window closes
		thread.start();
	}
	
	@Override
    public void process(Frame frame, InfoFrame info) throws Exception {
		if (!enabled) {
			return;
		}
		
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }

        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception("FrameSubtitler requires byte-backed image frames");
        }

        if (videoInfo.imageChannels() < 3) {
            throw new Exception("FrameVignetter requires at least three image channels");
        }
        
        fetcher.noteTimestamp(info.timestampUs());
        
        //Latest cue that started at or before this frame, if it hasn't ended yet.
        Map.Entry<Long, SubtitleCue> entry = cues.floorEntry(info.timestampUs());
        
        if(entry == null || info.timestampUs() >= entry.getValue().endUs()) {
        	return;
        }
        
        SubtitleCue cue = entry.getValue();
        if(cue != overlayCue) {
        	overlay = render(cue.text());
        	overlayCue = cue;
        }

        blend(pixels, overlay);
    }
	
	// Draws the text once per cue into a transparent image, so each frame only has to blend it in.
	private Overlay render(String text) {
		
		String[] lines = text.split("\n");
		int fontSize = Math.max(14, videoInfo.imageHeight() / 18);
		Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
		
		BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D scratchGraphics = scratch.createGraphics();
		FontMetrics metrics = scratchGraphics.getFontMetrics(font);
		scratchGraphics.dispose();
		
		float outline = fontSize / 6f;
		int pad = (int) Math.ceil(outline) + 2;
		int lineHeight = metrics.getHeight();
		int textWidth = 0;
		for (String line : lines) {
			textWidth = Math.max(textWidth, metrics.stringWidth(line));
		}
		
		int width = textWidth + 2 * pad;
		int height = lineHeight * lines.length + 2 * pad;
		
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		FontRenderContext context = g.getFontRenderContext();
		g.setStroke(new BasicStroke(outline, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		
		for (int i = 0; i < lines.length; i++) {
			GlyphVector glyphs = font.createGlyphVector(context, lines[i]);
			float x = pad + (textWidth - metrics.stringWidth(lines[i])) / 2f; //centre each line
			Shape shape = glyphs.getOutline(x, pad + i * lineHeight + metrics.getAscent());
			
			g.setColor(Color.BLACK); // wide black stroke first
			g.draw(shape);
			g.setColor(Color.WHITE); // then white fill on top
			g.fill(shape);
		}
		g.dispose();
		
		return new Overlay(width, height, image.getRGB(0, 0, width, height, null, 0, width));
	}
	
	private void blend(ByteBuffer pixels, Overlay o) {
		int imageWidth = videoInfo.imageWidth();
		int imageHeight = videoInfo.imageHeight();
		int channels = videoInfo.imageChannels();
		int stride = videoInfo.imageStride();
		
		int x0 = (imageWidth - o.width()) / 2;
		int y0 = imageHeight - o.height() - imageHeight / 20; //centred, 5% up from the bottom
		
		for (int oy = 0; oy < o.height(); oy++) {
			int y = y0 + oy;
			if (y < 0 || y >= imageHeight) {
				continue;
			}
			
			for (int ox = 0; ox < o.width(); ox++) {
				int x = x0 + ox;
				if (x < 0 || x >= imageWidth) {
					continue;
				}
				
				int argb = o.argb()[oy * o.width() + ox];
				int alpha = argb >>> 24;
				if (alpha == 0) {
					continue;
				}
				
				int r = (argb >> 16) & 0xff;
				int g = (argb >> 8) & 0xff;
				int b = argb & 0xff;
				int base = y * stride + x * channels;
				
				// JavaCV supplies these decoded frames in BGR byte order.
				pixels.put(base, (byte) ((b * alpha + Byte.toUnsignedInt(pixels.get(base)) * (255 - alpha)) / 255));
				pixels.put(base + 1, (byte) ((g * alpha + Byte.toUnsignedInt(pixels.get(base + 1)) * (255 - alpha)) / 255));
				pixels.put(base + 2, (byte) ((r * alpha + Byte.toUnsignedInt(pixels.get(base + 2)) * (255 - alpha)) / 255));
			}
		}
	}
}
