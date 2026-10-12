package jp.rai42.umetate;

public record FrameHealthSnapshot(double health, double maximum, double lastDamage, long revision, String status) {}
