/*
 *  * Authors:
 *   1. Caleb Stubbington, a2991661 in collarboration with Claude Sonnet 5.5
 */

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

//AI Assistance and Acknowledgement
//
//AI assistance was utilised throughout the development process
//as a supporting tool. Claude (Anthropic) was used to assist with:
//
//- Exploring potential software architectures and code structures.
//- Debugging and troubleshooting implementation issues.
//- Explaining programming concepts and suggesting improvements.
//- Reviewing code for readability, modularity and maintainability.
//- Identifying potential edge cases and improvements to code quality.
//
//All AI-generated suggestions were reviewed, tested, adapted and
//integrated where appropriate. The final implementation,
//architectural decisions, testing and responsibility for the
//code remain with the author.
//
//AI tool: Claude (Anthropic)
//Purpose:** Development assistance, debugging and code review.

/**
 * Background thread that fetcher subtitle cues from the server in 20 second chunks and stores
 * them in a map shared with FrameSubtitlier.
 * 
 * audio is read with its own FFmpegFrameGrabber, never the player's, so
 * playback is not touched. Network and decoding happen off the JavaFX and worker threads.
 * 
 * Ever failure such as server down, network lost and media removed is caught, logged and the chunk is retried after a delay.
 * The thread never dies, and nothing is requested while subtitles are off.
 * 
 * Audio is converted to 16 kHz mono 16-bit, about 640KB per 20 seconds, which is plenty for Whisper's 25 mb limit.
 * 
 * Trade-offs: 20 seconds chunks mean fewer requests but a delay of several seconds before the first subtitles, and a phrase spoken across
 * a chunk boundary can be split. Chunks are in parallel. An idle fetcher polls every 200ms instead of waiting to be woken.
 */

public class SubtitleFetcher implements Runnable{
	private static final String URL = "http://localhost:8080/api/v1/subtitles";
	private static final long CHUNK_US = 20_000_000L; // length of audio sent per request.
	private static final int LOOKAHEAD_CHUNKS = 2; // how many chunks ahead of playback to prepare
	private static final long RETRY_MS = 5_000; // wait before retrying a failed chunk
	private static final int SAMPLE_RATE = 16_000; // Hz sent to the server.
	
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
	
	private volatile long currentUs; // latest playback position
	private volatile boolean running = true; // cleared by stop()
	
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
	
	
	// Chooses the chunk to fetch next
	private Long nextChunk() {
		if (!enabled.getAsBoolean()) {
			return null; // subtites are off
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
        long[] actualStartUs = new long[1]; // a one element array is how a method returns a second value
        byte[] wav = extractWav(chunk * CHUNK_US, actualStartUs);

        if (wav == null) {
            return; // no audio in this chunk
        }
        
        List<SubtitleCue> cues = postToServer(wav, actualStartUs[0] / 1000);
        
        // sends the real start time of the audio as seeking can land slightly early.
        for (SubtitleCue cue : cues) {
            store.put(cue.startUs(), cue);
        }
    }
	
	// Decodes one chunk into 16 kHz mono 16-bit WAV 
	private byte[] extractWav(long startUs, long[] actualStartUs) throws Exception {
		ByteArrayOutputStream pcm = new ByteArrayOutputStream();
		
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(mediaPath)) {
			grabber.setSampleRate(SAMPLE_RATE); // FFmpeg resamples for us
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
					break; // collected a full chunk
				}
				if (frame.samples != null && frame.samples[0] instanceof ShortBuffer samples) {
					ShortBuffer copy = samples.duplicate(); // do not disturb the original buffer's position
					while (copy.hasRemaining()) {
						short value = copy.get();
						pcm.write(value & 0xff); // WAV is low byte first
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
		buffer.putInt(36 + pcm.length); // file size minus 8
		buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
		buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII)); // the trailing space is part of the format
		buffer.putInt(16); // size of this block
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
		
		// the boundary sperates the form's field and must not appear inside the data.
		String boundary = "----subtitles" + System.nanoTime();
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"startMs\"\r\n\r\n" + startMs + "\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"chunk.wav\"\r\n" + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(wav);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		
		HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
				.timeout(Duration.ofSeconds(60)).header("Content-Type", "multipart/form-data; boundary=" + boundary) // stops a hung server freezing this thread forever
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
