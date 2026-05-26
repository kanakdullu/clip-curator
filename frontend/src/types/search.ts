export type MatchType = 'audio' | 'visual'

export interface SearchResult {
    mediaAssetId: string
    matchType: MatchType
    similarityScore: number
    timestamp: number
    contentSnippet: string | null
    s3ThumbnailUrl: string
    s3VideoUrl?: string | null
}
