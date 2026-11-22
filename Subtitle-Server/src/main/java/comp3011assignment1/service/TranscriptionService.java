/*
 *  * Authors:
 *   1. Caleb Stubbington, a2991661 in collarboration with GPT 5 & Claude Sonnet 5.5
 */

package comp3011assignment1.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.MultipartField;
import com.openai.models.audio.AudioModel;
import com.openai.models.audio.AudioResponseFormat;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import com.openai.models.audio.transcriptions.TranscriptionVerbose;

/**
 * Service for communicating with the external cloud STT API (OpenAI whisper-1)
 * 
 * Requests the verbose JSON format with both word and segment timestamps, and adds
 * the audio duration to the global usage total. This is only called on a cache miss,
 * so usage only counts audio we actually paid to transcribe.
 * 
 * The API key comes from the OPENAI_API_KEU environment variable if set,
 * otherwise its from a key.txt file in the folder the server was started from. 
 * The client is created per request so the application can start without a key.
 */

@Service
public class TranscriptionService {
	
	private final GlobalStatsService globalStatsService;
	
	private static final Path KEY_FILE = Path.of("key.txt");
	
	public TranscriptionService(GlobalStatsService globalStatsService) {
		this.globalStatsService = globalStatsService;
	}
	
	public TranscriptionVerbose transcribe(byte[] audioData) {
		
		OpenAIClient client = createClient();
		
		TranscriptionCreateParams params =
				TranscriptionCreateParams.builder()
				.file(MultipartField.<InputStream>builder()
						.value(new ByteArrayInputStream(audioData))
						.filename("audio.wav")
						.build())
				.model(AudioModel.WHISPER_1)
				.responseFormat(AudioResponseFormat.VERBOSE_JSON)
				.timestampGranularities(List.of(
						TranscriptionCreateParams.TimestampGranularity.WORD,
						TranscriptionCreateParams.TimestampGranularity.SEGMENT))
				.build();
		
		TranscriptionVerbose transcription = client.audio()
				.transcriptions()
				.create(params)
				.asVerbose();
		
		globalStatsService.addUsage(transcription.duration());
		
		return transcription;
		
		
	}
	
	private OpenAIClient createClient() {
		String envKey = System.getenv("OPENAI_API_KEY");
		if (envKey != null && !envKey.isBlank()) {
			return OpenAIOkHttpClient.fromEnv();
		}
		try {
			String fileKey = Files.readString(KEY_FILE).trim();
			if (fileKey.isEmpty()) {
				throw new IllegalStateException("key.txt is empty");
			}
			return OpenAIOkHttpClient.builder().apiKey(fileKey).build();
		} catch (IOException e) {
			throw new IllegalStateException("No OPENAI_API_KEY set and key.txt could not be read from " + KEY_FILE.toAbsolutePath(), e);
		}
	}


}
