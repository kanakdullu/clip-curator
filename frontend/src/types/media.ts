export interface CompletedAsset {
    mediaAssetId: string
    filename: string
    createdAt: string
    s3ThumbnailUrl: string | null
    s3VideoUrl: string
}
