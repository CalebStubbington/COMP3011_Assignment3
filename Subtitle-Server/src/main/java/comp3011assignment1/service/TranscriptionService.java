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

import org.springframework.beans.factory.annotation.Value;

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
 * 
 * A new client is built per request, so the server starts without a key and checks
 * for the key, at a small cost.
 * 
 */

@Service
public class TranscriptionService {
	
	private static final String DEFAULT_KEY_FILE = "key.txt";
	
	private final GlobalStatsService globalStatsService;
	
	private final String configuredKeyFile;
	
	private volatile String cachedKey;
	
	public TranscriptionService(GlobalStatsService globalStatsService, @Value("${openai.key-file:}") String configuredKeyFile) {
		this.globalStatsService = globalStatsService;
		this.configuredKeyFile = configuredKeyFile;
	}
	
	public TranscriptionVerbose transcribe(byte[] audioData) {
		
		OpenAIClient client = createClient();
		
		TranscriptionCreateParams params =
				TranscriptionCreateParams.builder()
				.file(MultipartField.<InputStream>builder()
						// the .wav name matters as OpenAI picks its decoder from the file extension
						.value(new ByteArrayInputStream(audioData))
						.filename("audio.wav")
						.build())
				.model(AudioModel.WHISPER_1)
				// Verbose JSON is the only format that includes timings and confidence values.
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
	
	// FInds the API key in the environment variable then looks for key.txt and builds a client with it
	private OpenAIClient createClient() {
		return OpenAIOkHttpClient.builder().apiKey(apiKey()).build();
	}
	
	private String apiKey() {
		String key = cachedKey;
		if(key == null) {
			synchronized (this) {
				if (cachedKey == null) {
					cachedKey = loadKey();
				}
				key = cachedKey;
			}
		}
		return key;
	}
	
	private String loadKey() {
		String keyFile = System.getenv("OPENAI_API_KEY_FILE");
		
		if (keyFile != null && !keyFile.isBlank()) {
			return readKeyFile(Path.of(keyFile));
		}
		
		if (!configuredKeyFile.isBlank()) {
			return readKeyFile(Path.of(configuredKeyFile));
		}
		
		String envKey = System.getenv("OPEN_API_KEY");
		
		if (envKey != null && !envKey.isBlank()) {
			return envKey;
		}
		
		return readKeyFile(Path.of(DEFAULT_KEY_FILE));
	}
	
	private String readKeyFile(Path path) {
		try {
			return Files.readString(path);
		} catch (IOException e) {
			throw new IllegalStateException("API key file could not be read");
		}
	}
}
