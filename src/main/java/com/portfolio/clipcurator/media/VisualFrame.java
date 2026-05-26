package com.portfolio.clipcurator.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "visual_frames")
public class VisualFrame {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_asset_id", nullable = false)
    private MediaAsset mediaAsset;

    @Column(name = "timestamp", nullable = false, precision = 10, scale = 3)
    private BigDecimal timestamp;

    @Column(name = "s3_image_url", nullable = false, length = 1024)
    private String s3ImageUrl;

    protected VisualFrame() {
    }

    public VisualFrame(UUID id, MediaAsset mediaAsset, BigDecimal timestamp, String s3ImageUrl) {
        this.id = id;
        this.mediaAsset = mediaAsset;
        this.timestamp = timestamp;
        this.s3ImageUrl = s3ImageUrl;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public MediaAsset getMediaAsset() {
        return mediaAsset;
    }

    public void setMediaAsset(MediaAsset mediaAsset) {
        this.mediaAsset = mediaAsset;
    }

    public BigDecimal getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(BigDecimal timestamp) {
        this.timestamp = timestamp;
    }

    public String getS3ImageUrl() {
        return s3ImageUrl;
    }

    public void setS3ImageUrl(String s3ImageUrl) {
        this.s3ImageUrl = s3ImageUrl;
    }
}
