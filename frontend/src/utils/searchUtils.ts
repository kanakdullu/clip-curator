import type { SearchResult } from '../types/search'

export const looksLikeVideoUrl = (url: string | null | undefined): url is string => {
    if (!url) {
        return false
    }

    const normalized = decodeURIComponent(url).toLowerCase()
    return /\.(mp4|mov|m4v)(?:$|\?)/.test(normalized)
}

export const resolveVideoUrl = (result: SearchResult, allResults: SearchResult[]): string | null => {
    if (looksLikeVideoUrl(result.s3VideoUrl)) {
        return result.s3VideoUrl
    }

    if (looksLikeVideoUrl(result.s3ThumbnailUrl)) {
        return result.s3ThumbnailUrl
    }

    const fallback = allResults.find((candidate) => {
        return (
            candidate.mediaAssetId === result.mediaAssetId &&
            (looksLikeVideoUrl(candidate.s3VideoUrl) || looksLikeVideoUrl(candidate.s3ThumbnailUrl))
        )
    })

    if (!fallback) {
        return null
    }

    if (looksLikeVideoUrl(fallback.s3VideoUrl)) {
        return fallback.s3VideoUrl
    }

    if (looksLikeVideoUrl(fallback.s3ThumbnailUrl)) {
        return fallback.s3ThumbnailUrl
    }

    return null
}

export const formatTimestamp = (rawSeconds: number): string => {
    if (!Number.isFinite(rawSeconds) || rawSeconds < 0) {
        return '0:00'
    }

    const totalSeconds = Math.floor(rawSeconds)
    const hours = Math.floor(totalSeconds / 3600)
    const minutes = Math.floor((totalSeconds % 3600) / 60)
    const seconds = totalSeconds % 60

    if (hours > 0) {
        return `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
    }

    return `${minutes}:${String(seconds).padStart(2, '0')}`
}

export const formatScore = (score: number): string => `${Math.round(score * 100)}%`
