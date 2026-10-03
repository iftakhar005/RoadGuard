package com.roadguard.service;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

public class ReplayPoint implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String t;
    private String type;
    private Double lat;
    private Double lng;
    private String status;

    public ReplayPoint() {
    }

    public ReplayPoint(String t, String type, Double lat, Double lng, String status) {
        this.t = t;
        this.type = type;
        this.lat = lat;
        this.lng = lng;
        this.status = status;
    }

    public String getT() {
        return t;
    }

    public void setT(String t) {
        this.t = t;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Double getLat() {
        return lat;
    }

    public void setLat(Double lat) {
        this.lat = lat;
    }

    public Double getLng() {
        return lng;
    }

    public void setLng(Double lng) {
        this.lng = lng;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReplayPoint that = (ReplayPoint) o;
        return Objects.equals(t, that.t)
                && Objects.equals(type, that.type)
                && Objects.equals(lat, that.lat)
                && Objects.equals(lng, that.lng)
                && Objects.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(t, type, lat, lng, status);
    }
}
