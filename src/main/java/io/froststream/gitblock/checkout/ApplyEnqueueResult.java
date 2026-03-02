package io.froststream.untitled8.plotgit.checkout;

public record ApplyEnqueueResult(boolean accepted, String jobId, String message) {
    public static ApplyEnqueueResult accepted(String jobId, String message) {
        return new ApplyEnqueueResult(true, jobId, message);
    }

    public static ApplyEnqueueResult rejected(String message) {
        return new ApplyEnqueueResult(false, null, message);
    }
}
