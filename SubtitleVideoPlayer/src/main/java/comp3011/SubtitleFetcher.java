package comp3011;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.BooleanSupplier;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SubtitleFetcher implements Runnable{
	private static final String URL = "http://localhost:8080/api/v1/subtitles";
	private static final long CHUNK_US = 20_000_000L;
	private static final int LOOKAHEAD_CHUNKS = 2;
	private static final long RETRY_MS = 3_000;
	private static final int SAMPLE_RATE = 16_000;
	
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	
	private final ObjectMapper mapper = new ObjectMapper();
	
	// Chunks already fetched successfully.
	private final Set<Long> done = ConcurrentHashMap.newKeySet();
	// Chunks that failed recently, and when we may try them again (only used by the fetcher thread).
	private final Map<Long, Long> retryAtMs = new HashMap<>();
	
	private final String mediaPath;
	private final long durationUs;
	private final ConcurrentSkipListMap<Long, SubtitleCue> store;
	private final BooleanSupplier enabled;
	
	private volatile long currentUs;
	private volatile boolean running = true;
	
	SubtitleFetcher(String mediaPath, long durationUs, ConcurrentSkipListMap<Long, SubtitleCue> store, BooleanSupplier enabled) {
		this.mediaPath = mediaPath;
		this.durationUs = durationUs;
		this.store = store;
		this.enabled = enabled;
	}
	
	// Called by the frame processor on every frame so we know where playback is.
	void noteTimestamp(long timestampUs) {
		currentUs = timestampUs;
	}
	
	void stop() {
		running = false;
	}
	
	@Override
	public void run() {
		while (running) {
			Long chunk = null;
			try {
				chunk = nextChunk();
				if (chunk == null) {
					Thread.sleep(200); // nothing to do (subtitles off, or all nearby chunks done)
					continue;
				}
				fetchChunk(chunk);
				done.add(chunk);
			} catch (InterruptedException e) {
				return;
			} catch (Exception e) {
				// Server down, network lost, media removed, bad response
				// Never let this escape, back off and try this chunk again later.
				if (chunk != null) {
					retryAtMs.put(chunk, System.currentTimeMillis() + RETRY_MS);
				}
			}
		}
	}
	
	private Long nextChunk() {
		if (!enabled.getAsBoolean()) {
			return null;
		}
		
		long base = Math.max(0,  currentUs) / CHUNK_US;
		long now = System.currentTimeMillis();
		
		for (int k = 0; k <= LOOKAHEAD_CHUNKS; k++) {
			long chunk = base + k;
			if (durationUs > 0 && chunk * CHUNK_US >= durationUs) {
				break; // past the end of the video
			}
			if (done.contains(chunk) || retryAtMs.getOrDefault(chunk, 0L) > now) {
				continue;
			}
			return chunk;
		}
		return null;
	}
	
    private void fetchChunk(long chunk) throws Exception {
        long[] actualStartUs = new long[1];
        byte[] wav = extractWav(chunk * CHUNK_US, actualStartUs);

        if (wav == null) {
            System.out.println("Chunk " + chunk + ": no audio extracted (null WAV)");
            return;
        }

        List<SubtitleCue> cues = postToServer(wav, actualStartUs[0] / 1000);

        for (SubtitleCue cue : cues) {
            store.put(cue.startUs(), cue);
        }
    }
	
	
	private byte[] extractWav(long startUs, long[] actualStartUs) throws Exception {
		ByteArrayOutputStream pcm = new ByteArrayOutputStream();
		
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(mediaPath)) {
			grabber.setSampleRate(SAMPLE_RATE);
			grabber.setAudioChannels(1);
			grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
			grabber.start();
			
			if (!grabber.hasAudio()) {
				return null;
			}
			if (startUs > 0) {
				grabber.setAudioTimestamp(startUs);
			}
			
			boolean first = true;
			Frame frame;
			while ((frame = grabber.grabSamples()) != null) {
				if (first) {
					actualStartUs[0] = frame.timestamp;
					first = false;
				}
				if (frame.timestamp - actualStartUs[0] >= CHUNK_US) {
					break;
				}
				if (frame.samples != null && frame.samples[0] instanceof ShortBuffer samples) {
					ShortBuffer copy = samples.duplicate();
					while (copy.hasRemaining()) {
						short value = copy.get();
						pcm.write(value & 0xff);
						pcm.write((value >> 8) & 0xff); // little-endian
					}
				}
			}
		}
		
		if (pcm.size() < SAMPLE_RATE * 2 / 5) {
			return null; // under 0.2 seconds, Whisper would reject it
		}
		return wrapAsWav(pcm.toByteArray());
	}
	
	// Puts a standard 44 byte WAV header on raw 16-bit mono PCM data.
	private byte[] wrapAsWav(byte[] pcm) {
		ByteBuffer buffer = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
		buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
		buffer.putInt(36 + pcm.length);
		buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
		buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
		buffer.putInt(16);
		buffer.putShort((short) 1); // PCM
		buffer.putShort((short) 1); // mono
		buffer.putInt(SAMPLE_RATE);
		buffer.putInt(SAMPLE_RATE * 2); // bytes per second
		buffer.putShort((short) 2); // bytes per sample frame
		buffer.putShort((short) 16); // bits per sample
		buffer.put("data".getBytes(StandardCharsets.US_ASCII));
		buffer.putInt(pcm.length);
		buffer.put(pcm);
		return buffer.array();
	}
	
	// Sends the WAV as a multipart form (same format as the curl test) and parses the cues.
	private List<SubtitleCue> postToServer(byte[] wav, long startMs) throws Exception {
		String boundary = "----subtitles" + System.nanoTime();
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"startMs\"\r\n\r\n" + startMs + "\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"chunk.wav\"\r\n" + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(wav);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		
		HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
				.timeout(Duration.ofSeconds(60)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
		
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("Subtitle server return HTTP " + response.statusCode());
		}
		
		List<SubtitleCue> cues = new ArrayList<>();
		for (JsonNode cue : mapper.readTree(response.body()).path("cues")) {
			// Server uses milliseconds, the player works in microseconds.
			cues.add(new SubtitleCue(cue.path("startMs").asLong() * 1000, cue.path("endMs").asLong() * 1000, cue.path("text").asText()));
		}
		return cues;
	}
}
