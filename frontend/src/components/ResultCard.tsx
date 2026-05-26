import { memo, useCallback } from 'react'
import type { SearchResult } from '../types/search'
import { formatScore, formatTimestamp } from '../utils/searchUtils'

interface ResultCardProps {
    index: number
    result: SearchResult
    isSelected: boolean
    onSelect: (index: number) => void
}

function ResultCardComponent({ index, result, isSelected, onSelect }: ResultCardProps) {
    const label = `${result.matchType} match at ${formatTimestamp(Number(result.timestamp))}`

    const handleSelect = useCallback(() => {
        onSelect(index)
    }, [index, onSelect])

    return (
        <li>
            <button
                type="button"
                className={`result-card ${isSelected ? 'selected' : ''}`}
                onClick={handleSelect}
                aria-label={label}
            >
                <div className="thumb-wrap">
                    <img src={result.s3ThumbnailUrl} alt={label} loading="lazy" />
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

export const ResultCard = memo(ResultCardComponent)
