package jp.rai42.umetate;

final class FountainPath {
    private FountainPath() {}
    static double coordinate(double start, double end, double t) { return start * (1 - t) + end * t; }
    static double height(double start, double end, double t, double arc) { return coordinate(start, end, t) + 4 * arc * t * (1 - t); }
}
