package com.roadguard.service;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ReplaySnapshot implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long requestId;
    private List<ReplayPoint> timeline = new ArrayList<>();

    public ReplaySnapshot() {
    }

    public ReplaySnapshot(Long requestId, List<ReplayPoint> timeline) {
        this.requestId = requestId;
        this.timeline = timeline != null ? timeline : new ArrayList<>();
    }

    public Long getRequestId() {
        return requestId;
    }

    public void setRequestId(Long requestId) {
        this.requestId = requestId;
    }

    public List<ReplayPoint> getTimeline() {
        return timeline;
    }

    public void setTimeline(List<ReplayPoint> timeline) {
        this.timeline = timeline;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReplaySnapshot that = (ReplaySnapshot) o;
        return Objects.equals(requestId, that.requestId)
                && Objects.equals(timeline, that.timeline);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestId, timeline);
    }
}
