package comp3011assignment1.service;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.openai.models.audio.transcriptions.TranscriptionSegment;
import com.openai.models.audio.transcriptions.TranscriptionVerbose;
import com.openai.models.audio.transcriptions.TranscriptionWord;

import comp3011assignment1.dto.Cue;

/**
 * Service that turns Whisper output into subtitle cues, and caches the results.
 * 
 * The cache is keyed by a hash of the audio bytes, so repeat plays and seeks never
 * pay for the same audio twice. Storing the CompletableFuture also means that two
 * simultaneous requests for the same audio share a single OpenAI call.
 */

@Service
public class SubtitleService {

	private static final int MAX_PER_LINE = 42;
	private static final int MAX_CHARS = MAX_PER_LINE * 2;
	private static final double MAX_SECONDS = 7.0;
	private static final double MIN_SECONDS = 1.0;
	
	private final TranscriptionService transcriptionService;
	
	// Cues are stored relative to the start of the chunk, the offset is added on the way out.
	private final ConcurrentHashMap<String, CompletableFuture<List<Cue>>> cache = new ConcurrentHashMap<>();
	
	public SubtitleService(TranscriptionService transcriptionService) {
		this.transcriptionService = transcriptionService;
	}
	
	// hashes the audio and calls computeIfAbsent, so a cache hit never reaches TranscriptionService and never adds to usage.
	public List<Cue> getCues(byte[] wav, long offsetMs) {
		String key = sha256(wav);
		
		CompletableFuture<List<Cue>> future = cache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> buildCues(transcriptionService.transcribe(wav))));
		
		List<Cue> relative;
		try {
			relative = future.get(60, TimeUnit.SECONDS);
		} catch (Exception e) {
			cache.remove(key, future); // never cache a failure.
			throw new RuntimeException("Failed to transcribe audio", e);
		}
		
		return relative.stream().map(c -> new Cue(c.startMs() + offsetMs, c.endMs() + offsetMs, c.text())).toList();
	}
	
	// applies the spec's skip rule, then pairs each token with a time.
	private List<Cue> buildCues(TranscriptionVerbose transcription) {
		List<Cue> cues = new ArrayList<>();
		List<TranscriptionWord> words = transcription.words().orElse(List.of());
		
		for (TranscriptionSegment segment : transcription.segments().orElse(List.of())) {
			
			// Spec: not displayed when noSpeechProb > 0.6 AND average log probability < -1.0
			if (segment.noSpeechProb() > 0.6 && segment.avgLogprob() < -1.0) {
				continue;
			}
			
			String text = segment.text().trim();
			if (text.isEmpty()) {
				continue;
			}
			
			double segStart = segment.start();
			double segEnd = segment.end();
			String[] tokens = text.split("\\s+");
			
			// Whisper's word entries have no punctuation, so match them one-to-one to the
			// segment's tokens (which do).
			List<double[]> times = new ArrayList<>();
			for (TranscriptionWord w : words) {
				if (w.start() >= segStart - 0.05 && w.start() < segEnd + 0.05) {
					times.add(new double[] {w.start(), w.end() });
				}
			}
			
			// If the counts don't match, spread the tokens across the segment by length.
			if (times.size() != tokens.length) {
				times.clear();
				int total = Arrays.stream(tokens).mapToInt(String::length).sum();
				double t = segStart;
				for (String token : tokens) {
					double d = (segEnd - segStart) * token.length() / total;
					times.add(new double[] { t, t + d });
					t += d;
				}
			}
			
			splitIntoCues(tokens, times, cues);
		}
		
		// Don't let a cue run into the start of the next one.
		for (int i = 0; i + 1 < cues.size(); i++) {
			Cue current = cues.get(i);
			Cue next = cues.get(i + 1);
			if (current.endMs() > next.startMs()) {
				cues.set(i, new Cue(current.startMs(), next.startMs(), current.text()));
			}
		}
		
		return cues;
	}
	
	// applies the length and timing limits, and wrapsLines handles the two-line layout.
	private void splitIntoCues(String[] tokens, List<double[]> times, List<Cue> out) {
		int i = 0;
		while (i < tokens.length) {
			StringBuilder sb = new StringBuilder();
			int j = i;
			
			while (j < tokens.length) {
				String next = sb.length() == 0 ? tokens[j] : sb + " " + tokens[j];
				
				// Stop before exceeding two full lines or the maximum display time.
				if (j > i && (next.length() > MAX_CHARS || times.get(j)[0] > MAX_SECONDS)) {
					break;
				}
			}
			
			double start = times.get(i)[0];
			double end = Math.min(start + MAX_SECONDS, Math.max(times.get(j - 1)[1] + 0.3, MIN_SECONDS));
			out.add(new Cue(Math.round(start * 1000), Math.round(end * 1000), wrapLines(sb.toString())));
		}
	}
	
	// One line if it fits, otherwise break at the most balanced space, preferring spaces after punctuation.
	private String wrapLines(String text) {
		if (text.length() <= MAX_PER_LINE) {
			return text;
		}
		
		int best = -1;
		double baseScore = Double.MAX_VALUE;
		
		for (int i = 1; i < text.length() - 1; i++) {
			if (text.charAt(i) != ' ') {
				continue;
			}
			int first = i;
			int second = text.length() - i - 1;
			if (first > MAX_PER_LINE || second > MAX_PER_LINE) {
				continue;
			}
			double score = Math.abs(first - second) - (",;:.!?".indexOf(text.charAt(i - 1)) >= 0 ? 10 : 0);
			if (score < baseScore) {
				baseScore = score;
				best = i;
			}
		}
		
		if (best < 0) {
			best = text.lastIndexOf(' ', text.length() / 2);
		}
		if (best < 0) {
			return text;
		}
		return text.substring(0, best) + "\n" + text.substring(best + 1);
	}
	
	// hash method we use to hash the audio
	private static String sha256(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
