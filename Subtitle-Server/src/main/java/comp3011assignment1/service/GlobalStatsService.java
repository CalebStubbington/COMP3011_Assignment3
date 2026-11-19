package comp3011assignment1.service;

import java.util.concurrent.atomic.DoubleAdder;

import org.springframework.stereotype.Service;

import comp3011assignment1.dto.UsageResponse;

/**
 * Service responsible for maintaining the cumulative transcription usage in seconds.
 * 
 * DoubleAdder is user so concurrent requests can add usage safely without locking.
 */

@Service
public class GlobalStatsService {
	
	private final DoubleAdder usageSeconds = new DoubleAdder();
	
	public void addUsage(double seconds) {
		usageSeconds.add(seconds);
	}
	
	public UsageResponse getUsage() {
		return new UsageResponse(usageSeconds.sum());		
	}
}
