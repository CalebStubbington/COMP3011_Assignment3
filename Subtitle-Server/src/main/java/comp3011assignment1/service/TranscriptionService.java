package comp3011assignment1.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
 */

@Service
public class TranscriptionService {
	
	private final GlobalStatsService globalStatsService;
	
	public TranscriptionService(GlobalStatsService globalStatsService) {
		this.globalStatsService = globalStatsService;
	}
	
	public TranscriptionVerbose transcribe(byte[] audioData) {
		
		OpenAIClient client = OpenAIOkHttpClient.fromEnv();
		
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


}
