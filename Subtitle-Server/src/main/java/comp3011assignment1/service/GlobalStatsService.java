package comp3011assignment1.service;

import java.util.concurrent.atomic.DoubleAdder;

import org.springframework.stereotype.Service;

import comp3011assignment1.dto.UsageResponse;

/**
 * Service responsible for maintaining the cumulative transcription usage in seconds.
 * 
 * DoubleAdder is user so concurrent requests can add usage safely without locking.
 * 
 * Gets updated by TranscriptionService everytime it has a cache miss.
 * 
 */

@Service
public class GlobalStatsService {
	
	// total seconds of audio transcribed
	private final DoubleAdder usageSeconds = new DoubleAdder();
	
	// called once per uncached transcription
	public void addUsage(double seconds) {
		usageSeconds.add(seconds);
	}
	
	public UsageResponse getUsage() {
		return new UsageResponse(usageSeconds.sum());		
	}
}
