package jp.rai42.umetate;
public interface WinSink extends AutoCloseable {
    record Result(boolean completed, int sent, int requested, String error) {}
    Result send(WinRequest request);
    @Override default void close() {}
}
