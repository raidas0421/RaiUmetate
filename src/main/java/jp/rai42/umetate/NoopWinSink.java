package jp.rai42.umetate;
/** Accepts requests without emitting external events. */
public final class NoopWinSink implements WinSink {
    public Result send(WinRequest request) { return new Result(true, 0, request.count(), "送信なし"); }
}
