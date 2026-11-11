package edu.lcl.rag.speech;

/** 每次录音使用独立实例；音频均为 16kHz/mono/s16le PCM。 */
public interface AsrProvider {
    interface Listener {
        void onReady();
        void onSentence(int segmentId, String text, boolean sentenceEnd);
        void onComplete();
        void onError(String code, String message);
    }

    void start(Listener listener);
    boolean send(byte[] pcm);
    boolean finish();
    void cancel();
}
