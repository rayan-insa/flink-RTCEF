package edu.insa_lyon.streams.rtcef_flink;

import java.io.Serializable;

/**
 * POJO representing a maritime event for the Collector component.
 * Used for windowing and JSON serialization.
 */
public class MaritimeEvent implements Serializable {

    private long timestamp;
    private String mmsi;
    private double lon;
    private double lat;
    private double speed;
    private double heading;
    private double cog;
    private String annotation;

    // Default constructor for serialization
    public MaritimeEvent() {
    }

    public MaritimeEvent(long timestamp, String mmsi, double lon, double lat,
            double speed, double heading, double cog, String annotation) {
        this.timestamp = timestamp;
        this.mmsi = mmsi;
        this.lon = lon;
        this.lat = lat;
        this.speed = speed;
        this.heading = heading;
        this.cog = cog;
        this.annotation = annotation;
    }

    // Getters and Setters
    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getMmsi() {
        return mmsi;
    }

    public void setMmsi(String mmsi) {
        this.mmsi = mmsi;
    }

    public double getLon() {
        return lon;
    }

    public void setLon(double lon) {
        this.lon = lon;
    }

    public double getLat() {
        return lat;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }

    public double getSpeed() {
        return speed;
    }

    public void setSpeed(double speed) {
        this.speed = speed;
    }

    public double getHeading() {
        return heading;
    }

    public void setHeading(double heading) {
        this.heading = heading;
    }

    public double getCog() {
        return cog;
    }

    public void setCog(double cog) {
        this.cog = cog;
    }

    public String getAnnotation() {
        return annotation;
    }

    public void setAnnotation(String annotation) {
        this.annotation = annotation;
    }

    /**
     * Convert to JSON string for file output.
     */
    public String toJson() {
        return String.format(
                "{\"timestamp\":%d,\"mmsi\":\"%s\",\"lon\":%.6f,\"lat\":%.6f,\"speed\":%.2f,\"heading\":%.2f,\"cog\":%.2f,\"annotation\":\"%s\"}",
                timestamp, mmsi, lon, lat, speed, heading, cog, annotation);
    }

    @Override
    public String toString() {
        return toJson();
    }
}
