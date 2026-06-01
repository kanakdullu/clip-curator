import { useMemo, useState } from 'react'
import type { SearchResult } from '../types/search'
import { formatScore, formatTimestamp, looksLikeVideoUrl } from '../utils/searchUtils'

interface ResultCardProps {
    index: number
    result: SearchResult
    fallbackThumbnailUrl: string | null
    isSelected: boolean
    onSelect: (index: number) => void
}

export function ResultCard({ index, result, fallbackThumbnailUrl, isSelected, onSelect }: ResultCardProps) {
    const label = `${result.matchType} match at ${formatTimestamp(Number(result.timestamp))}`
    const [hasThumbnailError, setHasThumbnailError] = useState(false)

    const resolvedThumbnailUrl = useMemo(() => {
        if (result.s3ThumbnailUrl && !looksLikeVideoUrl(result.s3ThumbnailUrl)) {
            return result.s3ThumbnailUrl
        }

        if (fallbackThumbnailUrl && !looksLikeVideoUrl(fallbackThumbnailUrl)) {
            return fallbackThumbnailUrl
        }

        return null
    }, [fallbackThumbnailUrl, result.s3ThumbnailUrl])

    return (
        <li>
            <button
                type="button"
                className={`result-card ${isSelected ? 'selected' : ''}`}
                onClick={() => onSelect(index)}
                aria-label={label}
            >
                <div className="thumb-wrap">
                    {resolvedThumbnailUrl && !hasThumbnailError ? (
                        <img
                            src={resolvedThumbnailUrl}
                            alt={label}
                            loading="lazy"
                            onError={() => setHasThumbnailError(true)}
                        />
                    ) : (
                        <div className="thumb-placeholder">Preview unavailable</div>
                    )}
                    <span className={`match-chip ${result.matchType}`}>{result.matchType}</span>
                    <span className="score-chip">{formatScore(result.similarityScore)}</span>
                </div>

                <div className="result-copy">
                    <p className="timestamp">{formatTimestamp(Number(result.timestamp))}</p>
                    <p className="snippet">{result.contentSnippet ?? 'Visual match detected in this moment.'}</p>
                </div>
            </button>
        </li>
    )
}
