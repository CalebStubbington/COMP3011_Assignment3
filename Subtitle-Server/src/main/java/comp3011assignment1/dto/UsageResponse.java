package comp3011assignment1.dto;

/**
 * JSON body returned by GET /api/v1/global/usage: {usage: seconds (hopefully 137s)}
 * 
 * It holds seconds of audio transcribed in a double. 
 * I replaced Assignment 1's token count.
 */
public record UsageResponse(double usage) {

}
